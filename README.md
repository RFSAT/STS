# STS — Shooting Target Scorer

An Android application for scoring shooting on paper and steel, during
competition and during training. Built on the same structure, toolchain and
visual language as **VTB Vapor-Trail Ballistics**: the same four-theme
`BaseActivity`, the same bottom-tab navigation with swipe, the same
Gson-in-SharedPreferences persistence, the same crash-safe startup, and a
profile system that is deliberately field-compatible with VTB and DBM.

- `applicationId` — `com.STS` (permanent once published)
- Kotlin namespace — `com.rfsat.sts`
- AGP 8.9.1 / Kotlin 2.1.0 / compileSdk 36 / minSdk 26 / targetSdk 36
- Gradle 8.11.1 or newer, JDK 17

Open the folder in Android Studio and build.

`.github/workflows/android-ci.yml` is **release only** — it never assembles a
debug artefact. It runs the unit tests against the release variant, then
builds the release APK and the Android App Bundle for Play, and uploads both
along with the R8 mapping file.

Configure four repository secrets and the artefacts come out signed and
uploadable:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Without them the build still succeeds and still produces both artefacts,
unsigned — useful on a fork or a pull request, useless for Play. The run
summary states which of the two you got, rather than leaving it to be
discovered at upload time. The keystore is deleted from the runner
immediately after the build, whether or not it succeeded.

Keep the R8 mapping file. R8 rewrites the release build, so a crash report
from it is unreadable without `mapping.txt`; Play accepts it directly, and
the per-build artefact keeps an old release diagnosable after the next one
has overwritten what Play holds.

---

## What it does

1. **Register the target.** Tap the four corners of the card in the
   viewfinder. That fits a projective homography from target-plane
   millimetres to image pixels, and everything downstream works in
   millimetres.
2. **Capture a clean reference.** One frame of the unshot target.
3. **Shoot.** Each new hole is found by differencing against the reference,
   scored against the ring or zone geometry of the selected face under the
   conventions of the selected rule set, and added to the running total.
4. **Read the correction.** The Results screen reports the group centre and
   dispersion, and converts the centre's offset into clicks on the sight in
   the active profile set — or into a rear-sight movement in millimetres for
   iron sights that have no clicks.

### Four ways to record a shot

| Mode | When | Accuracy |
|---|---|---|
| **Live** | camera stays on the target for the string | best — immune to printed rings, paper texture and the aiming mark |
| **From a photograph** | the relay is over and the card is in your hand | best with a clean "before" photo, since scoring is then a difference; good without one |
| **Single frame** | scoring the target in front of the camera right now | as above, from the live feed rather than the gallery |
| **By hand** | anything the detector got wrong | authoritative — tap the plot on the Results screen |

### Scoring a photograph

Home or Session → *Upload a target photo to score*. Pick the photograph; the
app finds the black centre and draws a square box around the scoring area,
which you check and adjust by dragging its handles. Then it finds the holes
and scores them.
Supplying a second photograph of the same target **before** it was shot is
worth the trouble: scoring then becomes a difference, and the printed rings,
the paper grain, the staple shadows and the black aiming mark all cancel
because they are in both images. Without one the detector has to separate
holes from printing on scale and shape alone — it does a respectable job, but
its characteristic failure is a printed feature counted as a shot, which
looks exactly like a real detection on the plot. The screen says which mode
it is in, every time.

Photographs are loaded EXIF-aware and subsampled to 3000 px on the long edge.
Orientation matters more than it looks: a phone records the sensor readout
plus a tag saying which way up it was, so a portrait photo decodes landscape,
registration still succeeds, every score comes out right, and the plot
arrives on its side.

### Shot distribution

A total says what happened; the distribution says what kind of shooting
produced it. 95 as ten 9s and 10s is a shooter who needs a sight correction;
the same 95 as eight 10s and two 7s is a shooter throwing the occasional
flyer. One is fixed with a turret and the other with the shot process, and
the total alone cannot tell them apart.

The histogram appears on Results, live on the Session screen as the string
builds, on the import screen after detection, and as a fixed-width bar chart
in the shared text report. Shots are bucketed by the **whole number** of
points scored, because "how many tens did I shoot" is the question people
ask; the mean and SD are computed from the true values, so in a decimal
discipline a card of 10.9s and one of 10.1s share a bucket and are separated
by the mean. Rings nobody hit keep their bar — dropping the empty ones hides
the shape of the distribution, which is the only thing the chart is for.

### Frame sources

