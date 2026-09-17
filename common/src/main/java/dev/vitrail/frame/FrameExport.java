package dev.vitrail.frame;

/**
 * The engine's side of an external frame export: who is listening, and what the renderer may skip.
 * <p>
 * <strong>This class names nothing.</strong> It is the seam between the engine and whichever
 * component wants the frame's resources, and it holds no reference to one: an exporter installs a
 * {@link Sink} and the engine hands it a {@link FrameResources} once a frame. That keeps the tree
 * the engine draws in free of any third-party API, which is a property with a practical
 * consequence: the engine compiles and runs identically whether or not anything is exporting, and
 * the class that speaks a particular API is loaded only when that API is present.
 * <p>
 * <strong>An installed sink is called at one point of the frame</strong> - after the world has been
 * drawn and upscaled and before the interface, which is where the frame's picture is finished and
 * still HUD-less, and where no render pass is open. The sink is called on the render thread, inside
 * the frame, and must not keep anything it is handed: {@link FrameResources} says why.
 * <p>
 * <strong>Nothing here is a frame's worth of work in itself.</strong> With no sink installed, the
 * engine's seam returns after one read of a volatile and allocates nothing - which is what keeps a
 * session that exports nothing identical to one that could.
 */
public final class FrameExport {

	/**
	 * Whoever is exporting the engine's frames.
	 * <p>
	 * Called once per frame the engine has a finished world picture for, on the render thread,
	 * outside any render pass, and only while installed. It must not retain the frames it is given,
	 * must not throw a failure of its own into the frame (a description of a frame cannot be worth
	 * failing one over), and must return promptly: it runs inside the frame it describes.
	 */
	@FunctionalInterface
	public interface Sink {

		void frame(FrameResources frame);
	}

	private static volatile Sink sink;

	/**
	 * Whether the renderer must draw this frame's motion vectors for a consumer that asked.
	 * <p>
	 * Read on the render thread at the one place those vectors are paid for, so it is a volatile
	 * boolean rather than a call into anything: the engine's own gate is a cheap condition and it
	 * must stay one.
	 */
	private static volatile boolean motionVectorsWanted;

	private FrameExport() {
	}

	/**
	 * Installs the exporter, replacing any previous one.
	 * <p>
	 * Idempotent in the sense that matters to a caller: installing twice leaves one sink, the
	 * second one. A component that attaches and detaches across a session installs again on the
	 * second attach, and the first installation must not be left receiving frames.
	 */
	public static void install(final Sink exporter) {
		if (exporter == null) {
			throw new IllegalArgumentException("an exporter must be something");
		}

		sink = exporter;
	}

	/** Stops exporting: no frame is handed anywhere until something installs again. */
	public static void uninstall() {
		sink = null;
		demandMotionVectors(false);
	}

	/** Whether an exporter is installed. One volatile read, safe from any thread. */
	public static boolean installed() {
		return sink != null;
	}

	/**
	 * The installed exporter, or null when nothing is exporting. One volatile read.
	 * <p>
	 * Handed out rather than called through, so that the one place a frame is exported can hold on
	 * to which exporter it was talking to: a failure has to be attributed to a component and not to
	 * the seam, and a sink that has already thrown once must not be called again.
	 */
	public static Sink sink() {
		return sink;
	}

	/**
	 * Whether a consumer asked for this frame's motion vectors.
	 * <p>
	 * The engine asks this before it pays for them, and answers a false by giving back the image
	 * instead: the pass is a full screen target and a full screen draw, and on a frame nothing will
	 * read them it is work spent on nobody. Which is why the exporter sets it rather than the
	 * engine inferring it - only the other side knows whether anything is subscribed.
	 */
	public static boolean wantsMotionVectors() {
		return motionVectorsWanted;
	}

	/**
	 * States whether anything wants this frame's motion vectors, for the exporter to call when the
	 * answer changes.
	 * <p>
	 * Called by the exporter rather than by the engine, and before the frame it applies to: the
	 * renderer asks once, where the pass would be drawn, so a demand stated during a frame arms the
	 * next one. That is a frame the consumer could not have consumed anyway - it registered during
	 * this one - so the lag costs a consumer nothing and keeps the arming free of any call back
	 * into a foreign API from inside the frame.
	 */
	public static void demandMotionVectors(final boolean wanted) {
		motionVectorsWanted = wanted;
	}
}
