package dev.vitrail.b3d;

import dev.vitrail.Vitrail;
import dev.vitrail.frame.FrameExport;
import dev.vitrail.frame.FrameResources;

import b3dinterop.api.backend.GraphicsApi;
import b3dinterop.api.frame.FrameColorInfo;
import b3dinterop.api.frame.FrameDemand;
import b3dinterop.api.frame.FrameDemandRegistration;
import b3dinterop.api.frame.FrameExchange;
import b3dinterop.api.frame.FrameId;
import b3dinterop.api.frame.FrameRequirements;
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
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
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
 * <strong>What is published and what is not.</strong> Scene colour is the frame the player sees
 * without the interface, at the window's size, after the chain and after the render scale's upscale.
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

	/** The mod id this integration is for. Read before anything of it is loaded. */
	public static final String MOD_ID = "b3dinterop";

	/**
	 * A demand this mod registers for itself, for the runtime validation: {@code <semantic>} or
	 * {@code <semantic>:<frames>}, where the second form withdraws the demand after that many
	 * exported frames. Absent by default, so a normal session registers nothing and consumes
	 * nothing.
	 */
	public static final String DEMAND_PROPERTY = "vitrail.b3d.demand";

	/** Quieter than every frame: how often the self-test demand repeats its answer. */
	private static final long SELF_TEST_PERIOD = 600L;

	private static final String PROVIDER_ID = "vitrail";
	private static final String NAMESPACE = "vitrail";

	private static final String REASON_ORDERED =
		"the engine's own work for this frame is recorded into the frame's command buffer and "
			+ "submitted at its end; a consumer that opens its work after the frame boundary, on the "
			+ "same queue family, is ordered behind it";
	private static final String REASON_RELEASE =
		"this engine cannot name a consumer's completion primitive; the consumer states its own";
	private static final String REASON_LAYOUT =
		"this engine tracks no image layouts - every texture it owns lives in the general layout for "
			+ "its whole life, so the descriptor claims nothing and a consumer transitions what it uses";
	private static final String REASON_COLOUR =
		"neither this engine nor the game knows the primaries or the transfer function of the frame "
			+ "it rasterises into; the picture is past whatever tone mapping the pack does and no paper "
			+ "white is stated anywhere, so the encoding is reported as unknown rather than invented";
	private static final String REASON_ROOT =
		"no consumer asked about this frame's colour, and a format is not an encoding";

	private static final Slot SCENE_COLOUR =
		new Slot(ResourceKey.of(NAMESPACE, "scene_colour"), FrameSemantic.SCENE_COLOR);
	private static final Slot DEPTH = new Slot(ResourceKey.of(NAMESPACE, "depth"), FrameSemantic.DEPTH);
	private static final Slot MOTION_VECTORS =
		new Slot(ResourceKey.of(NAMESPACE, "motion_vectors"), FrameSemantic.MOTION_VECTORS);

	private static FrameSession session;
	private static FrameDemandRegistration selfTest;
	private static long selfTestWithdrawAt;
	private static long exported;
	private static long failures;
	private static boolean motionVectorsDrawn;
	private static boolean motionVectorsWanted;
	private static long lastDemandVersion = -1L;

	/**
	 * Semantics this export has stood down from, once each: one another provider already put in a
	 * frame, or one this backend cannot describe. Kept so that the reason is said once rather than
	 * once a frame.
	 */
	private static final Set<FrameSemantic> stoodDown = new LinkedHashSet<>();

	private B3DFrameExport() {
	}

	/** Whether the export is attached; false before {@link #attach} and after {@link #detach}. */
	public static synchronized boolean isAttached() {
		return session != null;
	}

	/**
	 * Attaches the export: a frame session, the sink the engine calls, and the self-test demand the
	 * property asks for.
	 * <p>
	 * Idempotent, because both loaders reach their client setup exactly once but a caller cannot
	 * tell that from here, and a second attach would leave a session nobody closes.
	 */
	public static synchronized void attach() {
		if (session != null) {
			return;
		}

		session = FrameExchange.attach(PROVIDER_ID);
		session.advertises(Set.of(FrameSemantic.SCENE_COLOR, FrameSemantic.DEPTH,
			FrameSemantic.MOTION_VECTORS));
		FrameExport.install(B3DFrameExport::onFrame);
		registerSelfTest();

		Vitrail.logger().info("Publishing this engine's frames to {} as {}: scene colour, depth, and "
			+ "motion vectors when a consumer asks for them", MOD_ID, PROVIDER_ID);
	}

	/** Detaches the export, closing everything it published. Idempotent. */
	public static synchronized void detach() {
		closeSelfTest();

		if (session == null) {
			FrameExport.uninstall();

			return;
		}

		FrameExport.uninstall();
		SCENE_COLOUR.close();
		DEPTH.close();
		MOTION_VECTORS.close();
		session.close();
		session = null;
		motionVectorsDrawn = false;
		motionVectorsWanted = false;
		lastDemandVersion = -1L;
		stoodDown.clear();

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

	private static void contribute(final FrameResources frame) {
		final FrameDemand demand = FrameExchange.demand();

		// The engine reads this where the motion vector pass would be drawn, which is before this
		// seam runs, so what is stated here arms the next frame - a frame no consumer of this one
		// could have asked about, having registered during it.
		sayDemand(demand.wanted(FrameSemantic.MOTION_VECTORS));
		FrameExport.demandMotionVectors(motionVectorsWanted);

		final Optional<FrameSnapshot> open = FrameExchange.currentSnapshot();

		if (open.isEmpty()) {
			return;
		}

		final FrameSnapshot snapshot = open.get();
		final FrameId frameId = snapshot.frameId();

		exported = frame.index();
		withdrawSelfTestIfDue();

		final boolean colourClaimed =
			contributeImage(snapshot, frameId, SCENE_COLOUR, frame.sceneColour(), frame.sceneColourView());
		boolean depthClaimed = false;

		if (frame.hasDepth()) {
			depthClaimed = contributeImage(snapshot, frameId, DEPTH, frame.depth().texture(), frame.depth());
		}

		boolean vectorsClaimed = false;

		if (frame.hasMotionVectors()) {
			vectorsClaimed = contributeImage(snapshot, frameId, MOTION_VECTORS, frame.motionVectorImage(),
				frame.motionVectors());
		} else if (!frame.hasMotionVectorImage()) {
			// The pass gave its image back, which it does on every frame nothing reads it. The
			// publication ends rather than standing over an image that no longer exists: this is the
			// one case where a consumer holding a descriptor has to be told, and the alternative is a
			// handle into freed memory.
			MOTION_VECTORS.close();
		}

		final boolean contributed = colourClaimed || depthClaimed || vectorsClaimed;

		if (!contributed) {
			// Nothing of this frame is this engine's to describe - no pack is drawing it, or the
			// backend is not Vulkan - so its camera state is not published either. A frame with
			// metadata and no resources would be a description of somebody else's frame.
			reportSelfTest(frame, snapshot, false, false);

			return;
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

		reportSelfTest(frame, snapshot, colourClaimed, depthClaimed);
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
		final GpuTexture texture,
		final GpuTextureView view
	) {
		if (!claim(snapshot, slot.semantic)) {
			return false;
		}

		final long image = imageHandle(texture);
		final long imageView = viewHandle(view);

		if (image == 0L) {
			// Not a Vulkan texture, which is the whole of what this backend can describe. Said once
			// and not per frame; the engine's own picture is unaffected either way.
			notVulkan(slot.semantic);

			return false;
		}

		final boolean first = slot.image == 0L;
		final boolean replaced = slot.image != 0L && slot.image != image;
		final int width = texture.getWidth(0);
		final int height = texture.getHeight(0);
		final boolean resized = slot.width != 0 && (slot.width != width || slot.height != height);

		slot.publish(descriptor(texture, image, imageView), image, imageView, width, height);
		session.resource(slot.semantic, slot.key);

		if (resized) {
			reset(HistoryResetReason.RESOLUTION_CHANGE, "the " + slot.semantic.id()
				+ " resource is now " + width + "x" + height + ", so a history accumulated at the old size "
				+ "is not a continuation of anything");
		} else if (replaced) {
			reset(HistoryResetReason.RESOURCE_RECREATION, "the " + slot.semantic.id()
				+ " resource was recreated at " + width + "x" + height + " (new native handle 0x"
				+ Long.toHexString(image) + ")");
		}

		// The live format is named here rather than assumed from a constant: the only honest source for
		// "what format is this image?" is the image, and this line is the one place that says it.
		if (first) {
			Vitrail.logger().info("Published the {} resource: {}x{}, live format {}, native handle "
				+ "0x{}, view 0x{}, publication generation {}", slot.semantic.id(), width, height,
				liveFormat(texture), Long.toHexString(image), Long.toHexString(imageView),
				slot.publication == null ? 0L : slot.publication.generation());
		} else if (replaced || resized) {
			Vitrail.logger().info("Replaced the exported {} resource: {}x{}, live format {}, native "
				+ "handle 0x{}, publication generation {}", slot.semantic.id(), width, height,
				liveFormat(texture), Long.toHexString(image),
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

	/** Declares the resource state no consumer may assume, with the reason it is not stated. */
	private static ExternalImage descriptor(
		final GpuTexture texture,
		final long image,
		final long imageView
	) {
		final GpuFormat format = texture.getFormat();
		final TexelFormat texel = texelFormat(format);
		final ExternalImage.Builder descriptor = ExternalImage.builder(GraphicsApi.VULKAN, image)
			.view(imageView)
			.format(texel);

		if (texel == TexelFormat.UNKNOWN) {
			// The neutral enum does not know this format, so the native code has to be stated: a
			// descriptor that says unknown without one leaves a consumer nothing to interpret.
			descriptor.nativeFormat(format.ordinal());
		}

		return descriptor.extent(texture.getWidth(0), texture.getHeight(0))
			.aspect(aspectOf(texel))
			.state(ResourceState.UNDEFINED)
			// Every texture this engine owns is used by the queue family the frame is drawn on, and
			// it is the family the game's own device was created with. It says nothing about a
			// consumer's queue: one that submits elsewhere owns the ownership transfer itself.
			.queueFamily(QueueFamilyOwnership.shared(0))
			.ownership(ResourceOwnership.BORROWED_PERSISTENT)
			.lifetime(ResourceLifetime.UNTIL_INVALIDATED)
			.ready(new SyncPrimitive.AlreadyOrdered(REASON_ORDERED))
			.release(new SyncPrimitive.Undeclared(REASON_RELEASE))
			.nativeState(VulkanImageState.unknown())
			.build();
	}

	/** How this frame's depth must be read, which is the pack's copy and not the device image. */
	private static DepthConvention depthConvention(final FrameResources frame) {
		return DepthConvention.declare(DepthConvention.Direction.NORMAL, DepthConvention.Range.ZERO_TO_ONE,
				DepthConvention.Linearity.NON_LINEAR, REASON_DEPTH_COPY)
			.withPlanes(frame.nearPlane(), frame.farPlane());
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
			.previousCameraPosition(position(frame.previousCameraPosition()))
			.planes(frame.nearPlane(), frame.farPlane())
			// The engine applies no jitter of its own: seven of the eight packs of the corpus
			// already offset their own composite, and a jitter added here would be applied twice.
			.jitter(TemporalFrameInfo.Jitter.none(
				"this engine applies no jitter; a pack that offsets its own composite is not reported here"))
			.cameraMotionIncluded(true)
			.source("the engine's frame at the interface's depth clear, after the render scale's upscale");

		return info.build();
	}

	private static TemporalFrameInfo.Position position(final Vector3dc at) {
		return TemporalFrameInfo.Position.of(at.x(), at.y(), at.z());
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

	/** Registers the demand the self-test property asks for, if it asks for one. */
	private static void registerSelfTest() {
		final String asked = System.getProperty(DEMAND_PROPERTY, "").trim();

		if (asked.isEmpty()) {
			return;
		}

		final int separator = asked.indexOf(':');
		final String name = separator < 0 ? asked : asked.substring(0, separator);
		final Set<FrameSemantic> required = new LinkedHashSet<>();

		if (name.equals("motion_vectors")) {
			required.add(FrameSemantic.MOTION_VECTORS);
		} else if (name.equals("colour_and_depth") || name.equals("colour-and-depth")) {
			required.add(FrameSemantic.SCENE_COLOR);
			required.add(FrameSemantic.DEPTH);
		} else {
			Vitrail.logger().warn("{} names a semantic this test does not know: '{}'", DEMAND_PROPERTY, name);

			return;
		}

		selfTest = FrameExchange.registerDemand("vitrail:selftest", FrameRequirements.requiring(
			required.toArray(new FrameSemantic[0])));

		if (separator >= 0) {
			try {
				selfTestWithdrawAt = exported + Long.parseLong(asked.substring(separator + 1));
			} catch (NumberFormatException e) {
				Vitrail.logger().warn("{} names a frame count that is not a number: '{}'", DEMAND_PROPERTY,
					asked);

				return;
			}
		}

		Vitrail.logger().info("Registered a test demand for {}; it withdraws after {} exported frame(s)",
			required, selfTestWithdrawAt == 0L ? "every" : Long.toString(selfTestWithdrawAt));
	}

	/** Withdraws the test demand once the frames it asked for have been exported. */
	private static void withdrawSelfTestIfDue() {
		if (selfTest != null && selfTestWithdrawAt != 0L && exported >= selfTestWithdrawAt) {
			Vitrail.logger().info("Withdrawing the test demand after {} exported frames; the engine "
				+ "should stop drawing motion vectors", exported);
			closeSelfTest();
		}
	}

	private static void closeSelfTest() {
		if (selfTest != null) {
			selfTest.close();
			selfTest = null;
		}
	}

	/** The test demand's own view of the frame, bounded so normal play is never a log a frame. */
	private static void reportSelfTest(
		final FrameResources frame,
		final FrameSnapshot snapshot,
		final boolean colour,
		final boolean depth
	) {
		sayDrawn(frame.hasMotionVectors());

		if (selfTest == null || (frame.index() > 5L && frame.index() % SELF_TEST_PERIOD != 0L)) {
			return;
		}

		Vitrail.logger().info("Test demand on exported frame {}: {}; this engine contributed colour {}, "
			+ "depth {}, vectors {}; history {}", frame.index(),
			selfTest.requirements().check(snapshot.semantics()).describe(), colour, depth,
			frame.hasMotionVectors(), snapshot.history().describe());
	}

	/** The Vulkan image behind an engine texture, or {@code 0} when this is not a Vulkan texture. */
	private static long imageHandle(final GpuTexture texture) {
		return texture instanceof VulkanGpuTexture vulkan ? vulkan.vkImage() : 0L;
	}

	/** The Vulkan view handle, or {@code 0} when there is none or it is not a Vulkan view. */
	private static long viewHandle(final GpuTextureView view) {
		return view instanceof VulkanGpuTextureView vulkan ? vulkan.vkImageView() : 0L;
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

	/** The live format of a texture, named the way the descriptor names it. */
	private static String liveFormat(final GpuTexture texture) {
		final GpuFormat format = texture.getFormat();
		final TexelFormat texel = texelFormat(format);

		return texel == TexelFormat.UNKNOWN
			? format.name() + " (native ordinal " + format.ordinal() + ")"
			: texel.name();
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

		private Slot(final ResourceKey key, final FrameSemantic semantic) {
			this.key = key;
			this.semantic = semantic;
		}

		/** Publishes the first descriptor or replaces the one standing, and records the handles. */
		private void publish(
			final ExternalImage descriptor,
			final long newImage,
			final long newView,
			final int newWidth,
			final int newHeight
		) {
			if (this.publication != null && this.publication.isOpen()) {
				this.publication.replace(descriptor);
			} else {
				this.publication = ExternalResourceExchange.publish(this.key, PROVIDER_ID, descriptor);
			}

			this.image = newImage;
			this.view = newView;
			this.width = newWidth;
			this.height = newHeight;
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