- The phone's own camera, through CameraX at 1920×1080 analysis resolution.
- **MJPEG over HTTP** — IP cameras, action cameras, phone-as-webcam apps.
  Implemented with no dependency, and the frame parser keys on the JPEG SOI
  and EOI markers rather than trusting the multipart boundary.
- **RTSP** — digital scopes that stream over their own Wi-Fi, via the
  platform MediaPlayer decoding into a TextureView. Higher latency and a
  narrow codec set; prefer MJPEG where a device offers both.

---

## Coverage

**Disciplines.** Air rifle and air pistol at 10 m; rimfire rifle and pistol
at 25 m and 50 m; centrefire rifle in .223, .308 and the 6.5 mm class out to
1000 yards; practical pistol.

**Target faces (21 built in).** ISSF 10 m air rifle and air pistol, 50 m
rifle, 25/50 m precision pistol, 25 m rapid fire, 300 m rifle; NRA/CMP SR,
MR-1 and LR high power, A-17 and A-23/5 smallbore; F-Class 600 and 1000 yd;
DSB/BDS 100 m and 50 m; IPSC Classic and Mini; IDPA; three steel plates.
Plus your own, from a photograph.

**Rule sets (31 built in).** ISSF qualification and final courses; NRA
National Match Course and smallbore prone; CMP Games; F-Class; NRL22 and PRS
stages; IPSC Comstock and IDPA; PZSS classification courses; DSB and BDS
including Auflage; and two training modes with no shot or time limit.

**Profile sets.** Firearms, loads and sights, seeded with six ready-made
combinations. The classes keep the VTB/DBM field names (`RifleProfile`,
`barrelLengthIn`, `zeroDistanceM` …) so a set exported from either app
imports here unchanged — see the note at the top of `RifleProfile.kt`.

---

## Two things to know before quoting a score

**1. Not every dimension in the app carries the same authority.**
`TargetFace.verified` and `RuleSet.verified` are `true` only for the ISSF
entries, whose geometry is fixed by a published dimension table and whose
courses of fire carry a rule reference. Everything else — the American
faces, F-Class, the practical silhouettes, the national federation courses —
is the commonly published figure. The app marks those with a warning symbol
in the pickers, repeats the warning on the Results screen and in the shared
report, and lets you edit every number.

**2. Editing never rewrites history.** A built-in face or rule set is
read-only; "Copy & edit" produces a custom entry under a new id. Sessions
record the id they were scored against, so correcting a ring diameter today
cannot silently change what last month's session claims to have measured.

---

## How the scoring works

The derivations are written out in the source rather than left implicit —
`TargetFace.kt` for the geometry, `CorrectionCalculator.kt` for the sight
advice. In brief:

**The touch rule.** A shot takes the highest ring its hole touches or
breaks, so with `d` the hole-centre distance, `c` the gauge radius and `R_v`
the printed ring radius, the value is the highest `v` with `(d − c) ≤ R_v`.

**The gauge is the rulebook's, not the bullet's.** ISSF specifies 4.5 mm for
air, 5.6 mm for .22, and 7.62/7.65/9.65 mm for the centrefire classes. A
.223 bullet measures 5.69 mm but is gauged at 7.62 mm, and scoring it at the
bullet diameter costs the shooter points on every marginal call. The engine
reads `RuleSet.gaugeDiameterMm` and never the bullet.

**Decimal scoring** falls out of one relation, `V(d) = 10.0 − (d − d₀)/s`
with `d₀ = R₁₀ + c` and `s` the ring pitch, truncated to a tenth and capped
at 10.9. It reproduces the published inner-ten tolerances — 0.25 mm on air
rifle, 0.8 mm on air pistol and 50 m rifle — which is the check that the
derivation is right and not merely self-consistent. Faces whose rings are
not evenly pitched (every American high-power face) return `null` rather
than a fabricated tenth, and the engine falls back to integers with a
warning.

**The correction** is pure geometry: a milliradian subtends a millimetre per
metre, so a group `x` mm off at `D` m is `x/D` mrad off, and the click count
is that over `ScopeProfile.clickMrad`. It centres the group *at the distance
it was shot* and warns when that is not the zero distance, rather than
silently folding in a drop difference nobody asked for. It also refuses to
be confident about a three-shot group, and says when the offset is inside
its own uncertainty.

**Match iron sights** are why `ClickUnit` gained `MM_AT_REFERENCE`. Diopter
and target-pistol sight makers publish "2 mm at 10 m", not an angle. The
profile stores their two numbers verbatim and the app converts once, at the
point of use.

---

## Detection, and its limits

