# The external frame export

Vitrail draws a finished frame, and something outside it may want that frame: the colour before the
interface goes on, the depth the pack converted, and the motion vectors the engine can produce. This
page is the mechanism behind handing those out, so that a component which is not this engine can
consume them without either side naming the other.

Nothing here changes what a player sees. With nothing attached - which is every session that does
not have the mod below installed - the engine behaves exactly as it did before this existed, and the
whole of the cost is one read of a field per frame.

## The seam

The export hangs off the injection that already wraps the interface's depth clear in
`GameRendererScaleMixin`, immediately after `RenderScale.endWorld` has returned and before the game's
own clear runs. At that line:

- the world, and everything the game draws after it, are finished;
- the render scale has upscaled and sharpened onto the window-sized colour view, and has put the
  window-sized set back into the target, and leaving its own render-sized stand-in alive, which is
  the image the scene colour is taken from
  game's own textures back into the main target;
- no render pass is open;
- the interface has not been drawn, so the picture is HUD-less;
- and the window-sized depth is about to be destroyed by that very clear.

That last point is why the seam is a deadline rather than a convenient landmark, and why the depth
exported is not the device's image.

It is **not** hung inside `RenderScale.endWorld`, because that method returns at once on every frame
the render scale is not engaged, which is most frames of most sessions.

## What is published

| semantic | resource | why that one |
| --- | --- | --- |
| scene colour | the render scale's stand-in, the finished pack output at the **render size**, or the main target's colour when no scale is engaged | it is the scene *before* this engine's own upscale, which is what a consumer that upscales needs; the window's picture is already an upscale, and upscaling it again is upscaling an upscale |
| upscaled scene colour | the main target's colour view, window-sized, on frames the render scale upscaled | the picture the frame ends with, after the pack and after the upscale, before the UI |
| depth | `PackDepth`'s **converted copy**, `R32_FLOAT`, render-sized, forward over 0..1 | it is the volume a pack reads depth in, it is what the engine's own vector pass consumes, and it survives the clear that destroys the device's window-sized depth |
| motion vectors | the existing `MotionVectors` image, `RG16_FLOAT`, render-sized | it already matches the convention consumers expect, so nothing is converted |

**The two colour semantics are one resource or two depending on the render scale**, and the difference
is not decoration:

- While the render scale is off, the world draws straight into the window-sized target, so the scene
  and the window's picture are one image, and it is published as the scene colour alone.
- While it is on, the chain draws into a stand-in smaller than the window and the engine's own upscale
  of that stand-in is what the window ends up holding. Both images exist at the export seam, because
  the stand-in outlives its own upscale, so they are published as two semantics at two sizes.

On the frames where there is no distinct upscale, `upscaled_scene_color` is **not published at all**.
A hundred percent is not a degenerate upscale; it is the absence of one, and publishing the window's
picture as an upscale of itself would say an upscale happened that did not.

**All of them are borrowed.** The engine keeps ownership, recreates and destroys them exactly as it did
before, and no descriptor it hands out carries any way to destroy one. The frame's *meaning* is
re-published every frame; the images themselves are published once and replaced only when the engine
recreates them.

**Exposure is not published at all.** This engine has no exposure value: the packs that compute one
do it inside their own shaders, from images the engine hands them. A number here would be invented.

**Colour is reported as unknown, with the reason.** A storage format is not an encoding. Nothing in
this engine or in the game knows the primaries or the transfer function of the target it rasterises
into, the picture is past whatever tone mapping the pack does, and no paper white is stated anywhere.

## Motion vectors are drawn only when something asks

The engine's vector pass used to run only when the render scale was engaged *and* the engine's own
temporal fold was on - and the fold is off by default. That is deliberate: on any frame nothing reads
the vectors, the pass is a full screen image and a full screen draw spent on nobody, and
`MotionVectors.standDown` gives the image back on exactly those frames.

So the export can ask. `FrameExport.wantsMotionVectors` is a field the renderer reads where the pass
would be drawn, and it is set from what the attached consumers have declared they want. Two
consequences worth knowing:

- **The pass can now be armed at a render scale of a hundred percent**, where the fold has nothing to
  do. A consumer that wants vectors is served at the render size, which is the window size there.
- **Arming lags one frame.** The consumer side is stated when the frame is exported, which is after
  the pass has already been decided for that frame. The lag is invisible in practice: a consumer that
  registered during a frame could not have consumed that frame anyway.

## Lifetime and ownership

Every descriptor is published as borrowed, valid until the engine replaces it. What a consumer has to
respect:

- **A handle is not a permission to destroy.** There is no destroyer on a borrowed publication to
  call, and the engine's own release paths are unchanged.
- **The motion vector image is the one resource that can disappear mid-session.** The pass frees it
  when nothing reads it, so the publication ends there and a consumer holding a descriptor is
  invalidated rather than left pointing at freed memory. When a demand returns, the image is
  allocated again at a new handle, which is a new generation.
