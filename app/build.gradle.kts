plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rfsat.sts"
    compileSdk = 36

    defaultConfig {
        // Play listing identity, and PERMANENT once published — an app's
        // applicationId cannot be changed afterwards without shipping a
        // different app and losing every install and review. The Kotlin and
        // resource namespace stays com.rfsat.sts, so no source moves.
        //
        // Play requires at least two segments separated by a dot, letters,
        // digits and underscores only, and a letter at the start of every
        // segment. "com.STS" satisfies all of that; uppercase is unusual but
        // legal, and matches the com.VTBC convention already in use.
        applicationId = "com.STS"
        minSdk = 26
        targetSdk = 36
        // VERSIONING RULE for this project — follow it on every release:
        //
        //   <brand>.<major>.<minor>
        //     brand  1 = STS. Does not change.
        //     major  incremented when a FEATURE is added; minor resets to 0.
        //     minor  incremented when a CORRECTION is made.
        //
        //   versionCode increments on EVERY build that leaves this machine,
        //   without exception. Play rejects a bundle whose code is not
        //   strictly greater than the last uploaded one, and a code reused
        //   during testing is impossible to tell apart afterwards.
        //
        // 1.25.0 — feature: the camera ADOPTS the face it identifies, and
        //          the plot no longer loses your zoom when you nudge a shot.
        //
        //   MEASURED, on the user's own T0002. The card is ISSF 10 m Air
        //   Pistol: its black measures 3.723 ring pitches across, and that
        //   face expects 3.719 — 0.1% out. Against ISSF 10 m Air Rifle, which
        //   expects 6.100, the same card is 39% out. That is why the SAME
        //   image scored from a photograph and failed from the camera: Import
        //   has identified and adopted the face on load since 1.22.0, so it
        //   quietly corrected the selection; Session only ever COMPLAINED,
        //   leaving registration scaled against a face 39% wrong, which puts
        //   every hole outside the detector's size gates. No hits, no error.
        //
        //   The ratio was not the doubt it looked like. Run over the same
        //   card at 1536, 1280, 1080, 900, 760, 640 and 512 px it reads
        //   3.723, 3.733, 3.730, 3.724, 3.720, 3.723, 3.710 — so neither the
        //   analysis resolution nor the YUV colour channel was to blame, and
        //   both were checked before the face was.
        //
        //   Guarded against the flip-flop this could otherwise cause: a face
        //   the app has moved away from is not silently adopted again in the
        //   same session, and choosing one by hand clears that record, since
        //   the user may deliberately be going back to it.
        //
        //   Both callers now go through adoptFace(), because the spinner and
        //   selectedFace must move together — the 1.18.0 bug where the screen
        //   showed one face and the scorer used another came from setting one
        //   and not the other.
        //
        //   ZOOM: TargetPlotView reset zoom and pan on every assignment to
        //   .face, and Results reassigns it after every edit. So nudging a
        //   shot by a tenth of a millimetre threw you back to the default
        //   zoom, at the exact moment you were zoomed in BECAUSE you were
        //   nudging. It now resets only when the face really changes.
        //
        // 1.24.0 — feature: the guide says continuously whether the card
        //          matches the selected face.
        //
        //   A word in the corner — "Match OK", "Wrong target face",
        //   "Checking", "Aim at the target" — and the rings change with it.
        //   A message that fires once is the wrong shape for this: a shooter
        //   lining a phone up is watching the preview, not a notification
        //   that appeared while they were still walking to the point.
        //
        //   ENCODED THREE WAYS AT ONCE, and deliberately not by colour alone.
        //   Green would be the obvious choice and it breaks the night-red
        //   theme, whose entire purpose is preserving dark adaptation — a
        //   green line would be the one bright non-red thing on that screen.
        //   So under night-red the hue is left alone and the state is carried
        //   by the line style and the word; under the other themes the colour
        //   changes as well. Solid means matched, dashed means doubtful,
        //   finely dashed means wrong. That is also what a colour-blind
        //   shooter needs, on a small screen, in daylight.
        //
        //   The check runs from the frame stream, at most every 2.5 s, and
        //   NOT AT ALL while live detection is armed: those frames belong to
        //   the detector, and a second full ring fit on the same thread would
        //   drop frames — a dropped frame is a shot the persistence rule
        //   never sees.
        //
        //   The full explanation is still spoken, but ONCE per change of
        //   verdict rather than on every check, so a minute spent lining up
        //   does not produce the same warning thirty times.
        //
        // 1.23.1 — correction: the app now WARNS when the card in front of
        //          the camera is not the face that is selected.
        //
        //   The ring guide makes a mismatch visible, but only to someone who
        //   knows to look — and the natural response to circles that will not
        //   line up is to walk until they do. That cannot work, because the
        //   rings are drawn at the face's own proportions and changing
        //   distance resizes them all together. At best it wastes the
        //   shooter's time; at worst they decide it is close enough, fire a
        //   card, and get a score wrong by however much the two faces differ.
        //
        //   So the app looks as well. When the guide is switched on, and
        //   whenever the face changes, it fits the rings on the live preview
        //   and compares them with the selected face — the same two checks
        //   used at registration, the scale-free ratio and the ranking by
        //   fitted pitch. Run on the analysis thread, advisory only: a
        //   preview is not a registration and a card half out of frame should
        //   not produce an accusation.
        //
        //   The cost of a wrong face is now stated in ONE place and quoted
        //   everywhere the app doubts it, rather than three vaguer variants:
        //   the face sets millimetres per pixel, so its error goes straight
        //   into every radius and therefore every score, and because the
        //   detector sizes what it looks for from the same number, a face
        //   wrong by enough finds no hits at all rather than wrong ones. The
        //   message says plainly not to force the guide by moving closer, and
        //   why that cannot work.
        //
        // 1.23.0 — feature: the selected face's rings as an alignment guide.
        //
        //   Four guides now: nothing, the simple crosshair, the RINGS of the
        //   selected face, or both. Size adjustable, because the distance to
        //   the card is set by the range and not by the app.
        //
        //   THE RINGS VERIFY THE FACE, and that is the strongest argument for
        //   them rather than the obvious one. They are drawn at the face's own
        //   RATIOS, so a card whose rings sit at different proportions will
        //   not line up however far the shooter moves: scaling changes every
        //   circle together and cannot change the spacing between them. A
        //   mismatch is therefore visible through the viewfinder before a
        //   shot is fired, rather than afterwards as a score that is quietly
        //   wrong. Selecting the wrong face remains the single largest cause
        //   of nothing being detected at all, and until now nothing in the
        //   app could catch it before the fact.
        //
        //   That property also guards against the obvious misuse. A guide the
        //   shooter can resize freely would otherwise invite forcing a match
        //   with the wrong face by walking closer; fixing the ratios makes
        //   that impossible.
        //
        //   Lining the rings up squares the camera to the card as well, which
        //   matters because perspective is the one error the scorer cannot
        //   fully undo: de-foreshortening recovers about half of what a
        //   square-on view would have given at 30 to 40 degrees, and the rest
        //   is a projective term a single affine correction cannot represent.
        //   Not taking the error beats correcting it.
        //
        //   The guide is a guide, not a requirement. Modest tilt is corrected
        //   perfectly well and a shooter should not be made to fuss.
        //
        //   AimGuide lives in its own file rather than inside CrosshairView:
        //   ScaleSettings stores the choice and is pure logic that the
        //   offline harness compiles without a framework, and an enum tucked
        //   inside a View drags the whole of android.view in behind it.
        //
        // 1.22.1 — correction: the WEAKER registration was the automatic one.
        //
        //   On loading a photograph the app ran the aiming-mark path, which
        //   takes the scale from the black mark multiplied by the ratio of
        //   whichever face happens to be SELECTED — so it is only ever as
        //   right as that selection, and selecting the wrong face is the
        //   single largest cause of nothing being detected at all (1.12.0).
        //
        //   It now identifies instead: fits the ring family, measures the
        //   pitch over five to nine rings, lets the fit say WHICH face it is
        //   rather than assuming, and cross-checks pitch against mark. That
        //   is the same route as the "Identify and register" button, and the
        //   0.2-point difference between the two paths reported from the
        //   field is exactly this — a scale disagreement of about one per
        //   cent, not a bug in either.
        //
        //   The mark path remains, as a FALLBACK rather than the default,
        //   which is the right way round. It is used automatically when the
        //   rings cannot be fitted at all, and when the face has no even
        //   pitch to fit — the practical and service faces put their scoring
        //   zones at unequal spacings by design, and for those the mark is
        //   the only measurement there is. That second case previously left
        //   the card unregistered with a message and no fallback.
        //
        //   Both buttons stay, so either method can still be forced by hand.
        //
        // 1.22.0 — feature: Results markers, photo/template swap, a brighter
        //          crosshair, and the rings that were found but not used.
        //
        //   SHOT MARKERS are half as large again as the hole, hollow, with a
        //   gapped cross on the centre. A filled disc at true size covers the
        //   very hole it marks, which defeats the photo view: the point of
        //   showing the photograph is to check whether a marker sits where a
        //   hole actually is.
        //
        //   PHOTO AND TEMPLATE are now alternatives, not layers. Drawing the
        //   printed template over the shooter's own card put two sets of
        //   rings a pixel or two apart, and the eye reads that as a
        //   registration error whether or not there is one. Shots, group and
        //   point of aim go on top of whichever is shown.
        //
        //   THE CROSSHAIR was a 2 px line at 82 per cent alpha, which
        //   disappears against a white card in sunlight. Now full alpha, 3.5
        //   px, a heavier dark halo for contrast either way, longer arms, and
        //   a bright core. The core is a LIGHTENED accent rather than white,
        //   so night-red stays red — a white core would put back the one
        //   bright thing that theme exists to remove.
        //
        //   RINGS FOUND BUT NOT USED are now drawn dashed on the registration
        //   overlay, and counted in the log. "Detected and left out of the
        //   family" and "never detected at all" looked identical and mean
        //   quite different things: the first is a ladder that chose a
        //   subset, the second is a ring the detector could not see. This
        //   answers, on the card itself, which is happening.
        //
        // 1.21.0 — feature: merged shots, the shot-count check, hole-centre
        //          precision as its own metric, and stage timings.
        //
        //   TWO SHOTS THROUGH ONE PLACE (H1). A connected-component detector
        //   sees them as one region, and the size and roundness gates then
        //   REJECTED that region — losing BOTH shots rather than one. At 10 m
        //   a good shooter puts most of a card through the same few
        //   millimetres, so this is the app's own use case failing.
        //
        //   MergedHoles splits such a region along its own long axis, at the
        //   peaks of the detector response inside it. Measured on synthetic
        //   pairs at known separations:
        //       separation   parts   centre error
        //         0.3 gauge    1     (refused, correctly)
        //         0.5 gauge    1     (refused, correctly)
        //         0.7 gauge    2      0.31 px
        //         0.9 gauge    2      0.05 px
        //         1.2 gauge    2      0.01 px
        //   Below about two thirds of a pellet the holes genuinely overlap
        //   and it declines to guess: a shot invented on paper is a score the
        //   shooter did not fire, which is worse than one missed. A split
        //   shot carries a lower confidence and a `merged` flag for the same
        //   reason — it is an inference, not a measurement.
        //
        //   SHOT COUNT (C1). The rule set has always known the string length
        //   and nothing looked at it. Nine holes where ten were fired is a
        //   missed detection and eleven is a false one. ADVISORY only: the
        //   obvious next step of lowering the threshold until the expected
        //   number appears would manufacture shots on demand and turn the
        //   detector into a machine that always agrees with the course of
        //   fire.
        //
        //   HOLE-CENTRE PRECISION (H2), measured separately from detection
        //   rate for the first time. They fail differently and only one is
        //   visible: a missed hole shows on the plot, a hole half a
        //   millimetre out looks normal and flips any shot near a boundary.
        //   Worst centre error across fifteen sub-pixel positions is 0.35 px,
        //   which at the rectified resolution is about 0.2 mm — under a tenth
        //   of a 10 m air rifle ring.
        //
        //   STAGE TIMINGS in the log: Hough, mark outline, shape, correction
        //   and ladder, separately, with the frame size. Registration
        //   performance has twice been diagnosed by guessing at which stage
        //   was slow; now the field log says.
        //
        //   NOT DONE: the full ring-family homography. It is written and
        //   validated offline — it recovered 39.82 degrees against a true 40
        //   — but it is worth only about a further quarter of the residual
        //   after the ellipse correction, and it depends on the face being
        //   right, which turns a wrong identification into a confident wrong
        //   score. It needs the range photographs before it earns a place.
        //
        // 1.20.0 — feature: resolution discipline, a bound on the ladder
        //          search, and the tilt-axis wedge behind a switch.
        //
        //   RESOLUTION DISCIPLINE. MarkOutline ran at FULL resolution while
        //   everything around it did not — RingFinder works at 700 px and
        //   HoughCentre at 420. On a phone photograph arriving at 3000 px
        //   that meant five threshold passes, each with a morphological
        //   closing of four separable passes, over nine megapixels. It now
        //   works at 700 px and returns the outline in source pixels.
        //
        //   Measured on one card at four sizes, 460 to 3000 px. The mark
        //   radius scales exactly linearly (84.0, 164.8, 329.6, 549.3 against
        //   expected factors of 1.96, 2.00, 1.67), and the SCALE-FREE ratio of
        //   mark radius to ring pitch holds to 0.24 per cent from 900 px
        //   upward. Time is now bounded rather than growing with pixels: 999
        //   ms at nine megapixels against 1311 ms at 0.8.
        //
        //   A HANG, found while measuring the above and worth its own note.
        //   fitLadder tries every PAIR of candidates against nine divisors, so
        //   its cost grows with the CUBE of the candidate list. On a warped
        //   753 px frame — smaller than images that finish in a second — the
        //   pooled list grew enough that a single registration ran for
        //   MINUTES. The pool is now capped at 24, cut by peak strength so
        //   what survives is the clearest evidence rather than whatever
        //   happened to be innermost. No catalogue face has more than a dozen
        //   rings. The same frames now finish in 0.8 to 1.5 s.
        //
        //   THE TILT-AXIS WEDGE, off by default, under Settings. Along the
        //   axis a card tilts about, depth does not change, so the scale there
        //   is exact — which is why the fitted pitch drifts monotonically with
        //   tilt while the true pitch cannot. Measuring only in that direction
        //   should remove the drift.
        //
        //   Measured on four frames it is NOT a clear win: unchanged on one
        //   (37.00 to 37.06), better on one in the sense that it REFUSED a fit
        //   that was 15 per cent wrong rather than returning it, and worse on
        //   a third (31.01 to 32.50). It ships off, with a switch, until real
        //   range photographs can settle it.
        //
        //   NOT ADDED, because it was already there: persistence for live
        //   scoring. LiveHitDetector has required three consecutive frames
        //   since it was written, with a stability tolerance, a global-change
        //   veto, deduplication against the accepted set, and optional
        //   acoustic gating. Proposing it as missing was my error.
        //
        // 1.19.2 — toolchain: the activities can finally be compiled
        //          offline, closing the gap that let three failures reach CI.
        //
        //   tools/offline/typecheck_ui.sh compiles EVERY source file in the
        //   app — 62 of them, activities and custom views included — against:
        //     - view-binding classes GENERATED from the real layouts, so a
        //       field that is not in the layout is not in the stub either;
        //     - an R GENERATED from the real resources, for the same reason;
        //     - a hand-written slice of the Android framework, roughly 25
        //       files across android.view, android.widget, android.graphics,
        //       androidx and the CameraX and Material pieces the app touches.
        //
        //   It runs nothing. The point is only that the compiler resolves
        //   every name, which is exactly what the three CI failures were.
        //
        //   THE STUBS ARE DELIBERATELY NARROW. Each class carries only the
        //   members the app actually uses, because a stub that answered to
        //   anything would resolve a typo as readily as a real name — the
        //   lesson from the JUnit shim in 1.10.1 and the Paint stub in
        //   1.12.2, both of which were too generous and hid the very failure
        //   they existed to catch. Using a new framework API therefore means
        //   adding a line to tools/offline/Stub*.kt.
        //
        //   Getting there took the error count from 2490 to zero over seven
        //   rounds. Two things had to change in the APP rather than the
        //   stubs, and both are improvements in their own right:
        //     - three adapters disagreed about whether getView's parent was
        //       nullable. Android never passes null there; they now agree.
        //       Kotlin cannot express a Java platform type, so a stub cannot
        //       accept both spellings at once.
        //
        //   RUN IT SEPARATELY from run.sh. Two compiler invocations back to
        //   back contend badly — chained, the check went from forty seconds
        //   to several minutes.
        //
        //   WHAT IT STILL DOES NOT COVER: resource merging, manifests, data
        //   binding generation and R8. Gradle remains the authority, and this
        //   is a filter in front of it, not a replacement.
        //
        // 1.19.1 — correction: two compile errors that have been in the
        //          tree since 1.18.0, through two releases.
        //
        //   Both came from copying the resolution spinner out of
        //   ImportActivity into SessionActivity and keeping the original's
        //   helper names: onSelectedIndex, which is private to another file,
        //   and startCamera(), which here is called startCameraIfPermitted.
        //
        //   THE OFFLINE HARNESS CANNOT SEE THIS. The activities are excluded
        //   from it because they need the whole Android framework to compile,
        //   so anything wrong inside one reaches CI untouched. That is now
        //   three times.
        //
        //   NEW GATE, and a sound one rather than a heuristic: a private
        //   function is visible only inside its own file, so an unqualified
        //   call to one from a DIFFERENT file is always an error. It reports
        //   exactly the failure above. Verified by putting the error back —
        //   "onSelectedIndex() is private to ProfileActivity.kt and cannot be
        //   called here" — and then restoring.
        //
        //   Its first run found three FALSE positives, all now excluded:
        //   add() inside a buildList, which is the standard library's and not
        //   Logger's; and onFrame in FrameSource, which is a lambda parameter
        //   rather than SessionActivity's private function of that name.
        //
        //   WHAT IT STILL CANNOT CATCH: a call to a name that exists nowhere
        //   at all, which is what startCamera() was. That needs a compiler and
        //   an android.jar. Generating view-binding stubs from the layouts
        //   would make the activities compilable offline and close this
        //   properly; it is the obvious next step for the toolchain.
        //
        // 1.19.0 — feature: 9x19 service pistols and factory loads.
        //
        //   FIREARMS. Beretta 92X Performance and Performance Defensive
        //   first, as asked, then 92X Full Size, Centurion, Compact and the
        //   92FS; Glock 17, 17L, 19, 19X, 26, 34, 43, 43X, 45, 47 and 48.
        //   Barrel lengths are the makers' published figures. Twist is 1 turn
        //   in 250 mm for both — 9.84 inches — which is what Beretta and
        //   Glock each publish for 9 mm. Zeroed at 25 m rather than the 10 m
        //   used for air or the 50 m for rimfire.
        //
        //   AMMUNITION. Fiocchi Range Dynamics in 115, 124 and 147 gr;
        //   Federal American Eagle, Syntech Range, Syntech Training Match,
        //   Syntech Action Pistol and HST; CCI Blazer Brass in 115, 124 and
        //   147 gr. Weights and muzzle velocities are the makers' published
        //   figures.
        //
        //   BALLISTIC COEFFICIENTS ARE NOT PUBLISHED by any of these three
        //   for pistol ammunition, so those are the conventional values for
        //   the bullet weight and shape. That is a real difference from the
        //   rest of the catalogue, whose BCs come from the maker, and it is
        //   recorded in the source rather than glossed over. It matters very
        //   little here — between BC 0.14 and 0.19 the drop at 25 m differs
        //   by well under a millimetre — and would matter at 100 m, where
        //   none of these loads belongs.
        //
        //   NOT a complete listing of every product line. Loads appear only
        //   where a published velocity could be found for that exact product;
        //   several defensive lines are left out rather than invented.
        //
        //   THREE DEFECTS THE NEW TESTS FOUND, all pre-existing:
        //     - A centrefire PISTOL whose model name lacked the word "pistol"
        //       was classed as a centrefire RIFLE, and would have taken a
        //       rifle's default sight height and zero.
        //     - Twist was displayed through toInt(), so 1 turn in 250 mm read
        //       as "1:9" — a rate nothing is rifled at. The CZ Shadow 2 and
        //       the Tanfoglio Stock II were already being shown wrongly.
        //     - The calibre regex, being case-insensitive, read "Glock 19X
        //       9x19" as calibre "19X 9": it found the model number, an
        //       upper-case X and the leading 9 before reaching the calibre.
        //
        // 1.18.1 — correction and feature: in-app capture actually exists
        //          now, and it is guarded.
        //
        //   THE ANSWER TO "DO YOU CAPTURE AT FULL RESOLUTION" WAS NO, BECAUSE
        //   THE APP DID NOT CAPTURE AT ALL. There was no ImageCapture use case
        //   and no VideoCapture use case; the only camera output was the
        //   analysis stream, and "Score the target now" read a preview frame
        //   from it. So there was nothing to set to 4K — 1.18.0's resolution
        //   selector governs that analysis stream, which was all there was.
        //
        //   ImageCapture is now bound at HIGHEST_AVAILABLE_STRATEGY and
        //   MAXIMIZE_QUALITY. A still comes off the sensor at its full size,
        //   commonly 12 megapixels against the analysis stream's 2 — some 2.4
        //   times the linear resolution on every hole, which are the smallest
        //   things this app measures.
        //
        //   TWO COUPLINGS THAT WOULD HAVE MADE THIS SILENTLY WRONG, both found
        //   before shipping and both worth recording:
        //
        //   1. The registration maps millimetres to ANALYSIS-frame pixels. A
        //      still has different dimensions, so putting one through that
        //      registration maps the wrong pixels — a plausible score computed
        //      from the wrong geometry. The registration is now rescaled for
        //      the still, and then CHECKED against the printed rings in that
        //      still before use; a failure falls back to the analysis frame
        //      and says so. Rescaling assumes the two streams frame the same
        //      scene, which is usual and not guaranteed, so it is verified
        //      rather than trusted.
        //
        //   2. LiveHitDetector rectifies the reference AND every later frame
        //      through one registration, and later frames arrive from the
        //      analysis stream. A full-resolution reference would therefore be
        //      differenced against frames it is not aligned with. The
        //      reference deliberately stays on the analysis stream, and "score
        //      now" only reaches for a photograph when there is no reference
        //      to align with.
        //
        //   Still not implemented: VIDEO capture. camera-video is on the
        //   classpath and unused. Recording, storage and frame extraction is a
        //   larger piece than belongs in this change, and the phone's own
        //   camera app records perfectly well for collecting test material.
        //
        // 1.18.0 — feature: the camera is held still, and its resolution
        //          can be chosen.
        //
        //   NEITHER WAS BEING DONE. Nothing in the app touched CameraControl,
        //   so exposure, white balance and focus ran free the whole time.
        //   For the differential path that is close to fatal: differencing
        //   assumes two frames of one card differ only where a shot arrived,
        //   and a camera that re-meters between them changes EVERY pixel by
        //   more than the 40 or so levels a hole is worth. Re-focusing is
        //   worse again — the lens moves, the field of view shifts slightly
        //   on nearly every phone, and the registration silently goes stale.
        //   Auto white balance moves the colour channel that hole detection
        //   is measured in.
        //
        //   All three are now locked, automatically, at the moment a card is
        //   registered — which is exactly when the framing has settled — and
        //   there is a button under the preview to release them when the
        //   light changes for real. Focus is locked by metering once with
        //   auto-cancel disabled rather than by switching AF off, which on
        //   many devices then needs the lens driven by hand and parks at
        //   infinity if you do not.
        //
        //   ANALYSIS RESOLUTION is selectable: 720p, 1080p, 1440p or 4K, with
        //   CameraX falling back to the nearest supported size. It was fixed
        //   at 1080p, so live scoring never saw more than that however good
        //   the phone. Changing it rebinds the camera and CLEARS the
        //   registration, because that was measured in the old frame's pixels.
        //
        //   Not changed: the photo path still imports from the gallery rather
        //   than capturing, so a still comes in at whatever the camera app
        //   produced, capped by ImageLoader at 3000 px on the long side.
        //
        // 1.17.0 — feature: face identification stabilised, and the two
        //          largest costs in registration removed.
        //
        //   T1 WAS THE LIMITING FACTOR, and measurably so: across six tilt
        //   angles of the SAME card the identified face changed up to four
        //   times. That matters more since 1.16.0, because the scale now uses
        //   the face's own black-to-pitch ratio.
        //
        //   The cause is not noise. Black radius over ring pitch CANNOT
        //   separate this catalogue at any precision — 4.00 for ISSF 25/50 m
        //   Precision Pistol, 4.00 for the German 100 m face, 4.01 for the
        //   NRA A-23/5; 6.10 for 10 m Air Rifle against 6.00 for 300 m Rifle.
        //   Those are the same shape at different sizes.
        //
        //   DISTANCE separates every one of those collisions, and the session
        //   has known its distance all along from the rule set. Candidates
        //   are now filtered by it before the ratio is compared, falling back
        //   to the whole catalogue if nothing survives — a face used at a
        //   distance it was not drawn for should give a worse answer, not no
        //   answer.
        //
        //   HYSTERESIS. A face already in use is kept unless a rival beats it
        //   by more than the margin the fitted pitch itself wanders by. That
        //   is what stops the answer flapping between frames of one target.
        //
        //   MEASURED, distinct faces chosen across six tilts of each card:
        //       card   before   after
        //       t01      1        1     (and 300 m -> 10 m, i.e. plausible)
        //       t02      2        2
        //       t03      4        1
        //       t04      3        2
        //   Ten identifications down to six, and no 300 m face proposed for a
        //   10 m session.
        //
        //   PERFORMANCE, first two items by expected gain. Registration was
        //   taking around 20 s a frame in the harness, which had begun to
        //   obstruct the accuracy work.
        //     - fitLadder called leastSquares INSIDE the innermost loop of an
        //       O(n^3) search for the aiming-mark test, sorting a map and
        //       allocating every time. The anchor already defines the line, so
        //       no fit is needed there; the least-squares runs once, on the
        //       winner.
        //     - The diagnostic second pass, which re-ran the whole fit without
        //       the shape correction purely so the log could compare, is now
        //       off by default.
        //   Measured over six frames: 591 ms each down to 335 ms, with an
        //   identical pitch on every frame — same answers, less work.
        //
        // 1.16.0 — feature: the scale is now measured two independent ways
        //          and cross-checked. Accuracy, not speed.
        //
        //   Every scoring error is proportional to millimetres-per-pixel, so
        //   it is the most consequential number the app measures. Until now
        //   it came from one source: the spacing of the fitted ring ladder.
        //   That is precise when the rings are found — 0 to 1.5 per cent on a
        //   square-on card — and it degrades badly when they are not.
        //
        //   The aiming mark gives a completely separate reading: its measured
        //   radius, divided by the black-to-pitch ratio the catalogue states
        //   for that face. One high-contrast boundary instead of a family of
        //   faint lines, and no ladder to lose.
        //
        //   MEASURED, four real cards warped by tilts of 0 to 25 degrees with
        //   the face held fixed, so this is scale consistency and not
        //   identification noise. Rotation about an axis through the centre
        //   leaves depth there unchanged, so the true scale is IDENTICAL at
        //   every angle and all of the spread is error:
        //
        //       card    ladder     mark    cross-check
        //       t01       2.7%     1.0%       1.6%
        //       t02      13.5%     1.1%       1.1%
        //       t03      21.8%     1.4%       2.9%
        //       t04      90.5%     1.9%       1.4%
        //
        //   And on absolute accuracy, measured offline against the two cards
        //   whose faces are known, the mark reading alone gave -0.8% and
        //   +0.7% against the true pitch — as good as the ladder at its best,
        //   from a single measurement with no search.
        //
        //   CROSS_CHECK is the default: within noise of the mark alone on
        //   consistency, and it additionally REPORTS when the two disagree,
        //   which on these images fired on 3 to 6 cases out of 6 and is
        //   usually the sign of a wrong face. When they agree to within 6 per
        //   cent their mean is used, because averaging two independent
        //   readings of comparable accuracy beats either.
        //
        //   Selectable under Settings > Detection algorithms, per the
        //   development rule that every new algorithm gets a switch until the
        //   accuracy is good enough to fix the choice.
        //
        //   WHAT THIS IS NOT. It needs the face, so it cannot identify one —
        //   used that way it would be circular, since identification already
        //   uses the mark. It verifies a face that has already been chosen.
        //
        //   NOT INTEGRATED, and why: template alignment by iterative
        //   optimisation (P2a). Six cost functions were prototyped offline
        //   and every one degraded a good seed — intensity correlation
        //   collapses onto the black; summed gradient locks the half-pitch
        //   harmonic (-49%, -47%); freeing the centre took one card from
        //   -4.3% to +27%; and refining against the exact known radii still
        //   shrank the scale by ~4% on both cards tested. The value in that
        //   line of work turned out to be the arithmetic seed above, not the
        //   optimisation.
        //
        //   KNOWN, and deferred on instruction: registration has become slow
        //   enough to obstruct measurement — around 20 s per frame at 800 px
        //   in the offline harness. The likely cause is the aiming-mark
        //   constraint added to fitLadder in 1.11.0, which calls a least
        //   squares fit inside the innermost loop of an O(n^3) search.
        //
        // 1.15.0 — feature: the interface follows the theme, and buttons
        //          are consistent.
        //   - 18 buttons across Import, Session, Targets, Rules and Settings
        //     had no style at all and were rendering at the platform's own
        //     size and in capitals, next to styled ones at 14sp. Every button
        //     in the app now carries a style, and all three button styles are
        //     14sp.
        //   - The status text over the viewfinder and over an imported photo
        //     was hardcoded #FFFFFF, and the registration overlay was a fixed
        //     gold box with white handles and white labels. All of it takes
        //     colorAccent now.
        //   - The crosshair follows the theme too. This one is not cosmetic:
        //     under night-red a white crosshair is the single brightest thing
        //     on a screen whose whole purpose is preserving dark adaptation,
        //     and it undoes what the theme is for.
        //   - Catalogue list rows lost the platform's listPreferredItemHeight
        //     minimum and half their vertical padding, so more of a long
        //     firearm or ammunition list fits on screen.
        //
        //   DELIBERATELY NOT THEMED, so it is not filed as a miss:
        //     - The target plot. Its colours are representational — paper
        //       #FAFAF5, ink #1A1A1A — because it depicts a real card. Tinting
        //       it red would make the drawing wrong rather than themed.
        //     - The detection markers on the registration overlay. They are a
        //       different colour from the box because they mean something
        //       different: what the app FOUND, as against what the user is
        //       placing. Recolouring them to the accent would lose that.
        //     - The dark scrims behind overlay text, which exist to make
        //       anything legible over a photograph of unknown brightness.
        //
        // 1.14.1 — correction: two presentation fixes, and a third static
        //          gate that found a real error while being written.
        //   - The Stage time and Notes fields on Results were on the
        //     platform's default EditText size, several points larger than
        //     the 12-13sp body text around them, so they read as headings.
        //     Both are 13sp now: with the body range, but not the smallest
        //     thing on the screen, which text being typed into should not be.
        //   - The selected target face on the Targets screen lists its
        //     dimensions in a two-column table, both columns left aligned,
        //     sharing item_param_row.xml with the rules screen. They ran
        //     inline as prose before — "Card 80 x 80 mm", "Aiming black 30.5
        //     mm" — so nothing lined up and the numbers could not be read
        //     down the column. The outer ring diameter is listed too; it was
        //     not shown at all, and it is the figure the scale depends on.
        //
        //   NEW GATE: android widget types used by simple name must be
        //   imported. Kotlin has no implicit android.* imports, and neither
        //   the view-binding gate nor the offline harness can see this — the
        //   activities are excluded from the harness because they need the
        //   whole framework to compile.
        //
        //   Written because this very change introduced one:
        //   findViewById<TextView> in TargetActivity, which had no TextView
        //   import. The first version of the gate passed it, because the file
        //   used android.widget.TextView fully qualified elsewhere and the
        //   gate treated that as sufficient. It is not — a qualified use does
        //   not make the bare name available. Corrected to look for
        //   SIMPLE-NAME uses specifically, which then reported it.
        //
        // 1.14.0 — feature: justified description text, and an aligned
        //          parameter table on the rules screen.
        //   - Sixteen descriptive paragraphs are now fully justified with
        //     android:justificationMode, across Home, Session, Import,
        //     Results and the two catalogue dialogs. Available unconditionally
        //     because minSdk is 26, which is the release it was added in.
        //     Applied to PROSE only: justification stretches inter-word
        //     spacing to reach the right margin, which on a two-word heading
        //     or a one-line field label reads as a mistake rather than as
        //     typesetting.
        //   - Rule parameters are a two-column table, both columns left
        //     aligned. They were lines of a single TextView with the names
        //     padded out with spaces, which only lines the values up in a
        //     monospaced font — the same defect the home screen's setup block
        //     had, and it wandered by several characters in the app's
        //     proportional face. Left rather than right alignment on the
        //     values on purpose: a ragged left edge is what stops a column
        //     being scannable.
        //   - The rows are INFLATED from item_param_row.xml rather than
        //     constructed in Kotlin. Passing a ContextThemeWrapper to a View
        //     constructor sets the theme used to resolve attributes; it does
        //     not apply the style's own attributes, so rows built that way
        //     compile, run, and render completely unstyled.
        //
        // 1.13.0 — feature: interface changes across Home, Session,
        //          Results, Targets, Settings and Import.
        //   - Home no longer carries the upload-photo and live-session
        //     buttons. Both remain reachable — live scoring is a bottom-nav
        //     tab and Import is linked from it — and removing the duplicates
        //     lifts the active setup, the one thing worth confirming before
        //     firing, back above the fold on a small phone.
        //   - The active setup is a two-column TABLE now. It was one TextView
        //     with the labels padded out with spaces, which only aligns the
        //     colons in a monospaced font; in the app's proportional face
        //     they wandered by several characters.
        //   - Session and Targets: every remaining flat text action is a real
        //     button. Results had none left.
        //   - Equal button heights in the Results "Shots" section and the
        //     Settings "Profile sets" section.
        //   - Import reopens showing the last photo scored rather than a
        //     black rectangle. Only the URI is stored, with durable read
        //     permission taken where the picker grants it, so nothing is
        //     duplicated; if the file has gone the screen simply starts empty.
        //
        //   BUG, and a silent one. On Session, currentFace() preferred a
        //   selectedFace field over the spinner, and the code that switches
        //   the spinner after identifying a target suppressed its own
        //   listener — which was the only thing that updated that field. So
        //   the screen showed the identified face while every score was
        //   computed against the previous one, with nothing to indicate it.
        //   The field is now set alongside the spinner, and the spinner
        //   outranks it, so what is displayed is what is scored.
        //
        //   NEW GATE: tools/kotlin_checks.py now verifies that every
        //   binding.<id> exists in that screen's layout, following <include>.
        //   View binding reports a missing id against the GENERATED class,
        //   some way from the layout edit that caused it, and renaming a
        //   control mid-interface-work is the usual way in. Written, found to
        //   catch NOTHING because it built the layout name as "activitymain"
        //   instead of "activity_main" and so skipped every file, then fixed
        //   and verified by deleting a control and referencing it again.
        //
        // 1.12.2 — correction: NameWrapTest asserted nothing and said so
        //          only in CI.
        //   - Under plain unit tests android.jar is stubbed and, with
        //     unitTests.isReturnDefaultValues = true, Paint.measureText
        //     returns 0.0f. The test measured with a real Paint, so every
        //     string "fitted", nothing was wrapped, and the logic under test
        //     never ran.
        //   - NameWrap.wrapAtDash now takes a PREDICATE, with the Paint form
        //     as a thin overload over it. The decision is pure string logic
        //     and only the measurement needs Android; keeping them apart is
        //     what makes the decision testable.
        //   - The offline harness's Paint stub measured text properly, which
        //     is why this passed there and failed here. It now returns 0.0f
        //     like the real one. Same lesson as the JUnit shim in 1.10.1: a
        //     stub more capable than the environment it stands in for hides
        //     exactly the failures it exists to catch. Verified by running
        //     the old formulation against the corrected stub — it now fails
        //     locally too.
        //
        // 1.12.1 — correction: seven interface changes, all reported.
        //   - The shot controls are now two rows of identically sized
        //     buttons. They were weighted with wrap_content heights, so a
        //     label that wrapped to two lines made its own button taller than
        //     its neighbours; labels are short now for the same reason.
        //   - DELETE for a detected shot. It acts on the SELECTED shot rather
        //     than the last one, because detection order has nothing to do
        //     with shooting order and "the last one" would be the wrong shot
        //     as often as not.
        //   - A centred crosshair over the viewfinder. Worth more than
        //     tidiness here: the flatter the card sits in frame, the less the
        //     ring fit has to correct, and residual perspective is the one
        //     error the scorer cannot fully undo.
        //   - Every remaining text action in Settings is a real button, and
        //     its section headings went from 16sp to 19sp — they were
        //     competing with the body text they introduce rather than
        //     standing above it.
        //   - Firearm, ammunition and sight lists break a name at its dash
        //     when it will not fit on one line, so the names line up down the
        //     left and the qualifiers sit underneath. Measured at draw time
        //     against the row's real width and the user's own font scale,
        //     and only broken when the break is actually needed.
        //   - The home screen shows only the part before the dash. A full
        //     catalogue label carries type, calibre, barrel and twist, which
        //     wrapped that aligned column into an unreadable block.
        //
        // 1.12.0 — feature and correction: the real reason hole detection
        //          "generally fails", plus the photo overlay and real buttons.
        //
        //   THE FACE WAS THE PROBLEM, NOT THE DETECTOR. The target face sets
        //   millimetres per pixel, the radius of the scoring area and which
        //   region counts as black. Register against the wrong face and the
        //   rectified card comes out at the wrong scale, so every hole falls
        //   outside the detector's size gates and NOTHING is found — with no
        //   error raised anywhere, because each stage did what it was told.
        //
        //   Measured on the two real cards supplied, absolute detection,
        //   ground truth five shots each:
        //        face used                       o03      o04
        //        identified from the picture     4        5
        //        ISSF 10 m Air Rifle forced      0        0
        //        ISSF 300 m Rifle forced        43       88
        //
        //   That is exactly the reported symptom: the one-button route
        //   identifies the face from the photograph, and registering by hand
        //   did not check it at all. Both routes now check, two ways:
        //     - a SCALE-FREE ratio, black width in ring widths, which can
        //       judge a face without first trusting it for the scale. Catches
        //       the gross case: 32 to 39 per cent out on the cards above.
        //     - ranking every catalogue face by the fitted ring pitch, which
        //       separates faces of similar proportions at different sizes.
        //   Five of six wrong faces are caught. The sixth is honest: on one
        //   card the right face fits to 2.86 per cent and ISSF 25/50 m
        //   Precision Pistol to 3.13, and ring proportions genuinely do not
        //   separate them.
        //
        //   ALSO FIXED: since the de-foreshortening went in at 1.10.0 every
        //   coordinate in a RingFit has been in the CORRECTED frame, and the
        //   registration overlay was drawing them straight onto the original
        //   photograph. The box and ring markers sat up to nine pixels off on
        //   a mildly angled card and further as the angle grew, so what the
        //   user saw disagreed with what had been registered — and nudging
        //   the box by hand started from the wrong place. That is very
        //   probably why adjusting the box by hand made things worse.
        //
        //   NEW: Results can show the hits on the SHOOTER'S OWN PHOTOGRAPH
        //   rather than only on the template. The photo is rectified onto the
        //   same millimetre grid as everything else, so tapping to add a shot
        //   and dragging one to a new place work over it unchanged. This is
        //   the only view in which a MISSED hole is visible at all: on the
        //   template an undetected hole leaves nothing behind to notice.
        //
        //   NEW: Add shot, Move shots, Show whole card, Reset view, Export
        //   CSV, Clear all shots and Finish session were flat coloured text
        //   and read as labels rather than controls. They are real buttons
        //   now. The same mistake had already been reported once, for the
        //   photo-import entry points.
        //
        // 1.11.0 — feature: the ring-pitch ladder now returns the same scale
        //          for the same target photographed at different angles.
        //
        //   The defect, from 1.10.1: pitch came back as 10.4, 36.1 and 7.6 px
        //   for one target at three tilts. Four causes, each measured:
        //
        //   1. ONE PERCENTILE CANNOT SEE EVERY RING. The radial profile was
        //      read at the 25th percentile only, which finds dark rings on
        //      light paper and is BLIND to the white rings printed inside the
        //      black aiming mark — where rings 7 to 10 live. On a real target
        //      the 25th-percentile profile read exactly 0 for every radius
        //      from 0 to 124 px: the whole mark, flat, with three rings in it.
        //      The profile is now read at 0.25, 0.50 and 0.90 from a single
        //      histogram and the candidates pooled.
        //
        //   2. INLIER_TOLERANCE 0.16 admitted a peak nearly 6 px off its rung.
        //      Residual perspective SPLITS an outer ring into two shoulder
        //      peaks, both were admitted, and the refit was dragged between
        //      them. Now 0.10.
        //
        //   3. NOTHING CROSS-CHECKED THE PITCH. The aiming mark is now used
        //      as independent evidence: its radius over the pitch must lie in
        //      2.4 to 8.6, which is the range spanned by every face in the
        //      catalogue (measured: 3.00 to 7.03). This is what rejects a
        //      ladder at half or twice the true pitch — one target was
        //      returning 18.8 px where the mark implied 37.
        //
        //   4. THE MARK ITSELF WAS NOT STABLE. MarkOutline took the first
        //      threshold that produced a compact blob, so the same card at
        //      two angles gave mark radii of 39.6 and 114.0 px. It now
        //      evaluates every threshold and keeps the largest compact
        //      region. It also CLOSES the dark mask first: the rings printed
        //      inside the mark are light lines that stop a flood fill dead,
        //      and on a synthetic face this returned the ten-ring disc
        //      instead of the mark. That had gone unnoticed only because thin
        //      anti-aliased lines on a photograph let the fill leak past.
        //
        //   MEASURED, on four real targets warped by angles chosen in advance.
        //   Rotation about an axis through the centre leaves depth unchanged
        //   there, so the true pitch is IDENTICAL at every tilt and any
        //   spread is error. Spread over 0-15 degrees, which is what a
        //   roughly square-on photograph produces:
        //
        //        target    before        after
        //        t01       (n/a)          7.0%
        //        t02       47.7%          9.2%
        //        t03       71.9%          7.7%
        //        t04      >100%           4.8%
        //
        //   A CHECKED ASSUMPTION THAT TURNED OUT TO BE FALSE, recorded
        //   because it nearly shipped: the black edge lands exactly on a ring
        //   boundary on ten of the twelve applicable catalogue faces, and on
        //   the ISSF 50 m Rifle face — and the German 50 m Kleinkaliber face
        //   that copies it — it sits 0.375 of a ring away by design. A hard
        //   rung constraint, which was written and working, would have made
        //   both unscoreable. It is now a confidence factor with a floor, and
        //   two tests pin the property so it cannot be re-assumed.
        //
        //   STILL OPEN. The remaining 5-9 per cent drifts monotonically with
        //   tilt, which points at a specific cause: the radial profile
        //   averages over ALL bearings, including those the residual
        //   perspective distorts, whereas along the tilt axis depth does not
        //   change at all and the scale there is exactly uniform. Restricting
        //   the profile to a wedge about the fitted major axis should remove
        //   it. Written and NOT shipped: it could not be measured properly
        //   inside this session, and an unvalidated change to the scale is
        //   the worst kind to guess at.
        //
        // 1.10.1 — correction: the unit test source set had never once been
        //          compiled, and failed the moment it could be.
        //   - RingFinderTest asserted assertEquals(0xFE, r.toInt(), 6): three
        //     Ints. JUnit's three-argument form is (double, double, double)
        //     and Kotlin does not widen Int to Double for overload
        //     resolution, so it matched nothing. Now doubles.
        //   - This was reachable only because 1.10.0 was the first release
        //     whose MAIN source compiled; every earlier CI run stopped at
        //     compileReleaseKotlin and never reached the tests.
        //   - tools/offline/run.sh compiles and runs the whole test source
        //     set without Gradle or the Android SDK, in about a minute, using
        //     the kotlin-compiler package from npm. All 110 tests pass.
        //     Its JUnit shim mirrors the real Assert overload set exactly;
        //     an earlier, more permissive shim accepted the very call CI
        //     rejected, which is how this reached CI at all.
        //
        // 1.10.0 — feature: direct least-squares ellipse fitting on the ring
        //          edges, and an evidence-based choice between it and a circle.
        //   - EllipseFit.kt implements Fitzgibbon/Pilu/Fisher (1999) in the
        //     numerically stable Halir-Flusser form. Fits all five ellipse
        //     parameters, where the pooled estimate in HoughCentre fits two
        //     and assumes three. Verified against an independent
        //     implementation: axis ratio and orientation agree to five
        //     decimal places on twelve reference cases.
        //   - RingShapeSelector picks between circle and ellipse by CROSS
        //     VALIDATION — fit both on 70% of the outline, score both on the
        //     untouched 30%. An ellipse always wins on the points it was
        //     fitted to, so residuals alone would pick it even on a perfectly
        //     square-on target, where a spurious 3% correction is about half
        //     a ring at the outer edge.
        //   - MarkOutline extracts the aiming mark as a connected component
        //     and checks it has not leaked along a ring line through a bullet
        //     hole; two ray-casting extractors were tried first and both
        //     invented ellipticity that was not there.
        //   - ShapeCorrection de-foreshortens the frame BEFORE the ring pitch
        //     is measured, so the existing radial fit gets an image its
        //     circular assumption holds for.
        //
        //   Measured, on real targets warped by angles chosen in advance —
        //   mean absolute score error per shot, ISSF 10 m air rifle face:
        //     tilt      circle   ellipse
        //     10 deg     0.092     0.083
        //     20 deg     0.201     0.154
        //     30 deg     0.366     0.216
        //     40 deg     0.565     0.282
        //   So it recovers roughly half of what a circle discards at 30-40
        //   degrees. A full ring-family homography would recover about a
        //   further quarter and is NOT implemented, because it needs the true
        //   ring radii and would therefore turn a wrong face identification
        //   into a confident wrong score. This needs no face knowledge.
        //
        //   KNOWN OPEN DEFECT, surfaced by this work and NOT caused by it:
        //   the ring-pitch ladder in RingFinder is unstable on oblique
        //   photographs, returning 10.4, 36.1 and 7.6 px for the same target
        //   at three tilts WITHOUT any correction applied. The uncorrected
        //   fit is now logged alongside the corrected one so the two can be
        //   compared from a shared field log. Scale should not be trusted on
        //   an angled photograph until that is fixed.
        //
        // 1.9.1 — correction: four compile errors from the 1.6.0-1.9.0 run of
        //         releases, none of which had been through a compiler.
        //   - SessionActivity had TWO companion objects, which made
        //     IDENTIFY_TOLERANCE unresolvable and reported the error on a
        //     different line than the cause.
        //   - ProfileActivity kept a return@onSelected after the function it
        //     labelled was renamed to onSelectedIndex.
        //   - the ported VTB ScopeCatalog's `when` covers VTB's three click
        //     units; this app has six, because match diopters are quoted in
        //     millimetres at a distance rather than as an angle.
        //   tools/kotlin_checks.py now catches all three, and CI runs it
        //   BEFORE the compiler, since it names the file and line where the
        //   compiler reports consequences elsewhere.
        // 1.9.0 — the two gaps from 1.8.0, and Hough centre voting.
        //   - the target spinners on the detection screens now show a picture
        //     of each face. 1.8.0 put thumbnails only in the Targets tab,
        //     which is where a face is browsed; the spinner is where one is
        //     CHOSEN, moments before scoring against it, and that is the half
        //     that mattered.
        //   - live camera detection uses the colour channel too, computed in
        //     YUV from the frame's own median. 1.8.0 left it on luminance,
        //     which was a defensible decision taken silently.
        //   - HoughCentre: every edge on a ring has a normal pointing at the
        //     common centre, so each votes along it. Measured on four real
        //     targets, within 2-4 px every time, and unlike a symmetry search
        //     it survives a thumb over a corner or a target filling part of
        //     the frame. It now seeds RingFinder.
        //
        //   HONEST LIMIT, since Hough was asked for to handle ANGLED targets
        //   and does not by itself: under perspective a ring projects to an
        //   ELLIPSE, which a circle accumulator has no parameter for, and a
        //   five-dimensional ellipse Hough is not a phone computation. What is
        //   affordable is measuring the ellipticity, pooled across every ring
        //   — and on four real targets that sits at a ~4 degree noise floor:
        //   3.1 and 3.9 degrees on targets that are perfect circles, against
        //   5.1 on a genuinely angled photograph. So it is reported and it
        //   seeds the tilt sliders above 8 degrees, and it is never applied
        //   on its own.
        // 1.8.0 — five features, and the first two change how registration
        //         works rather than tuning it.
        //   COLOUR. Hole detection reads distance from THE PAPER'S OWN COLOUR
        //     rather than brightness. A pellet hole is brown, not merely dark:
        //     on a real card that doubles its separation from the paper, 115
        //     luma levels against 242. Measuring the paper rather than
        //     assuming it is neutral matters — an ISSF card is yellow, and
        //     the obvious "brightness minus chroma" channel sends its paper to
        //     black and every hole with it.
        //   RING FITTING. The scale now comes from the PRINTED RING SPACING,
        //     fitted across the whole family and refined by least squares,
        //     instead of from the aiming mark times a ratio taken from
        //     whichever face was selected in a menu. Measured on four real
        //     targets: pitch to within 0.0-1.5%, where the aiming-mark ratio
        //     was out by 6%. The fit also IDENTIFIES the face — the right one
        //     agreed to 0.3-1.3% while the runner-up was 8% or worse — which
        //     removes the wrong-face failure at its source.
        //   Editing: shots can be added, deleted and DRAGGED, and are
        //     rescored where they are dropped.
        //   Catalogues: every VTB rifle, load and sight ported verbatim, with
        //     VTB's filter dialogs, plus the match sights and pistols STS
        //     needs. 41 firearms, 68 loads, 51 sights.
        //   Thumbnails: a drawing of each face beside its name in the picker.
        // 1.7.1 — three corrections, two of which had one cause.
        //   - the black text was NOT the spinners (1.7.0 fixed those); it was
        //     all 35 BORDERLESS BUTTONS. "?android:attr/borderlessButtonStyle"
        //     takes the platform style, whose text colour comes from platform
        //     attributes this app cannot reach. Replaced with Sts.TextButton,
        //     which sets textColor on the view's own style.
        //   - a Spinner delivers its first onItemSelected on the layout pass
        //     AFTER onCreate, i.e. after the listener is attached. Opening
        //     Session or Import therefore ran the rules listener, which forces
        //     the target face to the rule set's default — silently reverting a
        //     chosen target to ISSF 10 m Air Rifle every time. That is why the
        //     Results screen kept naming that face AND why detection kept
        //     finding nothing: it was running against the wrong geometry. The
        //     initial selection is now recognised and ignored.
        //   - an EMPTY session now follows the current target and rules.
        //     A scored session still keeps what it was scored with.
        // 1.7.0 — feature: clear the log (the button existed but four controls
        //         in one row pushed it off the right-hand edge of a phone, so
        //         the toolbar is now two rows); clear the recorded shots,
        //         from Results, Import and Session, keeping the target, rules
        //         and distance so the same card can be re-scored.
        //       — correction: EVERY SPINNER IN THE APP rendered black text on
        //         the dark background. android.R.layout.simple_spinner_item
        //         applies textAppearanceMediumInverse — coloured for the
        //         inverse of the theme — and no app style can override it
        //         because the colour comes from the platform text appearance,
        //         not from textColor. Replaced with our own item layouts.
        //       — correction: a photograph that detected nothing left the
        //         previous session untouched, so Results went on showing an
        //         older target's shots as though they were the new ones. The
        //         session is now replaced BEFORE detection runs.
        //       — correction: TargetGeometryCheck.verifyRings catches the
        //         other half of the wrong-face problem. The outer-radius test
        //         catches a box that is too small; this one catches a box in
        //         exactly the right place on a face whose RING SPACING does
        //         not match the card, which scores everything wrongly while
        //         looking entirely normal.
        // 1.6.0 — feature: a diagnostic log reachable from the Home screen,
        //         hideable in Settings, with a Report button that shares the
        //         log TOGETHER with the active face, rules, gauge and
        //         equipment. Detection now logs its own parameters and every
        //         candidate it accepted or rejected.
        //       — correction: the app no longer stays silent when the selected
        //         target face does not match the target in the picture.
        //         TargetGeometryCheck looks for printed rings outside the
        //         proposed registration box; if they are there, the box is too
        //         small and the wrong face is selected. Photographing an
        //         air-PISTOL card with the air RIFLE face chosen put the box
        //         on the FIVE ring (188 px against the ring's 194 px), halved
        //         every distance, scored the centre shot a 10 and every other
        //         shot a miss — a complete, confident, wrong score sheet from
        //         a wrong menu selection.
        //       — correction: the scoring-area limit went from 1.02x to 1.10x
        //         the outer ring, so a shot just outside the last ring is
        //         reported as the miss it is. Card furniture sits much further
        //         out; the logo that prompted the limit was at 1.21x.
        // 1.5.3 — corrections from running the detector against two real
        //         uploaded targets rather than synthetic data:
        //   - printed ring NUMERALS were being scored as shots. Radial
        //     normalisation removes rings but not numerals, which occupy four
        //     angles out of 360 and barely move a radial median. A synthetic
        //     face returned 22 candidates for 5 real shots, 17 of them digits.
        //     Now rejected by testing each candidate for rotational twins.
        //     A four-fold median over the whole image was tried first and
        //     LOST two of five real shots on a photograph — see the note in
        //     HoleDetector for why the per-candidate form survives real
        //     lighting and registration error where the global one does not.
        //   - absolute detection now looks only inside the outermost ring.
        //     A club logo on a real target was reported as a shot, and the
        //     card furniture was raising the noise floor: excluding it
        //     dropped the robust sigma from 4.4 to 3.0 on that photograph,
        //     which was enough to find a faint fifth hole that had been
        //     missed.
        // 1.5.2 — corrections for three failures found in the field:
        //   (1) auto-detect skewed the box on square-on targets. It was
        //       APPLYING a tilt inferred from how elliptical the aiming mark
        //       measured, and a shot-up mark measures a few percent
        //       elliptical from segmentation noise alone — which acos turns
        //       into 14 to 25 degrees. The estimate is now offered on a
        //       button, never imposed. Mark detection also gained a
        //       centrality weighting and a central-crop retry, so a dark
        //       bench behind a white card no longer wins the histogram.
        //   (2) ring numerals never appeared in the targets database: the
        //       gate demanded ~60 px of annulus against the 33 px a 230 dp
        //       preview actually has, on every face at every density. The
        //       glyph now scales to the annulus instead.
        //   (3) hit detection bore no resemblance to reality. Two causes:
        //       out-of-frame pixels were averaged into the contrast windows,
        //       inventing holes around the rim wherever the photo did not
        //       cover the whole card; and nothing suppressed the printed
        //       rings, which carry FOUR TIMES the contrast of a real hole.
        //       Fixed by a validity-masked integral image and by subtracting
        //       the radial median, which removes everything rotationally
        //       symmetric — every ring, exactly — and leaves the holes.
        // 1.5.1 — correction: the first real CI run compiled everything and
        //         passed 75 of 76 tests. The failure was the TEST, not the
        //         app: it asserted that a 10 degree tilt is recovered, but
        //         suggestedTransform deliberately ignores anything under 11.4
        //         degrees. That gate is now the named, documented constant
        //         MIN_ELLIPTICITY_TO_SUGGEST instead of a bare 1.02, and the
        //         test asserts the behaviour that exists.
        // 1.5.0 — feature: individual TILT and ROTATION controls on the
        //         registration box, laid out the way a phone camera app lays
        //         them out. Box plus these is seven degrees of freedom, which
        //         is every one a flat target through a normal lens needs; the
        //         eighth, shear, cannot arise with square pixels. The tilt is
        //         seeded from the ellipticity of the detected aiming mark.
        // 1.4.0 — feature: registration by a square bounding box with
        //         draggable top-left and bottom-right handles, placed
        //         automatically from the detected black aiming mark and
        //         expanded to the whole scoring area by the face's own
        //         published ratio. Corner registration stays available for
        //         angled views, and the detector says when it is needed.
        //       — feature: ring values printed on the target plot at all four
        //         cardinal points, so the targets database shows scores on
        //         the circles.
        // 1.3.1 — correction: the photo-upload scoring entry points existed
        //         since 1.2.0 but were styled borderless — flat text under a
        //         filled button — and users could not find them. Both are now
        //         ordinary buttons, the Home one comes FIRST, and both read
        //         "Upload a target photo to score".
        // 1.3.0 — feature: Play Store listing material — short and full
        //         descriptions and the 1024x500 feature graphic, in play/.
        //         The graphic is generated from the SAME drawing as the icon
        //         (tools/generate_feature_graphic.py imports the icon's
        //         artwork), so the listing and the home screen cannot drift.
        // 1.2.1 — correction: applicationId is now com.STS, and CI builds
        //         release artefacts only (no debug APK). Signing degrades to
        //         unsigned rather than failing when no keystore is present.
        // 1.2.0 — feature: score a target from a photograph after the session
        //         (ImportActivity), and a shot distribution histogram shown on
        //         Results, live on Session, on the import screen and in the
        //         shared report.
        //       — correction found while writing its tests: ImageLoader's
        //         sample size used the common idiom that stops one step early
        //         and returns an image LARGER than the requested cap, so a
        //         50 MP photo decoded at 4080 px instead of 3000.
        // 1.1.0 — feature: real launcher and Play Store icon, generated from
        //         one drawing (tools/generate_icons.py) into the 512 px store
        //         asset, the adaptive foreground at five densities, and the
        //         Android 13+ monochrome layer.
        // 1.0.1 — correction: removed res/mipmap-hdpi/README.txt, which the
        //         resource merger rejects (res accepts only .xml and .png).
        versionCode = 43
        versionName = "1.25.0"
    }

    // Resolved once, here, rather than re-read from the environment in two
    // places. CI sets these variables unconditionally, so an absent secret
    // arrives as an EMPTY STRING and not as null — and file("") resolves to
    // the project directory, which would be accepted as a keystore path and
    // then fail deep inside the signing task with an unhelpful message. Blank
    // and missing-on-disk are both treated as "no keystore".
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Kept for local work in Android Studio. CI never builds it: the
        // workflow runs the unit tests against the RELEASE variant and
        // assembles only the release APK and the Play bundle, so nothing that
        // leaves the build machine is ever a debug artefact.
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 ON per Play's optimisation recommendation: it strips
            // unreachable library code and emits the mapping file Play asks
            // for (AGP packages that into the bundle automatically).
            //
            // SAFETY: proguard-rules.pro keeps ALL com.rfsat.sts classes and
            // members. Every persisted format in this app is Gson reflection
            // over field NAMES, so renaming a field silently changes a stored
            // JSON key — no crash, no build error, just vanished profiles,
            // targets and sessions.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // CameraX ships native code (libimage_processing_util_jni.so).
            // This packages its symbol table into the AAB metadata, where
            // Play picks it up; metadata only, not shipped to devices.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Unsigned when no keystore is configured: the build still
            // succeeds and still produces both artefacts, which is what makes
            // the workflow useful on a fork or a pull request. An unsigned
            // bundle cannot be uploaded to Play, and the workflow says so
            // rather than leaving it to be discovered at upload time.
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX — live target observation and still capture
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Profile / target / session persistence
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
