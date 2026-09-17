package dev.vitrail.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * One offer of one frame to an {@link ExternalUpscaleProvider}.
 * <p>
 * <strong>The same object every frame, and valid only inside the call that hands it over.</strong>
 * A provider is offered this once per frame the render scale engages, and a session runs hundreds of
 * thousands of those, so the object is written over and handed back rather than allocated. That makes
 * keeping it worthless rather than dangerous: every getter refuses once the call has returned, so a
 * provider that stores it and reads it next frame is told so instead of quietly upscaling the wrong
 * frame's picture with the wrong frame's images.
 * <p>
 * <strong>Every image here is borrowed.</strong> Nothing on this object may be kept or destroyed, and
 * nothing on it can be: the scene is the render scale's stand-in and the destination is the game's own
 * window-sized colour, both of which this engine recreates on a resize, a scale change or a pack reload
 * and destroys on its own paths. A provider that keeps a view across any of those has a use after free
 * rather than an exception, which is the whole reason the frame is invalidated rather than merely
 * documented as temporary.
 * <p>
 * <strong>The scene is at the render size and the destination at the window's.</strong> They are equal
 * only when the scale is at a hundred percent, which is a frame this seam is never offered on: the
 * offer happens inside {@code RenderScale.endWorld}, which returns at once when nothing was scaled.
 * <p>
 * <strong>Where the temporal state is not.</strong> The matrices, the jitter and the camera positions a
 * temporal consumer needs are deliberately absent. This engine owns them, and publishing them here as
 * well would give one consumer two sources for one fact - the frame export already carries them, for
 * the frame this one is, and a provider that is also a consumer of that export reads them there under
 * one version rather than two. Should a provider turn up that performs the upscale and is not a
 * consumer of the export, adding them here is a small extension to this object and not a redesign.
 */
public final class ExternalUpscaleFrame {

	private boolean valid;
	private CommandEncoder encoder;
	private GpuDevice device;
	private GpuTextureView scene;
	private GpuTextureView destination;
	private int renderWidth;
	private int renderHeight;
	private int outputWidth;
	private int outputHeight;
	private long index;

	ExternalUpscaleFrame() {
	}

	/**
	 * Writes this frame's contents over the last one's.
	 * <p>
	 * Package-private: the only thing that may hand a frame to a provider is the registry, and the only
	 * thing that may fill one in is the render scale. A frame that could be built anywhere is a frame
	 * that could be built wrong.
	 */
	void lend(CommandEncoder encoder, GpuDevice device, GpuTextureView scene,
			GpuTextureView destination, int renderWidth, int renderHeight, int outputWidth,
			int outputHeight, long index) {
		this.encoder = encoder;
		this.device = device;
		this.scene = scene;
		this.destination = destination;
		this.renderWidth = renderWidth;
		this.renderHeight = renderHeight;
		this.outputWidth = outputWidth;
		this.outputHeight = outputHeight;
		this.index = index;
		this.valid = true;
	}

	/** Takes the frame back, after which nothing on it can be read. */
	void recall() {
		this.valid = false;
		this.encoder = null;
		this.device = null;
		this.scene = null;
		this.destination = null;
		this.renderWidth = 0;
		this.renderHeight = 0;
		this.outputWidth = 0;
		this.outputHeight = 0;
	}

	/** Whether the offer this frame belongs to is still running. */
	public boolean isValid() {
		return this.valid;
	}

	private void check() {
		if (!this.valid) {
			throw new IllegalStateException("this frame belongs to the offer that has already returned, "
					+ "and its images are the engine's: nothing on it may be read, kept or destroyed "
					+ "outside the call it arrived in");
		}
	}

	/**
	 * The frame's command stream, which is this frame's own and is submitted with it.
	 * <p>
	 * A provider records into this and returns. The interface is drawn into this frame's destination
	 * later on the same stream, so work recorded here is ordered before it.
	 */
	public CommandEncoder encoder() {
		check();

		return this.encoder;
	}

	/** The device the frame's images belong to, for a provider that has to allocate of its own. */
	public GpuDevice device() {
		check();

		return this.device;
	}

	/** The finished world frame at the render size: what a provider upscales. */
	public GpuTextureView scene() {
		check();

		return this.scene;
	}

	/**
	 * The window-sized colour a provider writes: what the interface is then drawn onto.
	 * <p>
	 * The same image this engine's own sharpen would have written, and the game's own colour rather
	 * than anything allocated for this seam.
	 */
	public GpuTextureView destination() {
		check();

		return this.destination;
	}

	/** The scene's width, which is the size a provider upscales from. */
	public int renderWidth() {
		check();

		return this.renderWidth;
	}

	/** The scene's height, which is the size a provider upscales from. */
	public int renderHeight() {
		check();

		return this.renderHeight;
	}

	/** The destination's width, which is the size a provider upscales to. */
	public int outputWidth() {
		check();

		return this.outputWidth;
	}

	/** The destination's height, which is the size a provider upscales to. */
	public int outputHeight() {
		check();

		return this.outputHeight;
	}

	/**
	 * Which offered frame this is, counting from one for the provider's own install.
	 * <p>
	 * Enough for a provider to tell a first frame from a later one, and deliberately no more: it is not
	 * the game's frame count and it is not a wall clock, and a provider wanting the identity the rest
	 * of the frame exchange uses reads that from the frame export.
	 */
	public long index() {
		check();

		return this.index;
	}

	/** Whether the render size and the window's differ, which is the only frame this seam is offered on. */
	public boolean isUpscale() {
		check();

		return this.renderWidth != this.outputWidth || this.renderHeight != this.outputHeight;
	}
}