- **A replacement is a history reset.** The image behind a semantic changes on a resize, a render
  scale engagement and a pack reload, and each of those is reported to a consumer as a resolution
  change or a resource recreation. A consumer is never expected to work that out from the matrices.

## Synchronisation

The engine records its work into the frame's own command buffer, which the game submits at the end of
the frame, and it does not own that submission. So the export claims the weakest honest thing:

- **ready** - already ordered: the frame's work is recorded into the engine's own command buffer and
  submitted at the frame's end, and a consumer of this API records on that same graphics queue - its
  submission is behind the engine's in queue order, against the same queue family. That order is an
  execution dependency and not a memory one: the barrier a consumer records from the published state
  is what makes the frame's writes visible to its reads.
- **release** - not declared: the engine cannot name a consumer's completion primitive, and it does
  not guess at one. A consumer that borrows an image states its own completion, and it must finish
  with an image before the engine replaces or frees it - a replacement is reported as a new
  generation, and a handle from the old one is refused, so the deadline is observable rather than
  guessed at.

Nothing is read back, copied, or waited on. There is no frame counter anywhere in this mechanism, and
no deferred destruction: an image is freed when the engine no longer needs it, and a consumer is told.

## Where the images are

A descriptor that names no state cannot be used for GPU work, so every descriptor states two facts a
consumer cannot work out for itself:

- **the layout** - always `VK_IMAGE_LAYOUT_GENERAL`, because that is where every image this engine
ows lives for its whole life. It is not tracked per image and it is not a guess: a texture is created
with `initialLayout` of `VK_IMAGE_LAYOUT_UNDEFINED` and transitioned to `VK_IMAGE_LAYOUT_GENERAL`
once, in its constructor; dynamic rendering names the general layout for every colour and depth
attachment; every sampled and storage descriptor names it; and the only two transitions in the whole
game are that constructor's and the swapchain's, which is not one of these images. The stage and
access masks published with it are the widest legal scope for that layout rather than this frame's
precise last write - wide is the safe direction for a barrier's source scope.
- **the queue family** - the family of the graphics queue of the device that created the image, read
from the device and not written down as family zero. Zero is a property of a card's family layout,
not of the engine.

The layout is the fact that matters: `VK_IMAGE_LAYOUT_UNDEFINED` is not a way of saying "nobody
knows", because as a barrier's old layout it permits the contents to be discarded, which would make a
consumer read a frame that is no longer there. An image whose state could not be read is published as
undefined and flagged as not exact rather than described as something it might not be, and the
publication is replaced when the state moves even though the handle did not - a consumer holding the
old descriptor would otherwise be describing an image that is no longer there.

What is checked, and by whom: an outside validation consumer reads each descriptor and records a
same-layout image barrier naming the *published* layout as its old layout, on the engine's own command
stream. It writes nothing, samples nothing and leaves every image where it found it. If the
publication were wrong, the validation layer's own layout tracking - not this engine's - would report
it.

## The optional dependency

The export speaks the API of a mod that may not be installed, and it is compiled against that API
without requiring it. The artifact is not published to any repository, so it is vendored under
`libs/` and used compile-only - no class of it reaches this mod's jar, and at runtime the classes come
from that mod's own jar.

Two things make that safe rather than lucky:

- **The engine names nothing of it.** `dev.vitrail.frame` is a seam in the engine's own vocabulary -
  an exporter, a frame's resources - and the class that speaks the foreign API lives in
  `dev.vitrail.b3d`. The engine cannot come to depend on the API, and a stack without it never
  resolves one of its names.
- **The integration is loaded only after a check that it can be.** The loader's client setup asks
  whether that mod is present and calls into the integration on the branch where the answer was yes;
  on the other branch the class is never initialised, and a reference to a class that is never
  initialised costs nothing. The same care runs at shutdown, where the question is asked of the seam
  rather than of the integration, for the same reason.

## Traps

- **A description must not be able to fail the frame it describes.** A failure in the export says so
  once, stands the export down for the session, and leaves the picture alone. This is the same rule
  the frame layer on the other side had to learn, and it is the shape of `WorldFrameExport`.
- **One claim per semantic per frame.** The frame layer drops a semantic that two providers claim at
  once, which loses it for every consumer rather than choosing between two descriptions. So anything
  the frame already carries is left to whoever put it there.
- **Nothing is exported without a pack drawing.** With no chain there is no depth copy, no vectors and
  no frame of this engine's to describe, and the colour left in the game's target is the game's own.
- **The matrices exported are the rendered pair.** The matrices a pack reads have been converted to
  another volume; pairing one of those with one of these reconstructs a position that is wrong by
  more the further away it is. `MotionVectors` carries the long version of that argument.
- **A dimension change is not a level change.** A portal replaces the level the player is standing in
  without going through the game's own level load and unload, so nothing here sees it and no history
  reset is requested for it: a temporal consumer that walks through one keeps a history belonging to
  the dimension it left. This is a known gap rather than a decision, and it belongs with whoever next
  touches temporal history, not with the export.
