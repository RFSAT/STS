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

---

## 📖 User Guide

**[STS User Guide (PDF) — https://www.rfsat.com/download/STS-User-Guide.pdf](https://www.rfsat.com/download/STS-User-Guide.pdf)**

The complete guide for shooters: setting up a profile set, scoring a card from
a photograph, live scoring from the camera, reading the Results screen, the AI
scoring options, and what accuracy to expect. Hosted on the RFSAT portal, so
the link stays valid as the guide is reissued.

The same document is kept in `docs/` in this repository — the editable
`STS-User-Guide_v<version>.docx` and the `STS-User-Guide.pdf` that is uploaded
to the portal. The PDF filename carries no version, so the portal link never
has to change; the version is on the guide's title page.

The document itself is the source: it is laid out in Word, and wording changes
between editions are made inside it by `docs/guide/edit_guide.py`. Nothing
regenerates it, so its template, heading styles, logo and page breaks cannot
be lost — see `docs/guide/README.md`.

---

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

One entry per release, newest first, each a short paragraph: what was wrong or
what was added, and the figure that settled it where there is one. The FULL
entry — everything measured, everything tried and rejected — is the header
comment of `app/build.gradle.kts`, which is where it is written as the work is
done rather than reconstructed afterwards.

**1.56.0** — feature. The app asks the camera what it will answer, because
nobody publishes the answer. Setting a camera's own options from the app would
make the stream predictable, and none of the protocol is documented — so
"What can this camera be told?" on the Session tab sends only questions (the
Novatek CGI query, the Ambarella socket, HTTP, ONVIF, and an RTSP OPTIONS
whose reply says whether SET_PARAMETER exists) and logs every exchange. One
run against a real camera turns a guess into a protocol.

**1.55.0** — feature. A reticle library with your own image, and lens
distortion measured from the target itself. A camera on the scope already
shows the scope's reticle, so the app was drawing a second one beside it;
Settings now offers None, five drawn reticles and an image of your own, kept
separate from the ring guide so switching it off does not switch off the
wrong-face check. For the lens: no action camera publishes a distortion
figure, and one number would not fit a zoom anyway, so it is measured from the
printed rings — they are concentric circles at equal spacing, so their radii
should be evenly spaced in pixels and the departure is the distortion.
Offered, never imposed. The Connect button is now a small Reconnect beside the
address.

**1.54.0** — feature. RTSP written out by hand, and the reason three releases
of decoder work changed nothing: it was never the decoder. A camera's access
point has no internet, Android leaves the default route on mobile data, and
every socket went out over cellular where 192.168.1.1 is correctly
unreachable. The app now requests the Wi-Fi transport *without* the internet
capability and binds to it. ExoPlayer is gone with it — TCP interleaved,
DESCRIBE probed across nine candidate paths, SDP parsed, RTP depacketised and
fed to MediaCodec, with every step of the handshake logged, because a library
that reports only an error code is what made three releases of guessing
possible. Two dependencies dropped, none added.

**1.53.0** — feature. The stream says what it is doing, and stops claiming a
connection it has not made — the message "Connected, but no picture has
arrived" appeared with the phone not on the camera's Wi-Fi at all. A TCP
reachability probe now runs first and is logged, every player transition is
logged with the transport and the full cause chain of any error, and the
message names the stage actually reached: nothing answered, answered but no
session, session but nothing decoded, decoding but the picture could not be
copied.

**1.52.1** — correction. The stream arrived and every frame was blank.
`TextureView.getBitmap()` copies out of the view's own GL surface, and off the
thread that owns it the copy comes back blank rather than failing — no
exception, no null, a bitmap of the right size full of one value, which the
detector then measured confidently. The read-back is on the main thread now,
with conversion and detection still on a worker, and a frame with no variation
in it is discarded rather than scored. The same log showed the stream being
opened twice within four milliseconds, and the phone camera bound behind it;
both fixed.

**1.52.0** — correction. A stream source was never opened at all. Starting one
happened in a single place — the live-detection button — which refuses until a
reference frame has been captured, and the reference frame comes from the
source: no frames until detection starts, no detection until a frame arrives,
and nothing in the log either way. A stream now opens on choosing the source,
on Done in the address box, on the new Connect button, on returning to the
screen, and on start-up from a saved address, with every stage logged.

**1.51.0** — feature. The stream address is remembered between restarts and
across upgrades, along with the source type — a long exact address typed on a
phone keyboard was being discarded every time the screen closed. Saved when the
source is started, and again on leaving the screen if the box holds a complete
address; anything that is not yet a URL is left alone, so a half-typed one
cannot replace a working one.

**1.50.0** — feature. RTSP is decoded by ExoPlayer rather than the platform
MediaPlayer, so a stream that plays in VLC plays here. MediaPlayer does RTP
over UDP only — no RTSP interleaved over TCP, which is what VLC falls back to
and what many cameras offer exclusively — and no H.265 on that path. UDP is
tried first and TCP automatically on failure. Two further faults went with it:
the TextureView has no surface until the layout pass after it becomes visible,
so pressing Start immediately did nothing at all, and a stream that connected
and delivered no picture said nothing.

**1.49.1** — documentation. The changelog reads to one standard from end to
end: every release gets a summary sentence and the reason or the figure behind
it, where the entries up to 1.9.0 had been full prose and everything after
them a single line. The pre-publication checklist is deleted — two of its four
items were done and the other two are ongoing work.

**1.49.0** — feature. The picture can be stretched back to square, the two
registration routes agree, and Home stops showing the rule book's distance
instead of the session's. Reshaping the drawn circles would have left an
elliptical geometry in a pipeline whose scale, gauge, ring radii and hole-size
gates are all written in terms of one millimetres-per-pixel number, so the
image is stretched instead and the rings are round before any of that runs.
The stretch is measured from the fitted ring family and offered, never
applied: a good photograph of a flat card measures 1–2% out of round on
segmentation noise alone. Auto-detect now fits and draws the rings from the
same shared code the identify route uses — it still does not identify the
face, which is the whole difference between the two buttons.

**1.48.3** — correction. The guide is edited inside the author's own document
now; the generator that kept destroying its formatting is gone. Rebuilding a
document from scratch works exactly once — after that it is laid out in Word,
and the next regeneration replaces the template, the heading styles, the logo
and the page breaks with the script's own. `docs/guide/edit_guide.py`
substitutes strings inside the document and writes nothing else, verified part
by part against the author's copy.

**1.48.2** — documentation. The proof-read guide is the baseline the tooling
is held to. Six edits came back in a returned document and the generator knew
about none of them, so the next reissue would have reverted every one with
nothing to show it had happened. All six were folded in and the generator
moved out of a temporary directory into the repository.

**1.48.1** — documentation. The guide has a permanent address on the RFSAT
portal, linked from the top of this file, and the changelog here stopped
being thirty-nine releases out of date. The PDF filename carries no version so
the link survives each reissue; the version is on the title page.

**1.48.0** — feature. The photograph survives a restart, and three things on
Results that read badly were fixed. The screen had been contradicting itself —
"no adjustment needed" above an instruction to move the point of impact —
because two places were deciding that question independently; it is decided
once now. The rectified card is kept as a JPEG so coming back to a score does
not mean losing the one view a missed shot is visible in, the angular figures
are a right-aligned monospaced table, and bullet lists have a hanging indent
instead of wrapping back under the dot.

**1.47.1** — correction. Check box labels ignored the theme. A CompoundButton
takes its label colour from the platform theme's text appearance, not from the
colours this app sets, so under Dark and both night modes a label was
framework grey while the note directly beneath it followed the theme. Fixed on
a style applied to the view, which is the one place the platform cannot
override, across all 16 of them.

**1.47.0** — feature. Which AI service is used is three separate, stated
choices, and the app no longer names one it is not calling. One setting had
governed both import scoring and the second opinion, and every message on
screen still said "Claude" whichever service was picked — indistinguishable
from ignoring the setting. Settings now asks what scores an import, what the
second opinion asks, and which service the key and model controls are editing;
the first two are independent, because asking the other service is what makes
a second opinion worth having.

**1.46.0** — feature. API keys survive an upgrade and cannot be silently lost
for ever. An in-place upgrade never touched them; a restore onto another phone
did, because the Keystore master key does not travel with a backup and the
restored file was ciphertext nobody could read. The file is excluded from
backup and device transfer, and an unreadable one is discarded and remade with
the shooter told why rather than failing for ever. The guide lost the name
"Claude" throughout at the same time, keeping it only in the table saying
where each key comes from.

**1.45.0** — correction. OpenAI would not connect at all: "unexpected char
0x0a at 83 in header value" is a newline 76 characters into a key pasted from
a wrapped display, and `trim()` only takes them off the ends. All whitespace
is now stripped on the way in AND on the way out, so a key stored by an
earlier version repairs itself. Both services' keys are listed at once, since
showing only the selected one made setting a second look as though it had
replaced the first, and the settings screen stopped naming Claude above a
picker set to OpenAI.

**1.44.0** — feature. OpenAI alongside Claude. Both are sent the same
rectified picture, the same question and the same schema, each held to it by
its own API's mechanism — a forced tool call for Anthropic, a strict
`json_schema` response format for OpenAI — so nothing downstream knows which
replied. Keys and model choices are kept per service, and the model list is
rebuilt on a change because an identifier from one means nothing to the other.

**1.43.0** — feature. The Targets preview is square, so a face fills the
width, and the ring numerals appear at last. They were always being drawn and
always being dropped by the gate that refuses a glyph too small to read: at
the old 180 dp preview an ISSF air pistol annulus asks for a 15.4 px glyph
against a 17.9 px floor. Nothing about the gate changed — the box was too
small. The offline stub that had `View.layoutParams` as a read-only `Any?` was
fixed too; a stub weaker than the thing it stands in for hides the errors it
exists to catch.

**1.42.0** — correction. The settings screen explained itself instead of
describing itself. Ten options had grown ten paragraphs of mechanism and
evidence, all true and none of it what someone deciding whether to tick a box
reads at that moment. Each option now carries one line, with the paragraph one
tap away under More info; nothing was deleted.

**1.41.0** — correction. "The reply was not in the expected form", reported on
Opus 5, was the parsing rather than the model. Asking for "JSON and nothing
else" is a request, not a guarantee, and the parser took everything between
the first brace and the last — which fails silently on a reply that ran out of
room part way through. The model now answers through a forced tool call, where
the API assembles the arguments against a schema, with the token budget raised
from 1,500 to 4,000.

**1.40.0** — feature. One choice of what scores a card, made on import:
Embedded, or the AI service. It replaced three overlapping checkboxes that had
accumulated one request at a time and between them described states nobody
wanted, such as overriding an engine that was not running. It also now runs
where it says it does — from the Import screen, on the picture being imported.

**1.39.0** — feature. The AI service can find and score a card outright, off
by default. Registration stays the app's own work, and that is not a half
measure: without knowing where the card is and how big it is there is no
millimetre grid and nothing can be drawn in the right place. The picture sent
is the rectified card, already on that grid, which is what makes the returned
positions land where the shooter sees them.

**1.38.0** — feature. The second opinion can be set to win outright, off by
default. The request was to send the app's own hit list and have the model
judge without being biased by it; those two cannot both happen, because
anything in the prompt anchors the answer. So the model is asked cold, and the
comparison is done afterwards in the app, where it can be done honestly.

**1.37.0** — correction. Asked to delete nine marks, the app deleted one and
said nothing: reindexing replaces every survivor with a copy carrying a new
index, and `MutableList.remove` is equality-based, so each later call quietly
removed nothing. Removal now takes them all at once and matches by identity.
The second-opinion dialog also stopped explaining its method, its millimetre
accuracy and its token count at the moment of deciding.

**1.36.0** — correction. "Off" now means off. With "also find shots that
missed the rings" switched off, the limit was still the outer radius times
1.10 — 85.5 mm on a 10 m air pistol face, eight millimetres beyond the
outermost ring, and the two false marks on the test card sit at 81.2 and
82.8 mm. The limit is now the furthest a hole's centre can be with its edge
still touching the outer ring: 80.0 mm on that face.

**1.35.0** — correction. The second opinion could only make an over-detected
card worse. On T0002 the app marked fourteen, the model counted seven — the
right answer — and the reconciler offered to add three more and nothing else,
taking the plot from fourteen wrong to seventeen. The asymmetry was the fault:
over-detection is this app's measured failure mode, so removal is now offered
first when the app has marked more than the model sees.

**1.34.0** — correction. A shot in the ten ring was being dropped because a
threshold was doing the wrong job. Core pixels were those more than a fixed 30
levels from the local background; inside the aiming mark a hole stands a
hundred levels above the ink, so the shot at (−1.8, 12.7) failed at every
offset it was pointed with while the one at dead centre survived. The
threshold is now half of each hole's own maximum, with the fixed figure kept
only as a floor.

**1.33.0** — feature. A region holding more than one shot is split instead of
thrown away. On card B the pairs at 4.5 and 3.1 mm now separate: frames that
scored 0 from 0 shots score 11 from 2 and 16 from 3, both exact. The cluster
was always found and then rejected by whichever single-shot gate happened to
catch it, so the split is asked for before those gates run. The
two-photograph difference method was measured first and does not work
hand-held — two photographs of the same unpunched card differ by 3 and 10
phantom holes — so the splitter is necessary rather than a fallback.

**1.32.0** — feature. A suggestion from the AI service is measured before it
becomes a shot. This closed a gap of my own making: 1.30.0 was described as
re-searching where the model pointed and did not, planting a shot at the
model's coordinate instead — several millimetres on a 170 mm card against 0.2
to 1.7 mm for a hole the app finds itself. The suggestion now says only where
to look. The shooter's photograph is also rectified four times finer than the
detection grid, since nothing is allocated per pixel for a picture.

**1.31.0** — feature. The background is estimated locally, and card A scores
exactly right: 6 of 6 found and 33 against a truth of 33, where a single level
per zone found 5 and scored 26. A global level cannot work on a card
photographed unretouched — the paper reads 28 to 40 levels darker at the foot
of the sheet than at the head. The shot it had never found is 3.5 mm inside
the black edge; its error went from 26.3 mm to 0.9 mm.

**1.30.0** — feature. A second opinion from an AI service, on demand, that is
not allowed to score. The premise it was proposed on does not hold: the card
scored correctly by hand was not read by eye but measured, and the 6 on it
turns on 0.8 mm, which nothing looking at a JPEG resolves. So the model counts
and the app measures. Two failures found on the punched test card were fixed
with it — a harmonic lock that fitted a ring pitch exactly a third of the
truth and scored 9 instead of 19, and a scale-free check that was blind to it.

**1.29.0** — feature. Two sights that cannot be clicked — "Built-in iron
sight" and "No sight" — which needed three changes before either could give an
honest answer, starting with stating the absence of a click rather than
inferring it from a missing number. The catalogue screens also gave too much
room to their furniture: closed spinners 48→34 dp, dropdown rows 48→42 dp
(shortened rather than squeezed, because those are tap targets), the face
preview 230→180 dp.

**1.28.0** — feature. The test card is part of the app, and every build is
scored against it: an ISSF 10 m Air Pistol card hand-scored at 9, 6, 2, 1, 1
and two misses — 19 points from 7 shots — with the centre from circles fitted
to the printed rings and the pitch recovered as 7.999 mm against a truth of
8.000. Six tests hold it, two of them for the shots that have already caught
real bugs. A seventh static gate now catches `break`/`continue` inside an
inline lambda, which needs Kotlin 2.2 while this project builds on 2.1 —
the worst kind of error, since it compiles locally and fails in CI.

**1.27.0** — feature. Shots are found in the photograph itself, and the shot
inside the aiming mark is found at last: 10 → 19 on the user's card, the
hand-scored truth exactly. The cause took three wrong guesses. It was not
resolution — measured at 8, 12, 16, 24 and 32 rectified pixels per gauge the
answer is identical — and not the ring-pitch scale, a claim made and retracted
in 1.26.0. It was the colour channel, in which the printed ring lines are as
far from the paper as a hole is.

**1.26.0** — feature. The detector can be asked whether a candidate has the
profile of a puncture, and to look for the shots that missed the rings. Both
came from scoring the user's card by hand and running the shipped pipeline on
the same image: by hand 19 points from 7 shots, shipped 4 real hits, one false
positive from the maker's footer at 82.77 mm, and the 9 in the black missed
entirely.

**1.25.0** — feature. The camera adopts the face it identifies, and the plot
no longer loses your zoom when you nudge a shot. The same image scored from a
photograph and failed from the camera because Import had been identifying and
adopting the face since 1.22.0 while Session only complained: T0002's black
measures 3.723 ring pitches across against the 3.719 its face expects, and 39%
against the air rifle face that was selected.

**1.24.0** — feature. The alignment guide says continuously whether the card
matches the selected face — "Match OK", "Wrong target face", "Checking", "Aim
at the target" — because a message that fires once is the wrong shape for
someone watching a preview. Encoded three ways at once and deliberately not by
colour alone, since green breaks the night-red theme whose entire purpose is
preserving dark adaptation.

**1.23.1** — correction. The app warns when the card in front of the camera is
not the selected face. The ring guide makes a mismatch visible only to someone
who knows to look, and the natural response — walking until the circles line
up — cannot work, because the rings are drawn at the face's own proportions
and distance resizes them all together.

**1.23.0** — feature. The selected face's rings as an alignment guide, in four
options and at an adjustable size. The strongest argument for them is not
framing but verification: drawn at the face's own ratios, a card whose rings
sit at different proportions will not line up however far the shooter moves.

**1.22.1** — correction. The weaker registration was the automatic one. On
loading a photograph the app ran the aiming-mark path, which takes the scale
from the black mark times the ratio of whichever face is selected — so it is
only ever as right as that selection, and a wrong selection is the single
largest cause of nothing being detected at all. It identifies instead now.

**1.22.0** — feature. Results markers, the photo/template swap, a brighter
crosshair, and the rings that were found but not used. Markers are hollow and
half as large again as the hole, because a filled disc at true size covers the
very hole it marks and defeats the photo view. Photograph and template are
alternatives rather than layers, since two sets of ring lines a few pixels
apart is worse than either alone.

**1.21.0** — feature. Merged shots, the shot-count check, hole-centre
precision as its own metric, and stage timings. A connected-component detector
sees two shots through one place as a single region and the size and roundness
gates then rejected it — losing both shots rather than one — which at 10 m is
the app's own use case failing.

**1.20.0** — feature. Resolution discipline, a bound on the ladder search, and
the tilt-axis wedge behind a switch. `MarkOutline` was running at full
resolution while everything around it worked at 700 px or less: on a 3000 px
phone photograph that meant five threshold passes over nine megapixels each.
Measured at four sizes from 460 to 3000 px, the answer does not change.

**1.19.2** — toolchain. The activities can be compiled offline at last,
closing the gap that let three failures reach CI. Every source file is
type-checked against view-binding classes generated from the real layouts, an
R generated from the real resources, and a hand-written slice of the Android
framework — so a field that is not in the layout is not in the stub either.

**1.19.1** — correction. Two compile errors that had been in the tree since
1.18.0, through two releases, both from copying the resolution spinner between
activities and keeping the original's helper names. The offline harness could
not see them: the activities were excluded from it, which is what 1.19.2 fixed.

**1.19.0** — feature. 9×19 service pistols and factory loads — the Beretta 92X
family and the 92FS, eleven Glocks, and Fiocchi, Federal and CCI ammunition in
115, 124 and 147 grains. Barrel lengths and twist are the makers' published
figures; zeroed at 25 m rather than the 10 m used for air or 50 m for rimfire.

**1.18.1** — correction and feature. The answer to "do you capture at full
resolution" was no, because the app did not capture at all: there was no
`ImageCapture` use case, and "Score the target now" was reading a frame from
the analysis stream. Capture is now bound at the highest available resolution
and guarded.

**1.18.0** — feature. The camera is held still and its resolution can be
chosen. Nothing in the app had touched `CameraControl`, so exposure, white
balance and focus ran free — close to fatal for the differential path, which
assumes two frames of one card differ only where a shot arrived, while a
camera that re-meters between them changes every pixel by more than the 40
levels a hole is worth.

**1.17.0** — feature. Face identification stabilised, and the two largest
costs in registration removed. Across six tilt angles of the same card the
identified face had changed up to four times, and not from noise: black radius
over ring pitch cannot separate this catalogue at any precision — 4.00, 4.00
and 4.01 for three quite different faces.

**1.16.0** — feature. The scale is measured two independent ways and
cross-checked. Every scoring error is proportional to millimetres-per-pixel,
so it is the most consequential number the app measures; the ring ladder is
precise when the rings are found — 0 to 1.5% on a square-on card — and
degrades badly when they are not, where the aiming mark gives a separate
reading.

**1.15.0** — feature. The interface follows the theme and buttons are
consistent. Eighteen buttons had no style at all and were rendering at the
platform's size and in capitals beside styled ones; the status text over the
viewfinder and the whole registration overlay were hardcoded white and gold,
which the night themes exist to avoid.

**1.14.1** — correction. Two presentation fixes and a third static gate that
found a real error while it was being written. The Stage time and Notes fields
were on the platform's default EditText size, several points larger than the
body text around them, so they read as headings.

**1.14.0** — feature. Justified description text and an aligned parameter
table on the rules screen. Sixteen descriptive paragraphs across five screens
are fully justified — prose only, since justification stretches inter-word
spacing and on a two-word heading that reads as a mistake.

**1.13.0** — feature. Interface changes across Home, Session, Results,
Targets, Settings and Import. Home lost the duplicate upload and live-session
buttons, which lifted the active setup — the one thing worth confirming before
firing — back above the fold on a small phone, and that setup is a real
two-column table rather than one TextView padded out with spaces.

**1.12.2** — correction. `NameWrapTest` asserted nothing and said so only in
CI: under stubbed unit tests `Paint.measureText` returns 0.0f, so every string
"fitted" and the logic under test never ran. The wrap decision now takes a
predicate, with the Paint form a thin overload over it.

**1.12.1** — correction. Seven interface changes, all reported. The shot
controls became two rows of identically sized buttons — weighted with
wrap_content heights, a label that wrapped made its own button taller than its
neighbours — and Delete acts on the selected shot rather than the last one,
because detection order has nothing to do with shooting order.

**1.12.0** — feature and correction. The real reason hole detection "generally
fails" is the face, not the detector. The face sets millimetres per pixel, the
radius of the scoring area and which region counts as black, so registering
against the wrong one puts every hole outside the detector's size gates and
finds nothing — with no error raised anywhere, because each stage did what it
was told.

**1.11.0** — feature. The ring-pitch ladder returns the same scale for one
target photographed at different angles. It had been returning 10.4, 36.1 and
7.6 px for one target at three tilts, from four causes each measured
separately — beginning with a radial profile read at one percentile, which is
blind to the white rings printed inside the black where rings 7 to 10 live.

**1.10.1** — correction. The unit test source set had never once been compiled
and failed the moment it could be: an assertion passing three Ints to JUnit's
three-argument form, which takes doubles. It was reachable only because 1.10.0
was the first release whose main source compiled at all.

**1.10.0** — feature. Direct least-squares ellipse fitting on the ring edges,
in the numerically stable Halíř–Flusser form, and an evidence-based choice
between it and a circle by cross-validation. It fits all five ellipse
parameters where the pooled estimate fits two and assumes three; verified
against an independent implementation to five decimal places on twelve
reference cases.

**1.9.1** — correction. Four compile errors from the 1.6.0–1.9.0 run of
releases, none of which had been through a compiler: two companion objects in
one class, a `return@` naming a function that had been renamed, and a `when`
over an enum with six values ported from one with three. The static gates in
`tools/kotlin_checks.py` now catch all three classes, verified by putting each
bug back.

**1.9.0** — feature. The two gaps from 1.8.0, and Hough centre voting. The
target spinners on the detection screens show a picture of each face — 1.8.0
had put thumbnails only in the Targets tab, which is where a face is browsed,
while the spinner is where one is chosen moments before scoring against it.
Live camera detection uses the colour channel too.

**1.8.0** — feature. Five features, of which the first two change how
registration works rather than tuning it. Hole detection reads distance from
the paper's own colour rather than brightness: a pellet hole is brown, not
merely dark, which doubles its separation from the paper — 115 luma levels
against 242 — and measuring the paper rather than assuming it is neutral
matters, because an ISSF card is yellow.

**1.7.1** — correction. Three corrections, two with one cause: the black text
was not the spinners but all 35 borderless buttons, whose platform style takes
its colour from attributes this app cannot reach. A spinner also delivers its
first `onItemSelected` on the layout pass after `onCreate`, which was being
read as a user choice.

**1.7.0** — feature. Clear the log — the button existed, but four controls in
one row pushed it off the right-hand edge of a phone — and clear the recorded
shots from Results, Import and Session, keeping the target, rules and distance
so the same card can be re-scored. Every spinner in the app had been rendering
black text on the dark background.

**1.6.0** — feature. A diagnostic log reachable from Home, hideable in
Settings, whose Report button shares the log together with the active face,
rules, gauge and equipment. The app also stopped staying silent when the
selected face does not match the target in the picture.

**1.5.3** — correction. From running the detector against two real uploaded
targets rather than synthetic data: printed ring numerals were being scored as
shots. Radial normalisation removes rings but not numerals, which occupy four
angles out of 360 and barely move a radial median — 22 candidates for 5 real
shots, 17 of them digits. Now rejected by testing each candidate for
rotational twins.

**1.5.2** — correction. Three failures found in the field, the first being
auto-detect skewing the box on square-on targets: it was applying a tilt
inferred from how elliptical the aiming mark measured, and a shot-up mark
measures a few per cent elliptical from segmentation noise alone, which `acos`
turns into 14 to 25 degrees. The estimate is offered on a button now, never
imposed.

**1.5.1** — correction. The first real CI run compiled everything and passed
75 of 76 tests, and the failure was the test rather than the app: it asserted
that a 10 degree tilt is recovered, where the code deliberately ignores
anything under 11.4. That gate is a named constant now and the test asserts
the behaviour that exists.

**1.5.0** — feature. Individual tilt and rotation controls on the registration
box, laid out the way a phone camera app lays them out. Box plus these is
seven degrees of freedom, which is every one a flat target through a normal
lens needs; the eighth, shear, cannot arise with square pixels.

**1.4.0** — feature. Registration by a square bounding box with draggable
handles, placed automatically from the detected aiming mark and expanded to
the whole scoring area by the face's own published ratio. Corner registration
stays available for angled views, and the detector says when it is needed.
Ring values are printed on the plot at all four cardinal points.

**1.3.1** — correction. The photo-upload entry points had existed since 1.2.0
but were styled borderless — flat text under a filled button — and users could
not find them. Both are ordinary buttons now, and the Home one comes first.

**1.3.0** — feature. Play Store listing material: short and full descriptions
and the 1024×500 feature graphic, generated from the same drawing as the icon
so the listing and the home screen cannot drift.

**1.2.1** — correction. `applicationId` is `com.STS`, and CI builds release
artefacts only. Signing degrades to unsigned rather than failing when no
keystore is present.

**1.2.0** — feature. Score a target from a photograph after the session, and a
shot distribution histogram on Results, Session, Import and the shared report.
A correction found while writing its tests: the image loader's sample size
used the common idiom that stops one step early, so a 50 MP photo decoded at
4080 px instead of the 3000 requested.

**1.1.0** — feature. Real launcher and Play Store icon, generated from one
drawing into the 512 px store asset, the adaptive foreground at five
densities, and the Android 13+ monochrome layer.

**1.0.1** — correction. Removed `res/mipmap-hdpi/README.txt`, which the
resource merger rejects — `res` accepts only `.xml` and `.png`.

**1.0.0** — first release.

---

developed by Dr Artur Krukowski, with support from Claude AI