Detection runs on a **rectified** image — the face resampled fronto-parallel
at a fixed millimetres-per-pixel — so a hole is the same size in pixels
everywhere, before/after frames subtract pixel for pixel, and the ring test
stays a distance comparison instead of a conic one.

Four rules turn detections into shots: a candidate must persist across
several frames, stay put while it does, survive a veto on frames where too
much changed at once (the target was bumped, or the sun came out), and not
coincide with a shot already accepted. Optional acoustic gating lets a
candidate arriving just after a report be believed on weaker evidence — a
real gain outdoors, and off by default because on a busy indoor range most
reports are not yours.

**What it cannot do.** It cannot see a shot that missed the target
altogether. It cannot read ring *values* off a photograph, which is why a
user-supplied face still needs its two dimensions typed in. Below about
three pixels per hole nothing will work, and the registration step says so
up front rather than producing a plausible wrong answer. Every detection is
overridable by hand, and low-confidence ones are drawn hollow on the plot so
a doubtful shot looks doubtful.

---

## Verification

`app/src/test/` holds ~40 unit tests over the parts that are pure
arithmetic — ring tables against the published ISSF dimensions, the touch
rule at its boundaries, the decimal derivation against the published
inner-ten tolerances, catalogue integrity (every rule set names a face that
exists; no duplicate ids), homography round-trip and degenerate-input
rejection, group statistics, and the click conversions for mil, MOA and
mm-at-distance sights. `gradle testDebugUnitTest` runs them; CI runs them on
every push.

## Before release

- Verify the non-ISSF target dimensions and courses of fire against the
  rulebooks you actually shoot under, and correct them in the catalogue.
- Field-test detection at each discipline's distance and lighting; the
  thresholds in `HoleDetector` are conservative starting points.
- Generate a release keystore and set `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`
  as repository secrets. The `applicationId` is permanent once published.
- Play listing: the app records and analyses camera and microphone input
  entirely on the device and sends nothing anywhere, which is worth stating
  plainly in the data-safety form.

---

## Play Store listing

Everything the console asks for lives in `play/`:

| File | What it is |
|---|---|
| `play_store_icon.png` | 512x512 app icon, full bleed |
| `feature_graphic.png` | 1024x500 feature graphic, RGB with no alpha |
| `short_description.txt` | 79 of the 80 characters allowed |
| `full_description.txt` | 3742 of the 4000 characters allowed |

`tools/generate_feature_graphic.py` imports the icon's own drawing rather
than redrawing a target, so the banner and the home screen cannot drift
apart. It also asserts its own layout before rendering — that the text
cannot reach the artwork and that nothing strays into the edge band Play
crops — because a collision is invisible in code and obvious in the store.

Two traps worth knowing if you revise the graphic. Play **rejects a feature
graphic with an alpha channel**, so the surface is `FORMAT_RGB24`, which has
none by construction rather than by remembering to flatten. And the icon
artwork knocks its bullet holes out with `OPERATOR_CLEAR` — on a surface
with no alpha, "clear" means BLACK, not transparent, so the artwork is
composited from an ARGB32 surface instead of being drawn straight onto the
background. Drawing it directly puts a heavy black ring around every hole.

The histogram in the graphic is an illustrative motif, not real data.

## Versioning and packaging

`<brand>.<major>.<minor>` — the same scheme VTB uses.

| Component | When it changes |
|---|---|
| **brand** | never; `1` = STS |
| **major** | a feature is added (minor resets to 0) |
| **minor** | a correction is made |

`versionCode` increments on **every** build that leaves the development
machine. Play rejects a bundle whose code is not strictly greater than the
last uploaded one, and a code reused during testing cannot be told apart
afterwards.

Each release ships as a **single ZIP** holding the whole project —
`STS_v<brand>_<major>_<minor>.zip` — with nothing loose beside it.

### Changelog

**1.9.0** — the two gaps from 1.8.0, and Hough centre voting.

*Thumbnails where they matter.* 1.8.0 drew a face beside each name in the
Targets tab. That is where a face is browsed; the SPINNER on the detection
screens is where one is chosen, moments before registering and scoring
against it, and choosing wrongly has silently rescaled whole score sheets.
Both spinners now show the picture, in the closed state and the dropdown, with
ring pitch and black diameter in the dropdown — which is what actually
separates two faces that are both black circles with rings on them.

