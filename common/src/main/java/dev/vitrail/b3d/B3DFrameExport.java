package dev.vitrail.b3d;

import dev.vitrail.Vitrail;
import dev.vitrail.frame.FrameExport;
import dev.vitrail.frame.FrameResources;

import b3dinterop.api.backend.GraphicsApi;
import b3dinterop.api.frame.FrameColorInfo;
import b3dinterop.api.frame.FrameDemand;
import b3dinterop.api.frame.FrameExchange;
import b3dinterop.api.frame.FrameId;
import b3dinterop.api.frame.FrameSemantic;
import b3dinterop.api.frame.FrameSession;
import b3dinterop.api.frame.FrameSnapshot;
import b3dinterop.api.frame.HistoryResetReason;
import b3dinterop.api.frame.MotionVectorConvention;
import b3dinterop.api.frame.DepthConvention;
import b3dinterop.api.frame.TemporalFrameInfo;
import b3dinterop.api.resource.ExternalImage;
import b3dinterop.api.resource.ExternalResourceExchange;
import b3dinterop.api.resource.ImageAspect;
import b3dinterop.api.resource.QueueFamilyOwnership;
import b3dinterop.api.resource.ResourceKey;
import b3dinterop.api.resource.ResourceLifetime;
import b3dinterop.api.resource.ResourceOwnership;
import b3dinterop.api.resource.ResourcePublication;
import b3dinterop.api.resource.ResourceState;
import b3dinterop.api.resource.TexelFormat;
import b3dinterop.api.resource.vulkan.VulkanImageState;
import b3dinterop.api.sync.SyncPrimitive;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import dev.vitrail.frame.EngineImage;
import dev.vitrail.frame.EngineImageState;
import org.joml.Vector3dc;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Publishes this engine's frames through the external frame API, when that API is present.
 * <p>
 * <strong>Optional in both directions.</strong> The mod that owns this API may be absent, in which
 * case no class here is ever loaded and the engine behaves exactly as it did before this file
 * existed. It may be present with nothing consuming frames, in which case the export runs and costs
 * a descriptor per resource per frame and no GPU work at all. It may be present with a consumer, in
 * which case the frame's finished colour, the pack's converted depth and - only when asked for - this
 * engine's motion vectors are described as borrowed resources that this engine continues to own.
 * <p>
 * <strong>Nothing is copied, read back, duplicated or handed over.</strong> Every resource here is
 * one the engine made for its own rendering, described with its live handle: no render target exists
 * because of this class, no pixel is read on the CPU, and no lifetime is changed. The ownership
 * declared on every descriptor is {@link ResourceOwnership#BORROWED_PERSISTENT}, which is the whole
 * of the answer to "who may destroy it" - this engine, on the same paths as before, whether or not
 * anybody is listening.
 * <p>
 * <strong>What is published and what is not.</strong> Scene colour is the frame the chain finished,
 * without the interface, at the size the pack drew it - the render size, which is the window's size
 * only while the render scale is off. When the scale is engaged the world is drawn into a stand-in
 * smaller than the window and this engine's own upscale of it is what the window ends up holding, so
 * that second picture is published separately as {@code b3d:upscaled_scene_color}. One semantic per
 * resource: a consumer that upscales needs the scene at the render size, a consumer that composites
 * what the player sees needs the upscale, and neither can tell which it has been handed if one image
 * is offered as both. On a frame the scale did not engage there is no distinct upscale and the second
 * semantic is not published at all - a hundred percent is not a degenerate upscale, it is the
 * absence of one, and the scene colour is then simply the image the window holds.
 * <p>
 * Depth is the pack's own converted copy rather than the device's image: forward over 0..1, at the
 * render size, and it outlives the clear that destroys the device's window-sized depth. Motion
 * vectors are the engine's existing pass, published only on a frame it drew, which is only when a
 * consumer asked for them. <strong>Exposure is not published at all</strong>, because this engine has
 * no exposure value: the packs that compute one do it inside their own shaders from images this
 * engine hands them, and inventing a number here would be inventing metadata.
 * <p>
 * <strong>Colour is reported as unknown, and that is not a gap.</strong> A storage format is not an
 * encoding: nothing in this engine or in the game knows this picture's primaries or transfer
 * function, the picture is past whatever tone mapping the pack does, and there is no paper white to
 * state. {@code FrameColorInfo.unknown} carries that reason, and a consumer that needs more can see
 * that it was not told rather than being told something wrong.
 * <p>
 * <strong>One claim per semantic per frame.</strong> The frame layer drops a semantic two providers
 * claim at once, which would lose it for every consumer rather than pick a winner. So anything this
 * class would add to a frame that already carries it is left alone, with one line in the log naming
 * the semantic and not the provider - this class does not need to know whose frame it is joining.
 */
public final class B3DFrameExport {

	/**
	 * The mod id of the external frame API, as that mod's own loader metadata spells it. Not the same
	 * string as the package the API's classes live in: the package is {@code b3dinterop}, the mod is
	 * {@code b3d-interop}, and a lookup by the wrong one simply never matches.
	 */
	public static final String B3D_MOD_ID = "b3d-interop";

	private static final String PROVIDER_ID = "vitrail";
	private static final String NAMESPACE = "vitrail";

	private static final String REASON_ORDERED =
		"this frame's writes are recorded into the engine's own command buffer and submitted at the "
			+ "frame's end, and a consumer of the external frame API records on that same queue - its "
			+ "submission is behind this engine's in queue order and against the same queue family. That "
			+ "order is an execution dependency and not a memory one: the barrier a consumer records "
			+ "with the state published here is what makes these writes visible to its reads";
	private static final String REASON_RELEASE =
		"this engine cannot name a consumer's completion primitive; the consumer states its own";
	private static final String REASON_LAYOUT =
		"every image this engine owns is created undefined and transitioned once to the general "
			+ "layout, and nothing in the game transitions it again: dynamic rendering names the general "
			+ "layout for every colour and depth attachment, so does every descriptor binding, and the "
			+ "only two transitions in the whole engine are that one and the swapchain's. So the layout "
			+ "is stated rather than guessed, and the stage and access masks published with it are the "
			+ "widest legal scope for it - which is the safe direction for a barrier's source scope";
	private static final String REASON_COLOUR =
		"neither this engine nor the game knows the primaries or the transfer function of the frame "
			+ "it rasterises into; the picture is past whatever tone mapping the pack does and no paper "
			+ "white is stated anywhere, so the encoding is reported as unknown rather than invented";
	private static final String REASON_ROOT =
		"no consumer asked about this frame's colour, and a format is not an encoding";

	private static final Slot SCENE_COLOUR =
		new Slot(ResourceKey.of(NAMESPACE, "scene_colour"), FrameSemantic.SCENE_COLOR);
	private static final Slot UPSCALED_SCENE_COLOUR =
		new Slot(ResourceKey.of(NAMESPACE, "upscaled_scene_colour"),
				FrameSemantic.UPSCALED_SCENE_COLOR);
	private static final Slot DEPTH = new Slot(ResourceKey.of(NAMESPACE, "depth"), FrameSemantic.DEPTH);
	private static final Slot MOTION_VECTORS =
		new Slot(ResourceKey.of(NAMESPACE, "motion_vectors"), FrameSemantic.MOTION_VECTORS);

	private static FrameSession session;
	private static long exported;
	private static long failures;
	private static boolean motionVectorsDrawn;
	private static boolean motionVectorsWanted;
	private static boolean planesAbsentSaid;
	private static long lastDemandVersion = -1L;

	/**
	 * Semantics this export has stood down from, once each: one another provider already put in a
	 * frame, or one this backend cannot describe. Kept so that the reason is said once rather than
	 * once a frame.
	 */
	private static final Set<FrameSemantic> stoodDown = new LinkedHashSet<>();

	/**
	 * Semantics whose image was described as undefined because its state could not be read, once
	 * each. Same reason as {@link #stoodDown}: it is a property of the session, not of a frame.
	 */
	private static final Set<FrameSemantic> unreported = new LinkedHashSet<>();

	private B3DFrameExport() {
	}

	/** Whether the export is attached; false before {@link #attach} and after {@link #detach}. */
	public static synchronized boolean isAttached() {
		return session != null;
	}

	/**
	 * Attaches the export: a frame session and the sink the engine calls.
	 * <p>
	 * Idempotent, because both loaders reach their client setup exactly once but a caller cannot
	 * tell that from here, and a second attach would leave a session nobody closes.
	 */
	public static synchronized void attach() {
		if (session != null) {
			return;
		}

		session = FrameExchange.attach(PROVIDER_ID);
		session.advertises(Set.of(FrameSemantic.SCENE_COLOR, FrameSemantic.UPSCALED_SCENE_COLOR,
			FrameSemantic.DEPTH, FrameSemantic.MOTION_VECTORS));
		FrameExport.install(B3DFrameExport::onFrame);

		Vitrail.logger().info("Publishing this engine's frames to {} as {}: the scene colour at the "
			+ "render size, its upscale when the render scale is engaged, depth, and motion vectors "
			+ "when a consumer asks for them", B3D_MOD_ID, PROVIDER_ID);
	}

	/** Detaches the export, closing everything it published. Idempotent. */
	public static synchronized void detach() {
		if (session == null) {
			FrameExport.uninstall();

			return;
		}

		FrameExport.uninstall();
		SCENE_COLOUR.close();
		UPSCALED_SCENE_COLOUR.close();
		DEPTH.close();
		MOTION_VECTORS.close();
		session.close();
		session = null;
		motionVectorsDrawn = false;
		motionVectorsWanted = false;
		planesAbsentSaid = false;
		lastDemandVersion = -1L;
		stoodDown.clear();
		unreported.clear();

		Vitrail.logger().info("Stopped publishing this engine's frames after {} exported frame(s)",
			exported);
	}

	/**
	 * One frame, from the seam.
	 * <p>
	 * A description of a frame must never be able to fail the frame it describes, so a failure here
	 * says so once, with its stack, and stands the export down rather than being thrown again on
	 * every frame until the session ends. The counter is not decoration: the second failure of a
	 * kind that could not be stopped would otherwise be silent.
	 */
	public static synchronized void onFrame(final FrameResources frame) {
		try {
			contribute(frame);
		} catch (GpuDeviceLossException e) {
			// Not a failure of this export: the device is gone and the whole engine unwinds on it.
			throw e;
		} catch (RuntimeException e) {
			failures++;

			if (failures == 1L) {
				Vitrail.logger().error("Stopped publishing this engine's frames after an error; the "
					+ "picture is unaffected and the export is off for this session", e);
			}

			detach();
		}
	}

	private static boolean contribute(final FrameResources frame) {
		final FrameDemand demand = FrameExchange.demand();

		// The engine reads this where the motion vector pass would be drawn, which is before this
		// seam runs, so what is stated here arms the next frame - a frame no consumer of this one
		// could have asked about, having registered during it.
		sayDemand(demand.wanted(FrameSemantic.MOTION_VECTORS));
		FrameExport.demandMotionVectors(motionVectorsWanted);

		final Optional<FrameSnapshot> open = FrameExchange.currentSnapshot();

		if (open.isEmpty()) {
			return false;
		}

		final FrameSnapshot snapshot = open.get();
		final FrameId frameId = snapshot.frameId();

		exported = frame.index();

		final boolean colourClaimed =
			contributeImage(snapshot, frameId, SCENE_COLOUR, frame.sceneColour());
		boolean upscaledClaimed = false;

		if (frame.hasUpscaledSceneColour()) {
			upscaledClaimed = contributeImage(snapshot, frameId, UPSCALED_SCENE_COLOUR,
					frame.upscaledSceneColour());
		} else {
			// The frame was not rendered small, so the window's picture is the scene and not a second
			// resource. The publication ends rather than standing over the window-sized colour from a
			// frame whose scale has since been turned off, which a consumer would read as this frame's
			// upscale - the one thing the exchange keeps a description from doing is describing a frame
			// that is not the open one.
			UPSCALED_SCENE_COLOUR.close();
		}

		boolean depthClaimed = false;

		if (frame.hasDepth()) {
			depthClaimed = contributeImage(snapshot, frameId, DEPTH, frame.depth());
		} else {
			// This frame carries no depth copy at all, which is a frame whose pack did not fill it - the
			// title screen, a world just left, or a copy the allocation refused. The publication ends
			// rather than standing over an image a consumer cannot trust: the last frame's depth read as
			// this frame's is worse than no depth at all, because nothing downstream can tell.
			DEPTH.close();
		}

		boolean vectorsClaimed = false;

		if (frame.hasMotionVectors()) {
			vectorsClaimed = contributeImage(snapshot, frameId, MOTION_VECTORS, frame.motionVectors());
		} else if (!frame.hasMotionVectorImage()) {
			// The pass gave its image back, which it does on every frame nothing reads it. The
			// publication ends rather than standing over an image that no longer exists: this is the
			// one case where a consumer holding a descriptor has to be told, and the alternative is a
			// handle into freed memory.
			MOTION_VECTORS.close();
		}

		// Said from the frame that drew rather than from the demand: the demand says the pass was
		// armed, and this says it ran.
		sayDrawn(frame.hasMotionVectors());

		if (!colourClaimed && !upscaledClaimed && !depthClaimed && !vectorsClaimed) {
			// Nothing of this frame is this engine's to describe - no pack is drawing it, or the
			// backend is not Vulkan - so its camera state is not published either. A frame with
			// metadata and no resources would be a description of somebody else's frame.
			return false;
		}

		// Declared only for what this class contributed, because a frame carries one convention and
		// it has to belong to the depth and the vectors actually in the frame.
		if (depthClaimed) {
			session.depthConvention(depthConvention(frame));
		}

		if (vectorsClaimed) {
			session.motionVectorConvention(motionVectorConvention());
		}

		if (snapshot.temporal().isEmpty()) {
			session.temporal(temporal(frame, frameId));
		}

		if (snapshot.color().isEmpty()) {
			session.color(FrameColorInfo.unknown(REASON_COLOUR));
		}

		return true;
	}

	/**
	 * Publishes or replaces one image under a key, and declares it as this frame's meaning.
	 * <p>
	 * The three resources differ only in their descriptor, so they share every rule: claim the
	 * semantic or leave it alone, take the live handle, publish or replace, and say when the native
	 * image changed underneath a consumer that was already holding a descriptor for it.
	 *
	 * @return whether this frame carries the semantic because of this call
	 */
	private static boolean contributeImage(
		final FrameSnapshot snapshot,
		final FrameId frameId,
		final Slot slot,
		final EngineImage port
	) {
		if (!claim(snapshot, slot.semantic)) {
			return false;
		}

		final long image = port.nativeImage();

		if (!port.isNative()) {
			// Not a Vulkan image, which is the whole of what this backend can describe. Said once
			// and not per frame; the engine's own picture is unaffected either way.
			notVulkan(slot.semantic);

			return false;
		}

		if (!port.isStateStated() && unreported.add(slot.semantic)) {
			// A native image the engine could not say the state of, which the descriptor then
			// publishes as undefined - an honest "nobody can say where this is" rather than a layout
			// nothing supports. Said once per semantic, because it is a state of the session and not
			// a thing that happens per frame.
			Vitrail.logger().info("The {} resource is published as undefined: this engine could not "
					+ "read the state of the image it is describing ({}).", slot.semantic.id(),
				REASON_LAYOUT);
		}

		final boolean first = slot.image == 0L;
		final boolean replaced = slot.image != 0L && slot.image != image;
		final int width = port.width();
		final int height = port.height();
		final boolean resized = slot.width != 0 && (slot.width != width || slot.height != height);

		slot.publish(descriptor(port), port);
		session.resource(slot.semantic, slot.key);

		if (resized) {
			// Unless the frame itself already says so. A resize is visible twice over: the exchange
			// sees the frame's own size at its boundary, and this adapter sees the resource's extent
			// change underneath it. Both are describing one transition, and asking for a second reset
			// would invalidate a history that is already empty - which a temporal consumer would report
			// as two resets for one resize. On a frame whose boundary carried no size, or carried the
			// wrong one, the exchange has said nothing and this is the only report of it.
			if (!snapshot.history().reset()
				|| snapshot.history().reason().orElse(null) != HistoryResetReason.RESOLUTION_CHANGE
				|| !snapshot.history().sinceFrame().equals(frameId)) {
				reset(HistoryResetReason.RESOLUTION_CHANGE, "the " + slot.semantic.id()
					+ " resource is now " + width + "x" + height + ", so a history accumulated at the old "
					+ "size is not a continuation of anything");
			}
		} else if (replaced) {
			reset(HistoryResetReason.RESOURCE_RECREATION, "the " + slot.semantic.id()
				+ " resource was recreated at " + width + "x" + height + " (new native handle 0x"
				+ Long.toHexString(image) + ")");
		}

		// The live format is named here rather than assumed from a constant: the only honest source for
		// "what format is this image?" is the image, and this line is the one place that says it.
		if (first) {
			Vitrail.logger().info("Published the {} resource: {}x{}, live format {}, {}, generation {}",
				slot.semantic.id(), width, height, liveFormat(port), state(port),
				slot.publication == null ? 0L : slot.publication.generation());
		} else if (replaced || resized) {
			Vitrail.logger().info("Replaced the exported {} resource: {}x{}, live format {}, {}, "
				+ "generation {}", slot.semantic.id(), width, height, liveFormat(port), state(port),
				slot.publication == null ? 0L : slot.publication.generation());
		}

		return true;
	}

	/**
	 * Whether this semantic is still this class's to claim in this frame.
	 * <p>
	 * A frame that already carries it belongs to whoever put it there: the exchange drops a semantic
	 * two providers claim, so adding a second contribution would take it away from every consumer
	 * instead of choosing between two descriptions of the same frame. Deferring is said once per
	 * semantic per session, in the log and not in the frame.
	 */
	private static boolean claim(final FrameSnapshot snapshot, final FrameSemantic semantic) {
		final String owner = snapshot.providerOf(semantic);

		if (owner.isEmpty() || owner.equals(PROVIDER_ID)) {
			return true;
		}

		if (stoodDown.add(semantic)) {
			Vitrail.logger().info("This frame already carries {} from another provider, so it is left "
				+ "to that provider and this engine publishes no second one", semantic.id());
		}

		return false;
	}

	/** Says once that this backend cannot describe the engine's textures. */
	private static void notVulkan(final FrameSemantic semantic) {
		if (stoodDown.add(semantic)) {
			Vitrail.logger().info("This engine is not rendering through Vulkan, so its {} cannot be "
				+ "described as a native resource and nothing is published for it", semantic.id());
		}
	}

	/**
	 * Declares where the image is, in both the API's neutral vocabulary and Vulkan's.
	 * <p>
	 * The two halves are built from one answer rather than filled in separately, so they cannot
	 * contradict each other: the neutral state comes from the engine's own answer for this image, and
	 * Vulkan's precise state is the canonical state for that neutral one, which is also what makes
	 * the stage and access masks the widest legal scope rather than a guess at this frame's last
	 * write.
	 * <p>
	 * A native image whose state the engine could not read is described as undefined, which is the
	 * honest answer to "where is this image" when nobody can say, and is what a consumer must treat
	 * as "the contents do not survive". That is not a case the engine reaches on Vulkan - the state
	 * is read from the same device that produced the handle - and it is kept because a descriptor
	 * that claims nothing is better than one that claims the wrong thing.
	 */
	private static ExternalImage descriptor(final EngineImage port) {
		final GpuFormat format = port.format();
		final TexelFormat texel = texelFormat(format);
		final ExternalImage.Builder descriptor = ExternalImage.builder(GraphicsApi.VULKAN, port.nativeImage())
			.view(port.nativeView())
			.format(texel);

		if (texel == TexelFormat.UNKNOWN) {
			// The neutral enum does not know this format, so the native code has to be stated: a
			// descriptor that says unknown without one leaves a consumer nothing to interpret.
			descriptor.nativeFormat(format.ordinal());
		}

		final boolean stated = port.isStateStated();
		final ResourceState state = stated ? resourceState(port.state()) : ResourceState.UNDEFINED;
		// The family is part of the state and not a separate assumption: an image's owner is the
		// family of the device that created it, which is the one this frame read. It is not the
		// constant nought - that is a property of this card's family layout, not of the engine - and
		// it says nothing about a consumer's queue, so one that submits elsewhere owns the transfer.
		final int family = stated ? port.state().queueFamily() : -1;

		return descriptor.extent(port.width(), port.height())
			.aspect(aspectOf(texel))
			.state(state)
			.queueFamily(stated ? QueueFamilyOwnership.shared(family) : QueueFamilyOwnership.ignored())
			.ownership(ResourceOwnership.BORROWED_PERSISTENT)
			.lifetime(ResourceLifetime.UNTIL_INVALIDATED)
			.ready(new SyncPrimitive.AlreadyOrdered(REASON_ORDERED))
			.release(new SyncPrimitive.Undeclared(REASON_RELEASE))
			.nativeState(stated ? VulkanImageState.of(state, family, true) : VulkanImageState.unknown())
			.build();
	}

	/**
	 * The API's word for the state the engine reported, which is the one place the engine's answer
	 * becomes the API's vocabulary - {@code texelFormat} and {@code aspectOf} translate the same way.
	 * <p>
	 * Only the general layout is translated, because it is the only layout this engine puts an image
	 * in. Anything else is not a state this class knows how to describe, and
	 * {@link ResourceState#UNDEFINED} is the answer that says so rather than one that guesses.
	 */
	private static ResourceState resourceState(final EngineImageState imageState) {
		return imageState.layout() == EngineImageState.GENERAL
			? ResourceState.GENERAL
			: ResourceState.UNDEFINED;
	}

	/** How this frame's depth must be read, which is the pack's copy and not the device image. */
	private static DepthConvention depthConvention(final FrameResources frame) {
		final DepthConvention convention = DepthConvention.declare(DepthConvention.Direction.NORMAL,
			DepthConvention.Range.ZERO_TO_ONE, DepthConvention.Linearity.NON_LINEAR, REASON_DEPTH_COPY);

		return planesKnown(frame)
			? convention.withPlanes(frame.nearPlane(), frame.farPlane())
			: convention;
	}

	/**
	 * Whether the frame's near and far planes are genuinely stated.
	 * <p>
	 * A frame that has not set them reports a far plane of zero, and the temporal contract refuses a
	 * near plane that is not closer than the far one - correctly, because a consumer would otherwise
	 * reconstruct a position from a distance nobody stated. There is a frame like that on the title
	 * screen, which is why this is asked rather than assumed: a frame without planes says so.
	 */
	private static boolean planesKnown(final FrameResources frame) {
		final double near = frame.nearPlane();
		final double far = frame.farPlane();

		return Double.isFinite(near) && Double.isFinite(far) && near > 0.0 && far > near;
	}

	/** How this frame's motion vectors must be read: the canonical convention, unchanged. */
	private static MotionVectorConvention motionVectorConvention() {
		return MotionVectorConvention.of(MotionVectorConvention.Direction.CURRENT_TO_PREVIOUS,
			MotionVectorConvention.Units.RENDER_PIXELS, MotionVectorConvention.Origin.TOP_LEFT, false, 1.0F,
			REASON_MOTION_VECTORS);
	}

	/**
	 * This frame's camera state, in the frame's own terms.
	 * <p>
	 * The pair handed over is the one the frame was rendered with and not the one a pack reads: the
	 * former is what reprojects a depth sample and the latter has been converted to another volume,
	 * and mixing the two reconstructs a position that is wrong by more the further away it is. The
	 * positions are the engine's published pair, shifted together so that a difference between them
	 * survives the shift exactly.
	 */
	private static TemporalFrameInfo temporal(final FrameResources frame, final FrameId frameId) {
		final TemporalFrameInfo.Builder info = TemporalFrameInfo.builder(frameId)
			.viewRotation(frame.view())
			.previousViewRotation(frame.previousView())
			.projection(frame.projection())
			.previousProjection(frame.previousProjection())
			.cameraPosition(position(frame.cameraPosition()))
			.previousCameraPosition(position(frame.previousCameraPosition()));

		if (planesKnown(frame)) {
			info.planes(frame.nearPlane(), frame.farPlane());
		} else {
			sayPlanesUnknown();
		}

		info
			// The engine applies no jitter of its own: seven of the eight packs of the corpus
			// already offset their own composite, and a jitter added here would be applied twice.
			.jitter(TemporalFrameInfo.Jitter.none(
				"this engine applies no jitter; a pack that offsets its own composite is not reported here"))
			.cameraMotionIncluded(true)
			.source("the engine's frame at the interface's depth clear, after the render scale's "
					+ "upscale has been drawn onto the window-sized colour");

		return info.build();
	}

	private static TemporalFrameInfo.Position position(final Vector3dc at) {
		return TemporalFrameInfo.Position.of(at.x(), at.y(), at.z());
	}

	/** Says once that this frame states no planes, which is the truth rather than a zero. */
	private static void sayPlanesUnknown() {
		if (planesAbsentSaid) {
			return;
		}

		planesAbsentSaid = true;
		Vitrail.logger().info("This frame states no near and far planes, so none are published for it "
			+ "rather than a zero a consumer would reconstruct a position from. Frames drawn without a "
			+ "world camera are the ones that have none.");
	}

	/** Asks for a temporal reset, in the exchange's terms, with this engine's reason. */
	private static void reset(final HistoryResetReason reason, final String detail) {
		if (session != null) {
			session.requestHistoryReset(reason, detail);
		}
	}

	/** Says once when the demand for motion vectors changes, because it changes what the engine does. */
	private static void sayDemand(final boolean wanted) {
		final FrameDemand demand = FrameExchange.demand();

		if (demand.version() == lastDemandVersion) {
			return;
		}

		lastDemandVersion = demand.version();
		motionVectorsWanted = wanted;
		Vitrail.logger().info("{}", demand.describe());

		if (wanted) {
			Vitrail.logger().info("A consumer is asking for this frame's motion vectors, so the engine "
				+ "draws them again: {}", REASON_MOTION_VECTORS);
		}
	}

	/** Says when the pass starts and stops drawing, which is the observable half of the demand. */
	private static void sayDrawn(final boolean drawn) {
		if (drawn == motionVectorsDrawn) {
			return;
		}

		motionVectorsDrawn = drawn;
		Vitrail.logger().info(drawn
			? "The motion vector pass is drawing this engine's vectors for a consumer"
			: "The motion vector pass has stopped drawing: nothing is reading this frame's vectors");
	}

	/**
	 * The neutral format for an engine format, by name: the two enums spell the same formats the
	 * same way, and a name the neutral enum does not know is reported as unknown, which forces the
	 * native code to be published rather than a format to be guessed.
	 */
	private static TexelFormat texelFormat(final GpuFormat format) {
		for (final TexelFormat candidate : TexelFormat.values()) {
			if (candidate.name().equals(format.name())) {
				return candidate;
			}
		}

		return TexelFormat.UNKNOWN;
	}

	/** The live format of an image, named the way the descriptor names it. */
	private static String liveFormat(final EngineImage port) {
		final GpuFormat format = port.format();
		final TexelFormat texel = texelFormat(format);

		return texel == TexelFormat.UNKNOWN
			? format.name() + " (native ordinal " + format.ordinal() + ")"
			: texel.name();
	}

	/** The published state of this image, as the log line that announces it names it. */
	private static String state(final EngineImage port) {
		return port.state() == null ? "state unstated" : port.state().describe();
	}

	/** The aspect a format belongs to, so a consumer does not take a colour view of depth. */
	private static ImageAspect aspectOf(final TexelFormat format) {
		return switch (format) {
			case D32_FLOAT, D16_UNORM -> ImageAspect.DEPTH;
			case D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT -> ImageAspect.DEPTH_STENCIL;
			case S8_UINT -> ImageAspect.STENCIL;
			default -> ImageAspect.COLOR;
		};
	}

	/** One semantic's publication and what was last published under it. */
	private static final class Slot {

		private final ResourceKey key;
		private final FrameSemantic semantic;
		private ResourcePublication publication;
		private long image;
		private long view;
		private int width;
		private int height;
		private GpuFormat format;
		private EngineImageState state;

		private Slot(final ResourceKey key, final FrameSemantic semantic) {
			this.key = key;
			this.semantic = semantic;
		}

		/**
		 * Publishes the first descriptor, or replaces the one standing when the resource really changed.
		 * <p>
		 * <strong>Nothing is replaced on a frame the resource did not change on, and that is not an
		 * optimisation.</strong> A replacement is a statement that the resource under the key is a
		 * different resource: the exchange bumps the publication's generation, invalidates every handle a
		 * consumer acquired from the previous one, and records a producer error for replacing a resource
		 * it had borrowed out. Doing that once a frame would mean a consumer's handle is dead by the next
		 * frame - which is the opposite of what a {\code BORROWED_PERSISTENT} descriptor with an
		 * {\code UNTIL_INVALIDATED} lifetime promises - and would fill the diagnostics with a fault this
		 * engine caused by merely drawing. The five things compared here are the whole of what can
		 * change about an image the engine keeps in place: which image it is, its view, its extent, its
		 * format and where it is - the last of those because a state that moved without the handle
		 * moving would otherwise leave a consumer describing an image that is no longer there, and a
		 * replacement is the only way to say so.
		 */
		private void publish(final ExternalImage descriptor, final EngineImage port) {
			final boolean changed = this.image != port.nativeImage()
				|| this.view != port.nativeView()
				|| this.width != port.width()
				|| this.height != port.height()
				|| this.format != port.format()
				|| !java.util.Objects.equals(this.state, port.state());

			if (this.publication != null && this.publication.isOpen()) {
				if (changed) {
					this.publication.replace(descriptor);
				}
			} else {
				this.publication = ExternalResourceExchange.publish(this.key, PROVIDER_ID, descriptor);
			}

			this.image = port.nativeImage();
			this.view = port.nativeView();
			this.width = port.width();
			this.height = port.height();
			this.format = port.format();
			this.state = port.state();
		}

		/** Ends the publication, which invalidates every consumer handle onto it. */
		private boolean close() {
			if (this.publication == null) {
				return false;
			}

			if (this.publication.isOpen()) {
				this.publication.close();
			}

			this.publication = null;
			this.image = 0L;
			this.view = 0L;
			this.width = 0;
			this.height = 0;
			this.format = null;
			this.state = null;

			return true;
		}
	}

	private static final String REASON_DEPTH_COPY =
		"the pack's converted depth copy: forward over 0..1, non-linear, at the render size, converted "
			+ "from the device's reversed volume by the same constants this engine's own vector pass "
			+ "converts back with";
	private static final String REASON_MOTION_VECTORS =
		"this engine's existing motion vector pass: current pixel to previous-frame pixel, in render "
			+ "resolution pixels, top-left origin, RG16F, no jitter applied, camera motion included and "
			+ "object motion not: moving entities, translucents, particles and the hand are outside what "
			+ "this pass reprojects";
}
