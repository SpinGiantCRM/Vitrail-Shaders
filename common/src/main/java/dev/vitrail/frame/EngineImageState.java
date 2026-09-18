package dev.vitrail.frame;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import org.lwjgl.vulkan.VK10;

/**
 * Where one of this engine's images is, in the two facts a consumer cannot work out for itself.
 * <p>
 * <strong>This is not a guess and it is not a tracker.</strong> The engine does not keep a layout
 * per image, and this class does not pretend otherwise: what it carries is the layout the engine
 * puts <em>every</em> image it owns into and never takes it out of, plus the queue family that owns
 * it. Both are read from the engine rather than assumed, and both are the engine's own answer for
 * the image being described.
 * <p>
 * <strong>Why the layout is one answer for every image.</strong> It is the shape of this backend,
 * and it is checked rather than remembered:
 * <ul>
 *   <li>a texture is created with {@code initialLayout} of {@code VK_IMAGE_LAYOUT_UNDEFINED} and
 *       immediately transitioned to {@code VK_IMAGE_LAYOUT_GENERAL}, once, in its constructor - the
 *       transition is recorded on the frame's own command buffer, and {@code GpuFormat} decides only
 *       the aspect of the subresource range;</li>
 *   <li>dynamic rendering names {@code VK_IMAGE_LAYOUT_GENERAL} for every colour and depth
 *       attachment, so drawing into an image is a use in the layout it is already in;</li>
 *   <li>every sampled and storage descriptor names {@code VK_IMAGE_LAYOUT_GENERAL} too;</li>
 *   <li>and nothing else in the whole game transitions an image: the only two classes that name a
 *       {@code VkImageMemoryBarrier} are the texture constructor above and the swapchain surface,
 *       which is not one of these images.</li>
 * </ul>
 * So an image of this engine is in {@link #GENERAL} when it is handed out, whatever the frame did
 * with it, and the alternative - claiming nothing - is the one answer that is certainly not true.
 * <p>
 * <strong>What is exact and what is conservative.</strong> The layout is exact: it follows from the
 * engine's construction. The stage and access masks a consumer derives from it are the widest legal
 * scope for that layout rather than the precise last write of this particular frame, because the
 * engine does not track that and will not invent it. Widest is the safe direction for a barrier's
 * source scope - it can only over-synchronise, never under-synchronise - and it is the same scope
 * this engine's own transitions use.
 * <p>
 * <strong>An unstated state is a real answer.</strong> {@link #unstated} is what a caller who cannot
 * read the engine's device has: it says nothing about the image, and a consumer of it must treat the
 * resource as one whose contents do not survive - which is what the external frame API does with an
 * undefined state. A null state is a different thing again: an image that is not this backend's
 * Vulkan image at all, which the descriptor path already refuses to describe.
 *
 * @param layout      the {@code VK_IMAGE_LAYOUT_*} the engine keeps this image in
 * @param queueFamily the family that owns it, or {@link #UNKNOWN_FAMILY} when that cannot be read
 */
public record EngineImageState(int layout, int queueFamily) {

	/**
	 * {@code VK_IMAGE_LAYOUT_GENERAL}, which is where this engine keeps every image it owns.
	 * <p>
	 * Named through the constant rather than written as a number: the value is Vulkan's, and a
	 * reader deserves to see which one it is.
	 */
	public static final int GENERAL = VK10.VK_IMAGE_LAYOUT_GENERAL;

	/**
	 * {@code VK_IMAGE_LAYOUT_UNDEFINED}: what an image nobody can say the state of is published as.
	 * <p>
	 * A real Vulkan layout and not a stand-in word, which is why it is named here rather than
	 * spelled as an absence: as a barrier's old layout it permits the contents to be discarded, so a
	 * consumer that is told this must treat the frame as one it cannot read rather than one it may.
	 */
	public static final int UNSTATED_LAYOUT = VK10.VK_IMAGE_LAYOUT_UNDEFINED;

	/** The family an image has when nobody could read one, matching Vulkan's own "unknown". */
	public static final int UNKNOWN_FAMILY = -1;

	public EngineImageState {
		if (layout < 0) {
			throw new IllegalArgumentException("a layout is a Vulkan constant, not " + layout);
		}

		if (queueFamily < UNKNOWN_FAMILY) {
			throw new IllegalArgumentException("a queue family is an index or " + UNKNOWN_FAMILY
					+ ", not " + queueFamily);
		}
	}

	/** The engine's own state for an image it owns: the general layout, in the family that made it. */
	public static EngineImageState general(final int queueFamily) {
		return new EngineImageState(GENERAL, queueFamily);
	}

	/** A state that says nothing: no layout, no family. */
	public static EngineImageState unstated() {
		return new EngineImageState(UNSTATED_LAYOUT, UNKNOWN_FAMILY);
	}

	/**
	 * Whether this says anything a consumer may act on.
	 * <p>
	 * Both halves are needed and neither is optional. A layout with no family leaves a consumer
	 * unable to tell whether it must take ownership of the image before using it, and a family with
	 * no layout leaves it unable to record the barrier that makes its own use legal.
	 */
	public boolean isStated() {
		return this.layout != UNSTATED_LAYOUT && this.queueFamily != UNKNOWN_FAMILY;
	}

	/**
	 * The state of the images of the device the game is drawing on, or null when that device is not
	 * one whose images can be described this way.
	 * <p>
	 * <strong>Read from the live device, once per frame.</strong> The family is the engine's own
	 * graphics family, which is the one its queue submits on and therefore the one an image it
	 * created lives in; it is asked for rather than assumed to be zero, because zero is a property
	 * of this card's family layout and not of the engine. A backend with no such concept - the
	 * OpenGL one - answers null, which is the honest answer for its textures: their native handle is
	 * zero as well, and nothing downstream describes them.
	 *
	 * @param device the front device, or null before one exists
	 */
	public static EngineImageState of(final GpuDevice device) {
		if (device == null
				|| !(((GpuDeviceAccessor) device).vitrail$backend() instanceof VulkanDevice vulkan)) {
			return null;
		}

		// The queue the engine's own frames are submitted on. Null only before the device is
		// finished being created, which cannot be true here: this is read inside a frame.
		if (vulkan.graphicsQueue() == null) {
			return null;
		}

		return general(vulkan.graphicsQueue().queueFamilyIndex());
	}

	public String describe() {
		final String family = this.queueFamily == UNKNOWN_FAMILY ? "unknown" : Integer.toString(this.queueFamily);
		final String layout = this.layout == GENERAL ? "general" : "layout#" + this.layout;

		return "layout=" + layout + ", family=" + family;
	}
}
