#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Compile and run the unit tests WITHOUT Gradle or the Android SDK.
# ---------------------------------------------------------------------------
#
#  Why this exists. Every release from 1.6.0 to 1.9.1 shipped without being
#  compiled, because the only compiler in the loop was CI, and each run
#  surfaced one layer of errors before stopping at the next. 1.10.0 was the
#  first build whose MAIN source compiled — which is precisely why it was also
#  the first whose TEST source got as far as the compiler, and that immediately
#  failed on an assertEquals(Int, Int, Int) that had been sitting in
#  RingFinderTest since it was written.
#
#  This runs the whole test source set locally in about a minute:
#
#      npm install --prefix /tmp/kotlinc kotlin-compiler
#      tools/offline/run.sh /tmp/kotlinc/node_modules/kotlin-compiler/bin
#
#  WHAT IT DOES NOT COVER, so it is not mistaken for the real build:
#    - Anything touching Android framework classes. The UI, the repositories,
#      ImageLoader's decode path and ScoringSession are all excluded; only the
#      pure logic compiles here.
#    - Resources, manifests, data binding, R8. Gradle remains the authority.
#
#  The JUnit shim mirrors the real Assert overload set exactly and deliberately
#  adds no convenience overloads. A shim more permissive than JUnit hides the
#  errors it exists to catch — an earlier version of it accepted the very
#  assertEquals(Int, Int, Int) that CI then rejected.
set -euo pipefail
# Scratch space on a fast local filesystem. The compiler writes thousands of
# class files, and putting them on a slow or network-backed mount turned a
# forty-second type-check into several minutes. Override with STS_TMP.
BIN="${1:-}"
[ -n "$BIN" ] && export PATH="$BIN:$PATH"
command -v kotlinc >/dev/null || { echo "kotlinc not on PATH; pass its bin directory as \$1"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d -p "${STS_TMP:-/tmp}")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src"

M="$ROOT/app/src/main/java/com/rfsat/sts"
for f in detect/BlackMarkDetector detect/BoxTransform detect/EllipseFit detect/HoleDetector \
         detect/Homography detect/HoughCentre detect/LumaFrame detect/MarkOutline \
         detect/RingFinder detect/RingShapeSelector detect/ShapeCorrection detect/MergedHoles \
         detect/AspectCorrection detect/SpsDimensions detect/LensDistortion detect/CameraProfile detect/TargetGeometryCheck detect/TargetRegistration detect/RingFamilyFit detect/PunctureCheck detect/SourceHoleDetector detect/LocalBackground \
         profiles/BulletProfile profiles/RifleProfile profiles/ScopeProfile \
         profiles/AmmoCatalog profiles/RifleCatalog profiles/ScopeCatalog \
         rules/RuleCatalog rules/RuleSet \
         scoring/CorrectionCalculator scoring/GroupStatistics scoring/ScoringEngine \
         scoring/Shot scoring/ShotDistribution \
         targets/PracticalGeometry targets/TargetCatalog targets/TargetFace \
         scoring/ScoredPhoto ui/NameWrap ui/AimGuide ui/Reticle detect/ScaleSettings scoring/ShotCountCheck \
         cloud/SecondOpinion cloud/OpinionReconciler cloud/AiProvider; do
  cp "$M/$f.kt" "$WORK/src/$(basename $f).kt"
done

# ImageLoader's decode path needs BitmapFactory, Uri and ExifInterface. Lift
# only the pure function the tests call, straight out of the real file, so it
# cannot drift from what ships.
python3 - "$M/detect/ImageLoader.kt" "$WORK/src/ImageLoaderTrimmed.kt" <<'PY'
import sys
src = open(sys.argv[1]).read()
a = src.index("    fun sampleSizeFor(")
i = src.index('{', a); j = i; d = 0
while True:
    if src[j] == '{': d += 1
    elif src[j] == '}':
        d -= 1
        if d == 0: break
    j += 1
open(sys.argv[2], 'w').write(
    "package com.rfsat.sts.detect\n\n// Lifted verbatim from ImageLoader.kt by tools/offline/run.sh.\n"
    "object ImageLoader {\n" + src[a:j+1] + "\n}\n")
PY

# One package declaration per file: these must stay SEPARATE files.
cp "$ROOT"/tools/offline/{JUnit4Shim,LoggerStub,AllRunner}.kt "$WORK/src/"
# StubUtilPkg carries android.util.Log, so StubLog is not copied here as well.
cp "$ROOT"/tools/offline/{StubPlatformPkg,StubGraphicsPkg,StubUtilPkg,StubNetPkg,StubCameraX,StubCameraRes,StubJsonPkg}.kt "$WORK/src/"
cp "$ROOT"/app/src/test/java/com/rfsat/sts/*.kt "$WORK/src/"

echo "compiling $(ls "$WORK/src" | wc -l) files..."
# Invoke the runner class by name rather than through a jar manifest:
# -Xmain-class is not supported by every kotlinc build, and where it is
# ignored the jar silently gets no Main-Class and the run fails with
# "no main manifest attribute" long after the compile appeared to succeed.
kotlinc -nowarn "$WORK"/src/*.kt -include-runtime -d "$WORK/all.jar"
java -cp "$WORK/all.jar:$ROOT/app/src/test/resources" AllRunnerKt

echo
echo "The UI is type-checked separately: tools/offline/typecheck_ui.sh"
