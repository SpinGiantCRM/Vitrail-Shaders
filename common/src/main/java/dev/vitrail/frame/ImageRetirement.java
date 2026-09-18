package dev.vitrail.frame;

import dev.vitrail.Vitrail;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Frees an image this engine owns only once nobody outside it can still be reading it.
 * <p>
 * <strong>An image handed to a consumer outlives the code that used to free it.</strong> The
 * engine's own images are freed on the paths it always freed them on - a resize, a pack reload, a
 * scale standing down, a pass giving its target back - and every one of those is safe today because
 * the only thing that could read a freed image is the engine, which stops reading it at the same
 * moment. A borrowed descriptor breaks that: a consumer can have recorded work against the image
 * and be waiting on the GPU for it, so freeing the image the moment the engine loses interest is a
 * use after free rather than an exception, and one that is silent when it happens.
 * <p>
 * <strong>Two events and not one.</strong> Those paths used to be one event, the destruction. They
 * are two now, and keeping them apart is the whole of this class:
 * <ul>
 *   <li><em>the publication ends</em> - now, at the moment the engine loses interest, because a
 *       consumer must not be able to acquire a generation that is about to stop existing;</li>
 *   <li><em>the image is freed</em> - later, when every consumer that ever acquired a descriptor for
 *       it has said something final and every completion primitive they named has been reached.</li>
 * </ul>
 * Delaying the first to avoid the second would be the wrong fix: a publication left standing is a
 * consumer still being handed an image nothing will keep alive.
 * <p>
 * <strong>The accounting is not here.</strong> Which consumers hold a descriptor, what they said
 * when they let go and whether their completion has been reached is knowledge the external frame
 * API owns, because it is the thing that handed the descriptors out. This class holds none of it
 * and does not guess at it: it asks the installed {@link Ledger}, which answers from the exchange's
 * own generation record. One source of truth about who is using what, and this is not it.
 * <p>
 * <strong>A stack with nobody listening pays nothing.</strong> With no ledger installed, or with a
 * ledger that has never published the image at all, {@link #retire} frees it on the spot - exactly
 * what the line it replaced did - and allocates nothing, submits nothing and waits for nothing. The
 * same is true of a ledger whose consumer acquired nothing: a publication with no handles is
 * finished with the moment it ends. The queue below only ever holds an image some consumer really
 * has an outstanding obligation against.
 * <p>
 * <strong>Render thread only.</strong> Every call here is made from the frame the engine is
 * drawing: retirement happens where the engine loses interest in an image, which is inside the
 * frame, and {@link #drain()} runs once at the frame boundary. Nothing here is synchronised, and
 * nothing here needs to be.
 *
 * @see Ledger
 */
public final class ImageRetirement {

	/**
	 * Whoever can say whether an image an engine owns is finished with, for an external API.
	 * <p>
	 * Installed by the class that speaks that API and by nothing else, so that the renderer's own
	 * classes stay free of it: the engine frees an image the same way whether or not anything is
	 * exported, and the only thing that changes is which question is asked first.
	 * <p>
	 * An implementation must be honest about the absent case, which is the common one: an image no
	 * publication ever stood over is not owed anything and answers {@code true} to
	 * {@link #finished}. It must also be non-blocking, because it is asked on the render thread
	 * inside the frame: reading a completion primitive's status is the intended cost, and waiting on
	 * one is not this engine's to do.
	 */
	public interface Ledger {

		/**
		 * Ends whatever publication stands over this image, now, so that no consumer may acquire it.
		 * <p>
		 * Called before the image is freed and never instead of it. Does nothing when this image was
		 * never published, which is a session nobody is exporting.
		 */
		void ended(long nativeImage);

		/**
		 * Whether every consumer of this image is finished with it: every descriptor acquired from
		 * the publication that ended, accounted for, and every completion primitive those consumers
		 * named reached. Non-blocking.
		 * <p>
		 * A false does not stop the engine drawing. It stops this one image being freed, and keeps it
		 * that way until a later frame finds a yes.
		 */
		boolean finished(long nativeImage);

		/**
		 * Said after the image has been freed: the API may forget the generation, so that a
		 * long-lived session does not accumulate the record of every image it ever replaced.
		 * <p>
		 * Called only for an image {@link #finished} answered true for, so an implementation that
		 * refuses to forget a generation with outstanding uses never has to.
		 */
		void forgotten(long nativeImage);
	}

	/** One image waiting for its consumers, and how to free it when they are done. */
	private record Pending(long nativeImage, String label, Runnable destroy) {
	}

	private static volatile Ledger ledger;

	/** The images that have been retired and are waiting. Render thread only. */
	private static final List<Pending> pending = new ArrayList<>();

	private ImageRetirement() {
	}

	/**
	 * Installs the ledger, or removes it with null.
	 * <p>
	 * Null is the right state for a session that is not exporting frames: it makes every retirement
	 * immediate, which is what this engine did before any of this existed. Installing a ledger is
	 * the moment an engine's images start being kept alive for somebody, and it is the API's call
	 * and not the renderer's.
	 * <p>
	 * An installed ledger is not removed when a session detaches: retirements that are already
	 * waiting still have to be resolved, and the record of a generation that ended outlives the
	 * publication that ended it. Nothing accumulates by leaving it installed, because a ledger with
	 * no open publication answers every image immediately.
	 */
	public static void ledger(final Ledger installed) {
		ledger = installed;
	}

	/**
	 * Retires one of this engine's images: ends its publication now, and frees it as soon as every
	 * consumer is finished with it.
	 * <p>
	 * The immediate path is the ordinary one and is not a fallback: no ledger, no native handle, or
	 * a ledger with nothing outstanding all free the image inside this call, on the same line the
	 * caller used to free it on, so a stack nobody is exporting behaves exactly as it did.
	 *
	 * @param nativeImage the native handle the publication named, or zero when the image is not one
	 *                    this backend can describe - nothing can have been published for either
	 * @param label       what this image is, for the one line said when it has to wait
	 * @param destroy     frees the image and its views; runs exactly once
	 */
	public static void retire(final long nativeImage, final String label, final Runnable destroy) {
		final Ledger installed = ledger;

		if (installed == null || nativeImage == 0L) {
			destroy.run();

			return;
		}

		// Before the question below and not after: a consumer must not be able to acquire this
		// generation between the moment the engine stopped wanting the image and the moment it is
		// freed, and that window is exactly the one a check-then-invalidate would open.
		installed.ended(nativeImage);

		if (installed.finished(nativeImage)) {
			installed.forgotten(nativeImage);
			destroy.run();

			return;
		}

		pending.add(new Pending(nativeImage, label, destroy));
		Vitrail.logger().info("Holding the {} image (0x{}) until the consumer using it is finished: a "
				+ "descriptor for it is outstanding, so it is freed on a later frame rather than now",
			label, Long.toHexString(nativeImage));
	}

	/**
	 * Frees every retired image whose consumers are finished, one bounded pass.
	 * <p>
	 * Runs once a frame, at the frame boundary, and does nothing at all when nothing has been
	 * retired: an empty list and one read of a volatile is the whole cost on a frame that replaced
	 * nothing. Nothing else about this is periodic - an image enters this list when the engine
	 * retires it and leaves it on the frame a consumer's completion is reached.
	 */
	public static void drain() {
		if (pending.isEmpty()) {
			return;
		}

		final Ledger installed = ledger;

		for (final Iterator<Pending> waiting = pending.iterator(); waiting.hasNext();) {
			final Pending retired = waiting.next();

			if (installed != null && !installed.finished(retired.nativeImage())) {
				continue;
			}

			if (installed != null) {
				installed.forgotten(retired.nativeImage());
			}

			waiting.remove();
			retired.destroy().run();
			// The same image the line above held, on the frame its consumers let it go. One line per
			// image that really waited rather than one per frame, which is what makes "freed exactly
			// once" something a run can count rather than something this class claims.
			Vitrail.logger().info("Freed the {} image (0x{}) after every consumer of it was finished",
				retired.label(), Long.toHexString(retired.nativeImage()));
		}
	}

	/**
	 * How many images are waiting, for the log and for a measurement of the cost this adds.
	 * <p>
	 * Zero on every ordinary frame, which is the number worth watching: a session that replaces no
	 * image and has no consumer never puts anything here.
	 */
	public static int waiting() {
		return pending.size();
	}
}