*Colour on the live path.* 1.8.0 left live camera detection on luminance. The
reasoning was that differencing against a reference cancels the paper anyway,
which is true but was a decision taken silently against a request with no
such qualification. Live frames now use the same distance-from-paper channel,
computed in YUV from the frame's own median, with chroma weighted above
luminance: paper varies in brightness across a frame as corners fall into
shadow, but it barely varies in hue.

*Hough centre voting.* Every edge on a printed ring has a normal pointing at
the common centre, so each edge votes along its own normal and the centre is
where the votes pile up. On the four test targets it landed within 2 to 4
pixels every time, and because it works from edges it survives a thumb across
a corner, a club logo, or a target filling only part of the frame — all of
which mislead a symmetry search that assumes the target dominates the picture.
It now seeds `RingFinder`, which still measures the pitch from the radial
profile because it does that better: 0.0–1.5% against 0.9–3.8% for a radius
histogram off the same edges.

**On Hough and angled targets, which is what it was asked for.** It does not
solve that by itself, and it is worth being plain about why. Under perspective
a ring projects to an ELLIPSE, and a circle accumulator has no parameter for
one; a full ellipse Hough needs a five-dimensional accumulator and is not a
phone computation. What IS affordable is measuring the ellipticity directly.
All the rings on a card share one projection, so `r(θ,k) = R_k·g(θ)` — take
logs, subtract each ring's own mean, and every edge point on every ring
becomes a sample of the same `g(θ)`, thousands of samples for two parameters.
Fitting each ring separately was tried first and sat at 5–8 degrees of error
on targets that were perfect circles.

Pooling brought that to about 4 degrees, and that is still the floor: the two
synthetic targets, which are exactly circular, measured 3.1 and 3.9 degrees,
while the genuinely angled photograph measured 5.1. The measurement therefore
cannot separate a real 5-degree tilt from a perfect circle. It is reported,
and above 8 degrees it seeds the tilt sliders, and it is never applied on its
own — the same policy as the aiming mark's ellipticity, for the same reason.
For a target angled enough to matter, corner registration remains exact where
all of this is approximate.

**1.8.0** — colour, ring fitting, hole editing, the full VTB catalogues, and
target thumbnails.

*Colour.* Hole detection now reads how far each pixel is from **the paper's
own colour** rather than how dark it is. A pellet hole is brown, not merely
dark: measured on a club target, holes came through at 115 luma levels of
separation and 242 in this channel. The paper colour is MEASURED — the
per-channel median, which on any target is the card — because assuming it is
neutral is wrong in a way that destroys the image: an ISSF 10 m card is
yellow, chroma 121, so the obvious "brightness minus chroma" channel sends the
paper itself to black and takes every hole down with it. That was caught by
testing the four targets before shipping it, not after.

*Ring fitting, which is the bigger change.* Every registration failure this
app has had came from deriving the scale from ONE feature — the aiming mark —
multiplied by a ratio taken from whichever face was selected in a menu. A
competition target is a family of concentric, evenly spaced rings, and that
spacing IS the scale. `RingFinder` locates the centre by rotational symmetry,
reads the ring radii off a radial profile, fits `r_k = r0 + k·pitch` and
refits by least squares over the whole ladder.

Measured on the four test targets: pitch recovered to within **0.0–1.5%**,
against 6% for the aiming-mark ratio. And because the pitch is a property of
the card rather than of the menu, it also says WHICH card: the correct face
agreed to 0.3–1.3% while the runner-up was 8% or worse, a margin wide enough
to act on. *Identify target and register* does both in one step.

Two things worth keeping if this is revised. The ladder fitter prefers the
LARGEST pitch that explains the data densely — each printed line has two
edges, so a half-pitch ladder fits every real ring plus every spurious edge
and wins on inlier count alone; an earlier version returned exactly half the
true pitch on two of four targets. And the method assumes the rings are
circles, which a scan or a square-on photograph satisfies and an angled one
does not; the confidence figure falls when the ladder fits badly and the app
falls back to the aiming mark.

*Editing.* Shots can be added by tapping, deleted, and now DRAGGED. A moved
shot is put back through the scoring engine at its new position, so the plot
and the total cannot disagree.

*Catalogues.* Every VTB rifle, ammunition and sight entry ported verbatim
with VTB's filter dialogs — brand and type for a firearm; manufacturer,
calibre, velocity class, weight and bullet type for a load; brand, click
value, magnification class and family for a sight — plus the match diopters,
target-pistol sights and competition pistols STS needs and VTB has no reason
to carry. 41 firearms, 68 loads, 51 sights. The ported blocks are kept
verbatim and the additions appended below them, so the next VTB revision can
be dropped in by replacing the entry list alone.

