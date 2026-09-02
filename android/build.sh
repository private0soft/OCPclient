#!/usr/bin/env bash
# =============================================================================
# OpenConnect +P — Android release builder
#
# Edit the three values below, then run:
#   ./build.sh
#
# Or pass on the command line (overrides the defaults below):
#   ./build.sh --version 1.0.5 --code 105 --url 'https://example.com/path/ap.json'
#
# What this does:
#   1) Sets app versionName / versionCode  →  UI "vX.Y.Z" + PackageManager
#   2) XOR-bakes UPDATE_URL into UpdateDefaults.java  →  not plaintext in APK
#   3) Builds signed/unsigned release APK
#   4) Writes OpenConnect-P_latest.apk + ap.json (updater manifest)
# =============================================================================

set -euo pipefail
cd "$(dirname "$0")"

# ---------- release values (edit these) ----------
VERSION="1.0.4"
VERSION_CODE="104"
UPDATE_URL="https://raw.githubusercontent.com/private0soft/OCPclient/refs/heads/main/android/ap.json"
NOTES="Latest Android release"
# -------------------------------------------------

usage() {
  cat <<'EOF'
Usage: ./build.sh [options] [-- gradle-args...]

Options:
  --version NAME     versionName shown in UI (e.g. 1.0.5)
  --code N           versionCode integer (must increase each release)
  --url URL          HTTPS URL of the update JSON (ap.json)
  --notes TEXT       notes field written into ap.json
  --apk-url URL      APK download URL written into ap.json (optional)
  --skip-bake        do not rewrite UpdateDefaults ENC bytes
  --dry-run          print planned actions, do not build
  -h, --help         show this help

Examples:
  ./build.sh
  ./build.sh --version 1.0.5 --code 105
  ./build.sh --version 1.0.5 --code 105 --url 'https://host/android/ap.json'
EOF
}

APK_URL=""
SKIP_BAKE=0
DRY_RUN=0
GRADLE_EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift 2 ;;
    --code) VERSION_CODE="${2:-}"; shift 2 ;;
    --url) UPDATE_URL="${2:-}"; shift 2 ;;
    --notes) NOTES="${2:-}"; shift 2 ;;
    --apk-url) APK_URL="${2:-}"; shift 2 ;;
    --skip-bake) SKIP_BAKE=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; GRADLE_EXTRA+=("$@"); break ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *) GRADLE_EXTRA+=("$1"); shift ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$VERSION" ] || die "VERSION is empty"
[ -n "$VERSION_CODE" ] || die "VERSION_CODE is empty"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE must be an integer (got: $VERSION_CODE)"
[ -n "$UPDATE_URL" ] || die "UPDATE_URL is empty"
case "$UPDATE_URL" in
  https://*) ;;
  *) die "UPDATE_URL must start with https://" ;;
esac

# Derive APK download URL for ap.json if not given.
# .../android/ap.json  →  .../android/OpenConnect-P_latest.apk
if [ -z "$APK_URL" ]; then
  if [[ "$UPDATE_URL" == */ap.json ]]; then
    APK_URL="${UPDATE_URL%/ap.json}/OpenConnect-P_latest.apk"
  else
    APK_URL="$UPDATE_URL"
  fi
fi

UPDATE_DEFAULTS="app/src/main/java/net/openconnect_vpn/android/core/UpdateDefaults.java"
[ -f "$UPDATE_DEFAULTS" ] || die "missing $UPDATE_DEFAULTS"

bake_update_url() {
  local url="$1"
  local target="$2"
  python3 "$(dirname "$0")/bake_update_url.py" "$url" "$target"
}

echo ""
echo "OpenConnect +P — Android release"
echo "  versionName  $VERSION"
echo "  versionCode  $VERSION_CODE"
echo "  update JSON  (XOR-baked into UpdateDefaults — not plaintext)"
echo "  apk url      $APK_URL"
echo ""

if [ "$DRY_RUN" = 1 ]; then
  echo "(dry-run) would bake URL and run: ./gradlew :app:assembleRelease"
  exit 0
fi

if [ "$SKIP_BAKE" = 0 ]; then
  bake_update_url "$UPDATE_URL" "$UPDATE_DEFAULTS"
else
  echo "Skipping UpdateDefaults bake (--skip-bake)"
fi

# Version goes into Gradle → AndroidManifest / PackageManager → UI label.
# Leave OCP_UPDATE_URL empty so the APK uses the XOR-baked bytes, not a
# plaintext BuildConfig string.
export OCP_VERSION="$VERSION"
export OCP_VERSION_CODE="$VERSION_CODE"
export OCP_UPDATE_URL=""

# Gradle 8.x does not run on very new JDKs. Prefer 17 when available.
if [ -z "${JAVA_HOME:-}" ]; then
  for jhome in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/jdk-17 /usr/lib/jvm/java-21-openjdk; do
    if [ -d "$jhome" ]; then
      export JAVA_HOME="$jhome"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  echo "Using JAVA_HOME=$JAVA_HOME"
fi

./gradlew :app:assembleRelease "${GRADLE_EXTRA[@]+"${GRADLE_EXTRA[@]}"}"

SRC="app/build/outputs/apk/release/app-release.apk"
DST="$(cd .. && pwd)/OpenConnect-P-latest-release.apk"
[ -f "$SRC" ] || die "release APK not found: $SRC"

mv -f "$SRC" "$DST"

write_ap() {
  local out="$1"
  cat >"$out" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION",
  "notes": "$NOTES",
  "url": "$APK_URL"
}
EOF
}

write_ap "ap.json"
[ -f "../ap.json" ] && write_ap "../ap.json" || true

echo ""
echo "Done."
echo "  APK:      $DST"
echo "  Manifest: $(pwd)/ap.json"
echo "  Upload ap.json to: $UPDATE_URL"
echo "  Upload APK to:     $APK_URL"
echo ""
echo "UI version comes from PackageManager.versionName=$VERSION"
echo "Update check uses XOR-baked URL inside UpdateDefaults.java"
