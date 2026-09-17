package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.frame.FrameExport;
import dev.vitrail.frame.FrameResources;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * The moment of the frame an exporter is handed.
 * <p>
 * <strong>Where it runs, and why there.</strong> The engine reaches this after
 * {@link RenderScale#endWorld} has put the window-sized set back into the main target and drawn the
 * scaled picture up onto it, and before the game clears that target's depth for the interface. At
 * that line: the world and everything drawn after it are finished, the upscale and the sharpen have
 * run, the interface has not been drawn, no render pass is open, and the frame is still open. So
 * what an exporter is given is the frame's finished picture with no UI in it, and it is given it at
 * the last moment that is still true - the depth the interface is about to clear is a deadline, not
 * a convenience.
 * <p>
 * <strong>It hangs at the anchor and not inside {@code endWorld}.</strong> That method returns at
 * once on every frame the scale is not engaged, which is most frames of most sessions; an export
 * behind it would silently export nothing for a player who never moves the slider. The seam is the
 * wrapper, which fires on every frame that renders a world.
 * <p>
 * <strong>Nothing is exported when no pack is drawing.</strong> The chain is what makes the frame
 * this engine's picture: with no pack there is no depth copy, no vectors and no frame of ours to
 * describe, and the colour left in the game's target is the game's own. {@link PackChain#exportFrame}
 * answers null there and this method returns, which is what keeps a stack with no pack out of an
 * export that would otherwise be describing somebody else's frame.
 * <p>
 * <strong>A failure here cannot take the frame with it.</strong> This describes a frame the game is
 * in the middle of drawing, and a description is not worth failing one over. The first failure says
 * so in the log and stops the export for the session; the exporter that threw is never called
 * again, and a later one is given its own chance, because the component that failed and the
 * component that takes over are not the same component.
 */
public final class WorldFrameExport {

	/**
	 * The exporter that has already thrown. Not volatile and not synchronised: the seam runs on the
	 * render thread, and a race against an installer on another thread costs one caught failure.
	 */
	private static FrameExport.Sink broken;

	/** How many frames have been exported this session, for the frame's own index. */
	private static long exported;

	private WorldFrameExport() {
	}

	/**
	 * Offers this frame to whoever is exporting frames, if anything is.
	 * <p>
	 * Called by the injection that wraps the interface's depth clear, before the original call.
	 * Costs one volatile read on a stack that exports nothing, and allocates nothing until a pack is
	 * drawing and an exporter is listening.
	 *
	 * @param main the game's own render target, holding the window-sized set by the time this runs
	 */
	public static void publish(final RenderTarget main) {
		final FrameExport.Sink exporter = FrameExport.sink();

		if (exporter == null || exporter == broken) {
			return;
		}

		final FrameResources frame;

		try {
			frame = PackChain.exportFrame(main, exported + 1L);
		} catch (GpuDeviceLossException e) {
			// Not about the export: the device is gone, and the whole engine unwinds on this.
			throw e;
		} catch (RuntimeException e) {
			standDown(exporter, e);

			return;
		}

		if (frame == null) {
			return;
		}

		try {
			exporter.frame(frame);
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			standDown(exporter, e);

			return;
		}

		exported = frame.index();
	}

	/**
	 * Stops the export for the session after a failure, saying so once.
	 * <p>
	 * The exporter that threw is kept beside the latch rather than inside it, so the same one
	 * failing again is silent while a different one still gets its chance: uninstalling is what the
	 * seam does, and a component that attaches later is not punished for this one's defect.
	 */
	private static void standDown(final FrameExport.Sink exporter, final RuntimeException failure) {
		broken = exporter;
		FrameExport.uninstall();
		Vitrail.logger().error("The external frame export stopped after an error; the picture is "
				+ "unaffected, and nothing is exported for the rest of this session", failure);
	}
}