*Thumbnails.* Each face in the picker is drawn beside its name. "ISSF 25/50 m
Precision Pistol" and "ISSF 10 m Air Pistol" are both black circles with
rings, both plausible, and picking the wrong one silently rescales every
score — a picture settles it at the point the mistake is cheapest to catch.
Drawn from the face geometry rather than shipped as assets, so a custom
target the user adds gets one too.

**1.7.1** — three corrections, and two of them had a single cause.

*The black text was not the spinners.* 1.7.0 fixed those, and they did need
fixing, but the controls still unreadable were the **35 borderless buttons** —
the log and resume actions on Home, everything under Elsewhere in Settings,
every undo, reset and clear elsewhere. `?android:attr/borderlessButtonStyle`
selects the PLATFORM style, whose text colour comes from platform attributes
that nothing in this app's themes can reach. Replaced with `Sts.TextButton`,
which sets `textColor` on the style applied directly to the view — the one
place the platform cannot override — and uses `colorAccent`, so a text button
reads as an action in all four themes.

*One bug behind "selections are not remembered", "Results ignores the
selected target", and "still no shots detected".* A `Spinner` delivers its
first `onItemSelected` on the layout pass AFTER `onCreate` — that is, after
the listener has been attached. So merely OPENING the Session or Import
screen ran the rules listener, which forces the target face to the rule set's
default face. Choose a custom target, leave, come back, and it had silently
reverted to ISSF 10 m Air Rifle. The Results screen then named that face, and
detection ran against its geometry, which is why a card that scores correctly
in offline testing produced no hits at all on the phone. The position set
programmatically is now remembered and its callback recognised as ours rather
than treated as a user choice.

*An empty session now follows the current selection.* A session records the
face it was scored against, and must: a result cannot change because a menu
changed afterwards. But before anything has been shot there is nothing to
protect, and the stale id was what the Results screen was reporting. Once
there are shots, the recorded face and rules stand, and the screen says so.

**1.7.0** — clearing, legibility, and the other half of the wrong-face
problem.

*Every spinner in the app was drawing black text on the dark background.*
`android.R.layout.simple_spinner_item` applies `textAppearanceMediumInverse`
— a text appearance coloured for the INVERSE of the current theme — so under
any of the three dark themes it resolved to black. No style in this app could
override it, because the colour comes from the platform's text appearance
rather than from `textColor`. Replaced with our own item layouts, which fixes
the theme, units, rules, target, firearm, sight, click-unit and log-level
pickers at once.

*Clearing.* The log's Clear button existed, but four controls in one row
pushed it off the right-hand edge of a phone; the toolbar is now two rows and
the actions are evenly spread. Recorded shots can be cleared from Results,
Import and Session, keeping the target, rules and distance so the same card
can be re-scored without rebuilding the session.

*Stale results.* A photograph that detected nothing left the previous session
untouched, so the Results screen went on showing an older target's shots as
though they belonged to the new photo. The session is now replaced BEFORE
detection runs, so an empty result is visibly empty, and the message says
what to check.

*The other half of the wrong-face problem.* 1.6.0 added a check for a
registration box that is too small. It cannot catch a box placed perfectly on
the outermost circle of a target whose RING SPACING does not match the
selected face — a six-ring 5-to-10 card registered as a ten-ring ISSF face
has its box in exactly the right place and every ring boundary in the wrong
one. `verifyRings` now walks out along the radius after registration and
checks that each ring the face claims is actually printed there; fewer than
half present means the face does not describe this target, and it says so.
Rings too close together for the image to resolve are skipped rather than
counted absent, so a dense air-rifle face at modest resolution does not cry
wolf.

**1.6.0** — a diagnostic log, and the check that would have explained why it
was needed.

*The log.* Reachable from the Home screen, hideable in Settings > Display.
Its **Report** button shares the log together with the state needed to read
it: app build, device, and the active target face with its black-to-outer
ratio, rule set, scoring gauge, distance and equipment. A log alone would not
have diagnosed the failure that prompted this — the cause was a target face
picked in a menu, which no amount of detection logging reveals unless you
also know what was picked. Detection itself now logs its parameters
(rectified size, mm/px, gauge in pixels, scoring limit, fraction of the frame
in view, noise sigma, threshold) and every candidate it accepted or rejected,
with the reason.

