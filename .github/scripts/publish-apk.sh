#!/usr/bin/env bash
set -euo pipefail

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
VERSION="$(python3 - <<'PY'
import re
from pathlib import Path
match = re.search(r'versionName\s*=\s*"([^"]+)"', Path("app/build.gradle.kts").read_text())
if not match or not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+-]*", match.group(1)):
    raise SystemExit("Missing or invalid APK versionName")
print(match.group(1))
PY
)"
APK_HASH="$(sha256sum "$APK_PATH" | cut -d ' ' -f1)"
BASE_TAG="v$VERSION"
TAG="$BASE_TAG-build$GITHUB_RUN_NUMBER"

release_digest() {
    gh api "repos/$GH_REPO/releases/tags/$1" --jq '.assets[] | select(.name | endswith(".apk")) | .digest'
}

if gh release view "$TAG" >/dev/null 2>&1; then
    if [[ "$(release_digest "$TAG")" == "sha256:$APK_HASH" ]]; then
        echo "Identical APK is already published at $TAG"
        exit 0
    fi
    TAG="$TAG-attempt$GITHUB_RUN_ATTEMPT"
fi

mkdir -p release-assets
ASSET="Meerkat-$TAG-debug.apk"
cp "$APK_PATH" "release-assets/$ASSET"
(cd release-assets && sha256sum "$ASSET" > "$ASSET.sha256")
cat > release-assets/notes.md <<NOTES
Meerkat $TAG

APK version: $VERSION
Source commit: $GITHUB_SHA
Build and test results: https://github.com/$GH_REPO/actions/runs/$GITHUB_RUN_ID

JVM unit tests and the APK build passed before publication.
This APK uses the fixed development signing identity for compatible updates.
Resource capture now uses the built-in browser. Global VPN capture and credential filling have been removed.
NOTES
gh release create "$TAG" "release-assets/$ASSET" "release-assets/$ASSET.sha256" \
    --target "$GITHUB_SHA" --title "Meerkat $TAG" --prerelease \
    --notes-file release-assets/notes.md
