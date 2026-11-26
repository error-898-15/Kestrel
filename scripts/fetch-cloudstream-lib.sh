#!/usr/bin/env bash
set -euo pipefail

# Pinned CloudStream library commit (GPL-3.0)
CLOUDSTREAM_COMMIT="${CLOUDSTREAM_COMMIT:-a28e6468f54cfe420feebd205d3d21d08ed0cde8}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/.cloudstream-build"
LOCAL_M2="$ROOT_DIR/local-m2"

if [ -d "$LOCAL_M2/com/lagradost/api/library-android" ]; then
  echo "CloudStream library already published in local-m2/"
  exit 0
fi

mkdir -p "$BUILD_DIR" "$LOCAL_M2"

if [ ! -d "$BUILD_DIR/.git" ]; then
  git init -q "$BUILD_DIR"
  git -C "$BUILD_DIR" remote add origin https://github.com/recloudstream/cloudstream.git
fi

git -C "$BUILD_DIR" fetch -q --depth 1 origin "$CLOUDSTREAM_COMMIT"
git -C "$BUILD_DIR" -c advice.detachedHead=false checkout -q -f FETCH_HEAD

ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$ANDROID_SDK" ]; then
  echo "sdk.dir=$ANDROID_SDK" > "$BUILD_DIR/local.properties"
fi

(
  cd "$BUILD_DIR"
  ./gradlew --no-daemon -q :library:publishToMavenLocal -Dmaven.repo.local="$LOCAL_M2"
)

echo "Published CloudStream library ($CLOUDSTREAM_COMMIT) to local-m2/"
