package dev.vitrail.frame;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

/**
 * One image of this engine's, reduced to the six things a description of it needs.
 * <p>
 * <strong>This is the whole of what the export path knows about the engine's images, and that is
 * the point of it.</strong> Everything downstream of the seam - claiming a semantic, publishing a
 * descriptor, replacing it when the native handle changes, asking for a history reset with the
 * right reason - is a decision about numbers this record carries. Testing those decisions used to
 * need a device, because the decision code named {@code GpuTexture} and a fake cannot be a
 * {@code VulkanGpuTexture}: it is a real class over a real device, and the one thing that tells a
 * Vulkan texture from any other is whether it is one. Naming this instead means the rules are
 * exercised with the port built by hand and no window open, and the one place that has to know
 * about Vulkan is {@link #of}.
 * <p>
 * <strong>The conversion happens once, where the live objects are.</strong> {@link #of} is called at
 * the seam, on the render thread, with the texture and view the frame actually drew into, and the
 * result is what travels. Nothing downstream holds a {@code GpuTextureView}, which also answers the
 * hazard the views carry: a view kept across a resize is a use after free rather than an exception,
 * and a handle kept across one is a number that is merely stale and can be compared, refused and
 * reported.
 * <p>
 * <strong>A zero handle is not an absent image.</strong> {@code nativeImage} is zero for a texture
 * that is not a Vulkan one, which is the OpenGL backend and is reported once as such; it is also
 * zero in no other case, because {@link #of} answers null for a null texture rather than a record of
 * noughts. A null port is the absent resource, exactly as a null texture was.
 * <p>
 * <strong>The format is the live one.</strong> It is read from the texture at the moment of the
 * conversion and never from a constant: the engine's main target is one format under the game's own
 * renderer and another under a component that replaces it, so the only honest source for "what
 * format is this image" is the image.
 * <p>
 * <strong>The state is where the image is, and it travels beside the handle rather than being
 * looked up later.</strong> A consumer that records a barrier has to name the layout the image is
 * already in, and by the time a descriptor reaches it the engine's live objects are out of reach.
 * So the state is read at the same moment as the handle, from the same device, and carried in the
 * record: {@link EngineImageState} says which two facts those are and why one answer covers every
 * image this engine owns. It is a value like the extent and not a reference to anything, so a
 * consumer cannot hold a stale one open and a test can build it by hand.
 *
 * @param nativeImage the native image handle, zero when this is not a Vulkan image
 * @param nativeView  the native view handle, zero when there is none or it is not a Vulkan view
 * @param format      the live format of the image
 * @param width       its width in texels
 * @param height      its height in texels
 * @param state       where the image is, or null when this image is not this backend's
 */
public record EngineImage(
		long nativeImage,
		long nativeView,
		GpuFormat format,
		int width,
		int height,
		EngineImageState state
) {

	/**
	 * The port for a live texture and its view, or null for a null texture.
	 *
	 * @param texture the engine's texture, or null when the frame has no such resource
	 * @param view    the view onto it, or null when there is none
	 * @param state   the state read from the device this frame is drawing on, or null when there is
	 *                no such device - which is the OpenGL backend, whose textures answer a zero
	 *                handle and are not described as native resources either
	 */
	public static EngineImage of(final GpuTexture texture, final GpuTextureView view,
			final EngineImageState state) {
		if (texture == null) {
			return null;
		}

		return new EngineImage(
			texture instanceof VulkanGpuTexture vulkan ? vulkan.vkImage() : 0L,
			view instanceof VulkanGpuTextureView vulkan ? vulkan.vkImageView() : 0L,
			texture.getFormat(),
			texture.getWidth(0),
			texture.getHeight(0),
			state);
	}

	/** Whether this is an image a Vulkan consumer can be handed at all. */
	public boolean isNative() {
		return this.nativeImage != 0L;
	}

	/**
	 * Whether this image's state is known, which is what a consumer needs before it can record a
	 * barrier: the handle tells it what to transition and this tells it from what.
	 */
	public boolean isStateStated() {
		return this.state != null && this.state.isStated();
	}
}
