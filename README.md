# STS — Shooting Target Scorer

An Android application for scoring shooting on paper and steel, during
competition and during training. Built on the same structure, toolchain and
visual language as **VTB Vapor-Trail Ballistics**: the same four-theme
`BaseActivity`, the same bottom-tab navigation with swipe, the same
Gson-in-SharedPreferences persistence, the same crash-safe startup, and a
profile system that is deliberately field-compatible with VTB and DBM.

- `applicationId` — `com.STSC`
- Kotlin namespace — `com.rfsat.sts`
- AGP 8.9.1 / Kotlin 2.1.0 / compileSdk 36 / minSdk 26 / targetSdk 36
- Gradle 8.11.1 or newer, JDK 17

Open the folder in Android Studio and build, or run `gradle assembleDebug`.
`.github/workflows/android-ci.yml` runs the unit tests and builds a debug APK
on every push, and additionally produces a signed APK and AAB when the four
keystore secrets are configured.

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

### Three ways to record a shot

| Mode | When | Accuracy |
|---|---|---|
| **Live** | camera stays on the target for the string | best — immune to printed rings, paper texture and the aiming mark |
| **Single frame** | scoring a card at the end of a relay | good with a reference; weaker without one, and the app says so |
| **By hand** | anything the detector got wrong | authoritative — tap the plot on the Results screen |

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

developed by Dr Artur Krukowski, with support from Claude AI