*Why the app was silent when it should not have been.* Auto-detection finds
the black aiming mark and expands the box to the outer ring using the
SELECTED face's published ratio. That ratio varies enormously — 2.61 on the
ISSF air pistol face, 1.49 on the air rifle one — so the wrong face does not
fail, it registers the wrong circle. An air-pistol-proportioned card scored
with the air rifle face put the box at 188 px when the FIVE ring sits at
194 px: every distance came out half its true size, the centre shot read as a
10, and everything past the misplaced outer ring was not looked at and came
back as a miss.

`TargetGeometryCheck` now reads the evidence already in the picture. If the
box really is around the outermost ring, there should be no printed rings
outside it; when there are, the face is wrong — and the black-to-outer ratio
the image actually shows says which face would have been right. The app says
so before anything is scored.

*Also:* the scoring-area limit moved from 1.02x to 1.10x the outer ring, so a
shot just past the last ring is reported as the miss it is rather than
ignored. Card furniture sits much further out — the club logo that prompted
the limit was at 1.21x.

**1.5.3** — corrections from testing the detector against two real uploaded
targets: a synthetic face with five shots, and a photograph of a club card
with five pellet holes.

*Printed ring numerals were being scored as shots.* Radial normalisation
removes ring lines because they are rotationally symmetric, but a numeral
occupies four angles out of 360 and barely moves a median taken around the
circumference. On the synthetic face the detector returned 22 candidates for
5 real shots; 17 were printed digits. Each candidate is now tested for
rotational twins — the same feature a quarter, half and three-quarters of a
turn away — and discarded if two of the three partners look alike.

A four-fold median over the whole image was tried first. It is perfect on
synthetic data and **lost two of five real shots on the photograph**, because
the rotated samples only correspond when registration is exact and the
lighting is flat, and on a hand-held photo of a card on a range neither
holds. The per-candidate form is far more forgiving. The result is
insensitive to its threshold: anything from 0.35 to 0.65 gave identical
answers on both targets.

*Absolute detection now looks only inside the outermost ring.* Everything
beyond it is card furniture — a club logo, a score box, the shooter's name, a
thumb holding the card down — and none of it is scoreable anyway. The
association's logo on the real target was being reported as a shot.
Excluding that region also dropped the robust sigma from 4.4 to 3.0 on that
photograph, and the lower threshold that followed found a faint fifth hole
that had been missed. This is deliberately NOT done in differential mode,
where the reference already cancels static features and a mark outside the
rings really is a shot that should be reported as a miss.

Result on the two targets: **5 of 5 shots found on both**, no false positives
on the synthetic one, and one low-confidence false positive on the photograph
(a thumb at the card edge, flagged as doubtful by the existing confidence
warning).

*On the remaining scoring differences, which are registration and not
detection.* On the synthetic target the centre shot scored 9 against a true
10, because the scoring gauge was set to 5 mm while those holes measure 6.4
to 8.0 mm across; at any gauge of 6 mm or more it scores 10. On the
photograph the outer three shots each came out one ring generous, and all
five match the true scores exactly once the registration scale is corrected
by about 6%. Marginal calls near a ring boundary need the box set to within
one or two percent — which is precisely what dragging the handles onto the
outermost ring is for, and why the black-derived expansion is a starting
point to be checked rather than an answer.

**1.5.2** — corrections for three failures found in the field. Every one of
them produced a complete, plausible, wrong answer rather than an error, which
is the class of bug this project keeps having to guard against.

*The box came out skewed on a square-on target.* Auto-detect was APPLYING a
tilt inferred from the ellipticity of the aiming mark. A shot-up mark
measures a few percent elliptical from segmentation noise alone, and
`acos(1/1.05)` is 18 degrees — so noise became a visible skew, and since the
sign is a guess, half of them skewed the wrong way. The estimate is now
offered on an *Apply estimated tilt* button and never applied on its own.
Mark detection additionally weights candidates by how central they are and
retries on the middle 60% of the frame, because a white card on a dark bench
puts the bench in charge of the Otsu split.

*Ring numerals never appeared in the targets database.* The gate wanted the
annulus to exceed 1.7x a fixed 12 dp of text — about 60 px — while the 230 dp
preview gives roughly 33 px on every face at every screen density, so they
were skipped universally. The glyph now scales to the annulus and drops out
only when it would genuinely be unreadable.

*Hit detection bore no resemblance to the real shots,* in both count and
position. Two independent causes:

* **Out-of-frame pixels were averaged into the contrast windows.** Rectifying
  fills whatever the camera did not cover with a near-black marker; a window
  25% outside the photograph read 150 instead of 200, which is 50 levels of
  apparent contrast against a threshold of 8. That invented a rim of holes
  wherever the photo did not cover the whole card. Sums and counts are now
  taken over valid pixels only, and a window without enough of them reports
  nothing rather than a confident wrong number.
