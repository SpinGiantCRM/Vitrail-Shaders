package dev.vitrail.render;

/**
 * Something outside this engine that can produce the window's picture from the world's.
 * <p>
 * <strong>What it is for.</strong> With the render scale engaged, this engine renders the world into
 * a stand-in smaller than the window and then draws its own upscale of that stand-in onto the
 * window-sized colour. This is the seam that lets something else do that second step: the finished
 * world frame at the render size and the window-sized destination are offered together, and a
 * provider that answers {@link Result#HANDLED} has produced the picture the interface is then drawn
 * onto.
 * <p>
 * <strong>The frame is a loan.</strong> Every image in it is one this engine already had, will go on
 * owning and will destroy by the same paths as before, whether or not anything is attached. A
 * provider records its work into the frame's own command encoder and returns; it must not keep the
 * frame, must not keep any of its images, and has no way to destroy one. {@link ExternalUpscaleFrame}
 * enforces the first of those rather than trusting it.
 * <p>
 * <strong>Ordering is the engine's, and it is the strongest reason a provider can be this simple.</strong>
 * The destination is the same window-sized colour this engine's own sharpen writes on the same frame
 * through the same encoder, and the interface is drawn into that colour afterwards. A provider writes
 * where the sharpen would have written, at the moment it would have written there, through the same
 * command stream - so its result is ordered before the interface by exactly the mechanism that already
 * orders the sharpen before it, and no wait, fence, semaphore or queue idle is involved anywhere.
 * <p>
 * <strong>No provider means no change at all.</strong> With nothing attached the engine's own chain
 * runs on every frame, exactly as it does today, and the offer costs one read of a field. A provider
 * that declines, that has been removed, or that reports {@link Result#FAILED} leaves that chain to run
 * as well, which is why the fallback is never a second implementation of anything.
 */
@FunctionalInterface
public interface ExternalUpscaleProvider {

	/** What a provider did with the frame it was offered. */
	enum Result {

		/**
		 * The provider did nothing with this frame and the engine's own chain runs.
		 * <p>
		 * The honest answer for a frame a provider cannot serve - a size it has no pipeline for, a
		 * frame it has decided is not worth the work - and the one that costs the player nothing but
		 * the offer.
		 */
		NOT_HANDLED,

		/**
		 * The provider produced the window's picture and the engine must not overwrite it.
		 * <p>
		 * The claim is that the destination holds what this frame should end as. The engine skips its
		 * own upscale and sharpen for the frame and draws the interface on top of what the provider
		 * left there.
		 */
		HANDLED,

		/**
		 * The provider meant to serve this frame and could not, and the engine's own chain runs.
		 * <p>
		 * Distinguished from {@link #NOT_HANDLED} only so that a fault is visible: the engine says so
		 * once rather than every frame, and a provider is not silently ignored for a whole session.
		 * What the destination holds after a failure is the provider's business, so a provider that
		 * can fail part way has to leave the destination worth drawing on - or answer
		 * {@link #NOT_HANDLED} before it writes anything.
		 */
		FAILED
	}

	/**
	 * Produces this frame's window-sized picture, or leaves it to the engine.
	 * <p>
	 * Called on the render thread, inside the frame, at the point {@code RenderScale.endWorld} would
	 * have drawn its own upscale and sharpen, with no render pass open. It must return promptly: it
	 * records GPU work and returns, it does not wait for it.
	 *
	 * @param frame this frame, borrowed for the length of the call and invalid immediately after it
	 * @return what to do with the frame, which must not be null
	 */
	Result upscale(ExternalUpscaleFrame frame);
}
