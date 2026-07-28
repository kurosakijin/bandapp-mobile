#!/usr/bin/env bash
# Ship a change: bump version -> build signed release -> publish to GitHub so
# Obtainium picks it up on the device. Usage:
#
#   ./release.sh "what changed"
#   ./release.sh --minor "bigger change"     (1.0.4 -> 1.1.0)
#   ./release.sh --major "big one"           (1.0.4 -> 2.0.0)
#
# versionCode ALWAYS increments and never restarts: Android compares only that
# integer when deciding an update is newer, so a reset would make the new APK
# look older than what is installed and the update would be refused.
set -euo pipefail
cd "$(dirname "$0")"

BUMP=patch
case "${1:-}" in
  --major) BUMP=major; shift ;;
  --minor) BUMP=minor; shift ;;
  --patch) BUMP=patch; shift ;;
esac
NOTES="${1:-Maintenance update.}"
SUMMARY_FILE=RELEASE_HISTORY.md

GRADLE_FILE=app/build.gradle
CODE=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$GRADLE_FILE")
NAME=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$GRADLE_FILE")
MAJOR=${NAME%%.*}; REST=${NAME#*.}; MINOR=${REST%%.*}; PATCH=${REST#*.}
PATCH=${PATCH%%-*}                       # tolerate a "-suffix" on the old name

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac
NEW_CODE=$((CODE + 1))
NEW_NAME="$MAJOR.$MINOR.$PATCH"

echo "==> $NAME (code $CODE)  ->  $NEW_NAME (code $NEW_CODE)"
sed -i "s/versionCode $CODE/versionCode $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/versionName \"$NAME\"/versionName \"$NEW_NAME\"/" "$GRADLE_FILE"

export JAVA_HOME="$(pwd)/.tools/jdk-extract/jdk-17.0.19+10"
export PATH="$JAVA_HOME/bin:$PATH"
echo "==> building signed release"
".tools/gradle-9.4.1/bin/gradle" --offline :app:assembleRelease -q

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "!! no APK produced"; exit 1; }

# Keep a concise handoff record beside the code and attach it to the GitHub
# release. This gives future work sessions the shipped version and intent.
TODAY=$(date -u +%Y-%m-%d)
TMP_SUMMARY=$(mktemp)
{
  echo "# Release History"
  echo
  echo "## $NEW_NAME (version code $NEW_CODE) - $TODAY"
  echo "$NOTES"
  echo
  if [ -f "$SUMMARY_FILE" ]; then
    sed '1,2d' "$SUMMARY_FILE"
  fi
} > "$TMP_SUMMARY"
mv "$TMP_SUMMARY" "$SUMMARY_FILE"

echo "==> publishing v$NEW_NAME"
gh release create "v$NEW_NAME" "$APK" "$SUMMARY_FILE" "HANDOFF_SUMMARY.md" \
  --repo kurosakijin/bandapp-mobile \
  --title "$NEW_NAME" \
  --notes "$NOTES"

echo "==> done: https://github.com/kurosakijin/bandapp-mobile/releases/tag/v$NEW_NAME"
echo "    Obtainium will offer it on the next check."