* **Nothing suppressed the printed target.** On a synthetic ISSF face the
  ring lines carry 170 levels of contrast against a real hole's 39 — the
  printing was four times stronger than the signal. Absolute detection now
  subtracts the RADIAL MEDIAN first: everything printed on a ringed face is
  rotationally symmetric, so taking the median brightness around each radius
  and removing it deletes every ring exactly, at every radius, with no
  threshold to tune, while a handful of holes cannot shift a median taken
  over a whole circumference. Measured on the synthetic face, ring contrast
  goes to zero and hole contrast survives.

Note the third fix is verified against synthetic targets, not photographs.
It removes two definite causes; whether it is now good enough on real cards
needs range testing.

**1.5.1** — correction. The first CI run to get past resource merging
compiled the whole project and passed 75 of 76 tests. The one failure was in
the test, not the app: it asserted that a 10 degree tilt is recovered from
the aiming mark, while `suggestedTransform` deliberately ignores anything
under about 11.4 degrees.

The gate was right and is now explicit. Ignoring an 11 degree tilt misplaces
a shot on the *outermost* ring of a 50 m face by 1.5 mm — under a fifth of a
ring pitch, and less nearer the centre where the shots are. Against that, the
sign of a suggested tilt is a guess, so acting on an ellipticity that is
really segmentation noise buys a correction as likely to go backwards as
forwards. The bare `1.02` is now `MIN_ELLIPTICITY_TO_SUGGEST`, documented
with that reasoning, and a test pins the angle it corresponds to so moving it
has to be a decision rather than an accident.

**1.5.0** — feature. Individual **tilt and rotation** controls on the
registration box: an in-plane rotation and a tilt about each image axis, the
three controls a phone camera app already teaches everyone. A square box
carries four degrees of freedom and a homography has eight; these take it to
seven, and the eighth is shear, which cannot arise with square pixels and a
flat target. Corner registration remains for anything beyond that.

The tilt model is deliberately not a bare projective term. Tilting a plane
does two things at once, and modelling one of them looks convincing and
scores wrong:

* **foreshortening** by `cos(alpha)` — this is what turns the aiming mark
  from a circle into an ellipse, and a transform with only a perspective term
  makes the outline *wider* whatever sign you give it, so it can never match
  a target leaning away;
* **keystoning** — the near edge magnified, which is the projective term
  proper.

Keystone strength is fixed at a quarter (target half-size over camera
distance, near enough at any normal framing) rather than being a fourth
slider whose effect nobody could judge by eye. It is second order next to the
foreshortening.

The starting tilt is inferred from the detected mark: a circle tilted by
`alpha` projects to an ellipse with minor/major = `cos(alpha)`, so
`alpha = acos(ratio)`, split between the two axes by the direction of the
minor axis. The axis ratio comes from the blob's **second moments**, not its
bounding box, because a bounding box cannot tell a rotated ellipse from a
larger circle — while the SIZE still comes from the bounding box, which is
unaffected by the shot holes that inflate a second moment.

**The sign of the tilt is a guess, knowingly.** An ellipse is symmetric: it
says the target leans and by how much, but not which way. Only the keystone
asymmetry distinguishes leaning toward from leaning away, and that is far too
weak a signal to read off a shot-up aiming mark. So the app picks a side,
draws the outline, and says to flip the slider if it went the wrong way —
which with a live preview is a one-second fix.

**1.4.0** — feature. Registration is now a **square bounding box** with
draggable top-left and bottom-right handles, placed for you: the app finds
the black aiming mark by Otsu threshold plus a largest-circular-component
search, then expands the box to the whole scoring area using the face's own
published black-to-outer ratio. Ring values are printed on the target plot at
four cardinal points, so the targets database shows scores on the circles.

Three decisions worth keeping if this is revised.

*The box cannot model perspective.* Four tapped corners give a full
projective transform — eight degrees of freedom, enough to undo keystoning.
An axis-aligned square gives four, translation and one scale, and registering
an oblique target that way yields a plausible score that is wrong by a
smoothly varying amount across the face. The box is still the right default
because most people photograph a card square-on and two handles beat four
accurate taps, so the detector measures the aiming mark's **ellipticity** and
says plainly when the assumption has broken. Corner registration remains one
checkbox away and is unchanged.

