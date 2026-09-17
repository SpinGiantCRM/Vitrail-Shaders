package dev.vitrail.frame;

import org.joml.Matrix4fc;
import org.joml.Vector3dc;

/**
 * One rendered frame, as the engine hands it to whatever is exporting frames.
 * <p>
 * <strong>Nothing here is owned or kept by the reader.</strong> Every resource is one the engine
 * already had and will go on owning: the images are recreated by a resize, a pack reload or a
 * render scale engagement, and a holder that keeps one across any of those has a use after free
 * rather than an exception. What arrives here is {@link EngineImage}, which is a handle and a
 * format rather than the engine's own view, so a stale one is a number that can be compared and
 * refused rather than a reference into freed memory. The value is valid for the call it arrives in,
 * which is the whole of the contract, and {@link FrameExport.Sink} says the same thing from the
 * other side.
 * <p>
 * <strong>An absent resource is absent, not empty.</strong> {@code depth} is null on a frame the
 * pack did not fill its converted copy, and {@code motionVectors} is null on a frame the pass did
 * not draw - which is every frame no consumer asked for it. Null is the honest value in both cases
 * and a caller must not paper over it: a frame exported with last frame's vectors still in place is
 * worse than one exported without any, because a temporal consumer has no way to tell.
 * <p>
 * <strong>{@code motionVectorImage} is not a fourth resource.</strong> It is the image behind
 * {@code motionVectors}, and it is carried separately because the two answer different questions:
 * the view handle inside it says whether this frame's vectors were written, and the record itself
 * says whether there is anything allocated at all. {@code MotionVectors} frees its image on any frame nothing reads it,
 * so between two frames of demand the image goes out of existence - and a consumer holding a
 * descriptor for it must be told that happened rather than discovering it. A null view with a
 * non-null image is the one frame after an allocation where the pass legitimately drew nothing.
 * <p>
 * <strong>The matrices are the pair the frame was rendered with, and they are not the pair a pack
 * reads.</strong> {@code view} is the model view and {@code projection} the projection in the
 * volume the device rasterises into, reversed Z over 0..1, which is what {@code MotionVectors}
 * reprojects a depth sample with and the only pair that round trips. The matrices a pack reads have
 * been converted to OpenGL's volume, and pairing one of those with one of these reconstructs a
 * position that is wrong by more the further away it is - see {@code ViewMatrices#previousRendered}.
 * <p>
 * <strong>The camera positions are the published ones, and they are shifted.</strong> X and Z carry
 * a common offset that keeps them inside a float, which is exact in the difference between the two
 * and inexact in either one alone: a consumer wanting a world position has the unshifted pair
 * available from the engine, and one wanting the distance the camera moved - which is what a
 * reprojection needs - has it here to the bit.
 *
 * @param sceneColour            the frame's finished world colour, HUD-less, at the window size
 * @param depth                  the pack's converted depth copy, or null when there is none
 * @param motionVectors          this frame's vectors, or null when the pass did not draw them
 * @param motionVectorImage      the image behind them, or null when none is allocated
 * @param view                   the current frame's model view, as rendered
 * @param previousView           the previous frame's, or the same one on a frame with no history
 * @param projection             the current frame's projection, as rendered
 * @param previousProjection     the previous frame's, or the same one on a frame with no history
 * @param cameraPosition         the current camera position, in the published convention
 * @param previousCameraPosition the previous frame's, shifted by the same amount
 * @param nearPlane              the near plane the frame's projection was built with
 * @param farPlane               the far plane, which may be an infinite one
 * @param index                  which exported frame this is, counting from one for the session
 */
public record FrameResources(
		EngineImage sceneColour,
		EngineImage depth,
		EngineImage motionVectors,
		EngineImage motionVectorImage,
		Matrix4fc view,
		Matrix4fc previousView,
		Matrix4fc projection,
		Matrix4fc previousProjection,
		Vector3dc cameraPosition,
		Vector3dc previousCameraPosition,
		float nearPlane,
		float farPlane,
		long index) {

	public FrameResources {
		if (sceneColour == null) {
			// The one resource every frame the engine exports has: a frame without it is not a
			// frame this export has anything to say about, and the seam does not offer one.
			throw new IllegalArgumentException("a frame must carry its scene colour");
		}

		if (view == null || previousView == null || projection == null || previousProjection == null) {
			throw new IllegalArgumentException("a frame must carry all four of its matrices");
		}

		if (cameraPosition == null || previousCameraPosition == null) {
			throw new IllegalArgumentException("a frame must carry both camera positions");
		}

		if (!(nearPlane > 0.0F)) {
			throw new IllegalArgumentException("a near plane must be positive, not " + nearPlane);
		}

		if (motionVectors != null && motionVectorImage == null) {
			// A view with no image behind it is not a state this seam can produce, and accepting it
			// would let a caller publish a descriptor whose resource nothing owns.
			throw new IllegalArgumentException("motion vectors were drawn, so their image must be there");
		}
	}

	/** Whether this frame has a depth copy a consumer could read. */
	public boolean hasDepth() {
		return this.depth != null;
	}

	/** Whether this frame's motion vectors were drawn, so they can be exported at all. */
	public boolean hasMotionVectors() {
		return this.motionVectors != null;
	}

	/** Whether the motion vector image exists, whatever this frame did with it. */
	public boolean hasMotionVectorImage() {
		return this.motionVectorImage != null;
	}

	/** The width of the frame's colour, in pixels. */
	public int width() {
		return this.sceneColour.width();
	}

	/** The height of the frame's colour, in pixels. */
	public int height() {
		return this.sceneColour.height();
	}
}
