#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="${1:-debug}"

case "$VARIANT" in
  debug)
    TASK_VARIANT="Debug"
    OUTPUT_VARIANT="debug"
    ;;
  release)
    TASK_VARIANT="Release"
    OUTPUT_VARIANT="release"
    ;;
  *)
    echo "Unsupported variant: $VARIANT" >&2
    exit 2
    ;;
esac

"$ROOT/gradlew" \
  ":androidApp:process${TASK_VARIANT}MainManifest" \
  ":androidTvApp:process${TASK_VARIANT}MainManifest"

check_manifest() {
  local app="$1"
  local manifest="$ROOT/$app/build/intermediates/merged_manifest/$OUTPUT_VARIANT/process${TASK_VARIANT}MainManifest/AndroidManifest.xml"

  if [[ ! -f "$manifest" ]]; then
    echo "Merged manifest not found: $manifest" >&2
    return 1
  fi

  python3 - "$app" "$manifest" <<'PY'
import sys
import xml.etree.ElementTree as ET

app, manifest_path = sys.argv[1:]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(manifest_path).getroot()
permissions = {}
for element in root:
    local_name = element.tag.rsplit("}", 1)[-1]
    if not local_name.startswith("uses-permission"):
        continue
    name = element.get(android + "name")
    if name:
        permissions.setdefault(name, []).append(element)

for forbidden in (
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
):
    if forbidden in permissions:
        raise SystemExit(f"{app}: forbidden merged permission: {forbidden}")

write = permissions.get("android.permission.WRITE_EXTERNAL_STORAGE", [])
if app == "androidTvApp":
    if write:
        raise SystemExit(
            "androidTvApp: forbidden merged permission: "
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
elif len(write) != 1 or write[0].get(android + "maxSdkVersion") != "28":
    raise SystemExit(
        "androidApp: WRITE_EXTERNAL_STORAGE must appear exactly once "
        "with maxSdkVersion=28"
    )
PY
}

check_manifest androidApp
check_manifest androidTvApp
echo "Merged manifest permission policy passed for $VARIANT"
