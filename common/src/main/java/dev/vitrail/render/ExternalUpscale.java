package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Who, if anyone, is producing this engine's window-sized picture.
 * <p>
 * <strong>One provider, or none, and never a queue of them.</strong> {@link #install} refuses a second
 * provider while a first is attached rather than replacing it or ordering the two. That is a decision
 * about what a frame is: the destination is one image, so two providers attached at once would be two
 * answers to one question with nothing to choose between them, and the fields below would make the
 * winner depend on which mod attached last. A refusal is visible and immediate; a silent precedence
 * rule is neither.
 * <p>
 * <strong>Nothing here allocates on the frame path.</strong> The frame object is made once, at install,
 * and written over for every offer, so a session at a hundred and forty frames a second costs one
 * field read where nothing is attached and no allocation where something is.
 * <p>
 * <strong>A provider cannot fail the frame it describes.</strong> Anything thrown out of a provider is
 * caught, said once, and answered as {@link ExternalUpscaleProvider.Result#FAILED}, which leaves the
 * engine's own chain to run for that frame. A description of a frame is never worth failing one over,
 * and the same rule holds for the export seam next door.
 */
public final class ExternalUpscale {

	/** The installed provider, or null. Volatile because a session may attach from another thread. */
	private static volatile ExternalUpscaleProvider provider;

	/**
	 * The frame handed to a provider, written over per offer.
	 * <p>
	 * Belongs to the installed provider rather than to a frame: it is filled in by {@link #offer} and
	 * recalled by it, and a provider that kept it is refused on its next read rather than served stale
	 * numbers.
	 */
	private static final ExternalUpscaleFrame frame = new ExternalUpscaleFrame();

	/** Whether a fault has already been said, so a provider failing every frame says it once. */
	private static volatile boolean failureSaid;

	/**
	 * How many frames have been offered to the installed provider, for {@link ExternalUpscaleFrame#index}.
	 * <p>
	 * Counted from one for the provider's own attach and reset with it, so the number is the provider's
	 * own rather than the session's: a component installed halfway through does not have to work out
	 * where in somebody else's count it arrived.
	 */
	private static long offered;

	private ExternalUpscale() {
	}

	/**
	 * Installs a provider, for a caller that will uninstall it.
	 * <p>
	 * The returned handle is what an uninstall is: closing it detaches the provider it installed, and
	 * closing it after another provider has taken its place does nothing, so a component cannot pull
	 * down somebody else's by closing a stale handle. Closing twice is harmless.
	 *
	 * @param candidate the provider, whose result decides whether the engine upscales the frame
	 * @throws IllegalArgumentException when the candidate is nothing
	 * @throws IllegalStateException    when a different provider is already attached
	 */
	public static Registration install(final ExternalUpscaleProvider candidate) {
		if (candidate == null) {
			throw new IllegalArgumentException("a provider must be something");
		}

		final ExternalUpscaleProvider attached = provider;

		if (attached != null && attached != candidate) {
			throw new IllegalStateException("this engine already has a provider producing its window's "
					+ "picture, and one frame has one destination: detach it before attaching another");
		}

		provider = candidate;
		failureSaid = false;
		offered = 0L;
		Vitrail.logger().info("An external provider is now producing this engine's upscaled frame: the "
				+ "engine's own upscale and sharpen stand down on every frame it handles");

		return new Registration(candidate);
	}

	/**
	 * Detaches the installed provider, whoever it is. Uninstalling with nothing attached does nothing.
	 * <p>
	 * The frame after this runs the engine's own chain, exactly as a session with no provider ever
	 * attached does, which is the promise this whole seam rests on.
	 */
	public static void uninstall() {
		final ExternalUpscaleProvider attached = provider;

		if (attached == null) {
			return;
		}

		provider = null;
		failureSaid = false;
		offered = 0L;
		frame.recall();
		Vitrail.logger().info("The external upscale provider has detached: the engine upscales its own "
				+ "frames again");
	}

	/** Whether anything is producing this engine's window-sized picture. One field read. */
	public static boolean installed() {
		return provider != null;
	}

	/**
	 * Offers this frame to the installed provider, if there is one.
	 * <p>
	 * Called by {@link RenderScale#endWorld} where its own upscale would have been drawn, with no render
	 * pass open. The answer decides whether the engine draws its own.
	 *
	 * @return {@code true} when the provider produced the frame and the engine's own chain must be
	 *         skipped; {@code false} on every other outcome, including no provider at all
	 */
	static boolean offer(final CommandEncoder encoder, final GpuDevice device, final GpuTextureView scene,
			final GpuTextureView destination, final int renderWidth, final int renderHeight,
			final int outputWidth, final int outputHeight) {
		final ExternalUpscaleProvider attached = provider;

		if (attached == null) {
			return false;
		}

		frame.lend(encoder, device, scene, destination, renderWidth, renderHeight, outputWidth,
				outputHeight, ++offered);

		final ExternalUpscaleProvider.Result result;

		try {
			result = attached.upscale(frame);
		} catch (GpuDeviceLossException e) {
			// Not the provider's fault and not the engine's: the device is gone and the whole frame
			// unwinds on this, so it is not caught as a provider failure.
			throw e;
		} catch (RuntimeException e) {
			// Said once and not per frame: a provider that faults on every frame would otherwise fill
			// the log with one defect, and the first line is the one that names it.
			if (!failureSaid) {
				failureSaid = true;
				Vitrail.logger().error("The external upscale provider threw while producing a frame, so "
						+ "this engine upscaled that frame itself; the picture is unaffected", e);
			}

			return false;
		} finally {
			// Whether it handled the frame or not, and whether it threw: the loan ends here, so a
			// reference kept past this point is refused rather than read as another frame's images.
			frame.recall();
		}

		if (result == null) {
			if (!failureSaid) {
				failureSaid = true;
				Vitrail.logger().error("The external upscale provider answered nothing for a frame, so "
						+ "this engine upscaled it itself; a provider must state what it did");
			}

			return false;
		}

		if (result == ExternalUpscaleProvider.Result.FAILED && !failureSaid) {
			failureSaid = true;
			Vitrail.logger().error("The external upscale provider could not produce a frame and said "
					+ "so, so this engine upscaled it itself; the provider is not asked to say so per "
					+ "frame again this session");
		}

		return result == ExternalUpscaleProvider.Result.HANDLED;
	}

	/** What {@link #install} hands back, so a provider can detach itself and only itself. */
	public static final class Registration implements AutoCloseable {

		private final ExternalUpscaleProvider owner;
		private boolean closed;

		private Registration(final ExternalUpscaleProvider owner) {
			this.owner = owner;
		}

		/**
		 * Detaches the provider this handle installed, if it is still the one attached.
		 * <p>
		 * Idempotent, and deliberately not "uninstall whatever is there": a component that closed
		 * twice, or closed late, must not take down a provider that replaced it.
		 */
		@Override
		public void close() {
			if (this.closed) {
				return;
			}

			this.closed = true;

			if (provider == this.owner) {
				uninstall();
			}
		}

		/** Whether this handle has been closed, whatever is attached now. */
		public boolean isClosed() {
			return this.closed;
		}
	}
}