*The outer ring is reached by arithmetic, not detection.* The outermost ring
is a thin line on white paper and finding it directly is unreliable; the
black mark is the easiest feature in the picture. So the mark locates the
target and the published ratio expands to the outer ring — 1.5x on the ISSF
air rifle face, 2.61x on air pistol — giving a much longer scale baseline
than the black alone. It falls back to the mark when the expansion would run
off the picture.

*The mark's radius comes from its bounding box, not its area.* A shot-up
aiming mark has holes in it, so `sqrt(area/pi)` under-reads the radius by
more the better the shooter is — a bias that would tighten registration and
inflate every score, worst for exactly the people most likely to notice.

**1.3.1** — correction. The photo-upload scoring entry points have existed
since 1.2.0, but both were styled `borderlessButtonStyle` — flat text with no
background, sitting directly under a filled button — and read as captions
rather than controls. Users could not find the feature at all. Both are now
ordinary buttons labelled "Upload a target photo to score", the Home one
comes first (scoring a card after the relay is the commoner job and needs no
camera downrange), and the Session screen separates it from the live-capture
workflow with an explicit "Or score live, as you shoot:".

The static gate now also flags the reverse of a missing view: a button
present in a layout that no code listens to. It would not have caught this
one — the listener was wired correctly, the button was just invisible as a
button — which is worth remembering about what a build check can and cannot
tell you.

**1.3.0** — feature. Google Play listing material in `play/`: short and full
descriptions, and a 1024x500 feature graphic generated from the same drawing
as the launcher icon.

**1.2.1** — correction. `applicationId` is now `com.STS`. CI builds release
artefacts only: unit tests run against the release variant and the workflow
assembles the release APK and the Play bundle, with no debug build anywhere
in the pipeline.

Signing was also made to degrade rather than break. CI sets the keystore
environment variables unconditionally, so an unconfigured secret arrives as
an empty string and not as null — and `file("")` resolves to the project
directory, which the old build script would have accepted as a keystore path
and then failed on deep inside the signing task. Blank, and present-but-
missing-on-disk, are now both treated as "no keystore", and the build
produces unsigned artefacts instead of stopping.

**1.2.0** — feature. Score a target from a photograph taken after the session
(`ImportActivity`), with an optional clean "before" photograph that turns
detection into a difference. Shot distribution and histogram
(`ShotDistribution`, `ScoreHistogramView`) on Results, live on Session, on
the import screen and in the shared report.

Also a correction found while writing the tests rather than by running them:
`ImageLoader.sampleSizeFor` used the sample-code idiom that tests
`width / (sample * 2) >= max` and therefore stops one step early, deliberately
returning an image at least as large as requested. That is right for a
thumbnail and wrong for a memory bound — a 50 MP photograph came back at
4080 px against a documented 3000 px cap. The function now genuinely caps.

**1.1.0** — feature. Launcher and Play Store icon: a target with a three-shot
group and the score `10.9` — the ISSF decimal maximum, which reads to a
target shooter as a perfect shot and is the figure this app exists to
produce. Palette sampled from the VTB icon (`#2E4034` field, `#CAA24B` gold,
`#F4F3EE` cream) so the two apps sit together on a home screen as a family.

`tools/generate_icons.py` is the single source: it emits `play/play_store_icon.png`
(512×512, full bleed — Play rounds the corners itself), the adaptive
foreground at five densities, and the Android 13+ monochrome layer, all from
one parameterised drawing. Regenerate with `python3 tools/generate_icons.py`
rather than editing the PNGs, or the store listing and the home screen will
drift apart.

Two details worth keeping if the artwork is revised. The bullet holes are
knocked out to *transparency* at a slightly larger radius than the hole
itself, so whatever they land on is visibly broken by them — that gap is what
makes a mark read as a hole punched through paper rather than a dot printed
on it, and being transparent it works unchanged in the store icon, the
adaptive icon and the themed monochrome layer. And the artwork is sized to
the 66dp adaptive safe zone, not the 108dp canvas: a launcher scales the
canvas so its *72dp visible area* fills the display size, which is easy to
get wrong in a preview and produces an icon that looks correct in the mockup
and undersized on a real home screen.

**1.0.1** — correction. Removed `res/mipmap-hdpi/README.txt`. Android's
resource merger accepts only `.xml` and `.png` under `res/`, so any other
file fails `mergeDebugResources` before compilation is attempted. The file
was a placeholder for a directory that is not needed at all: minSdk is 26,
so the adaptive icon in `mipmap-anydpi-v26` covers every supported device
and no density-specific PNG fallback exists.

**1.0.0** — first release.

---

developed by Dr Artur Krukowski, with support from Claude AI
