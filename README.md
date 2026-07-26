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

Home or Session → *Score a photo of a shot target*. Pick the photograph, tap
the four corners of the card, and the app finds the holes and scores them.
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
