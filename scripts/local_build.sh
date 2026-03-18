#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Use any provided SDK root or fall back to $HOME/android-sdk.
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

TRACK="${FASTLANE_TRACK:-internal}"

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PROJECT_ROOT/.gradle}"
export GRADLE_USER_HOME

mkdir -p "$GRADLE_USER_HOME"/{daemon,caches,wrapper,native}

GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.daemon=false}"
GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.host.address=127.0.0.1 -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"
export GRADLE_OPTS

cd "$PROJECT_ROOT/fastlane/app"

echo "Running lint..."
gradle lint

echo "Running unit tests..."
gradle testDebugUnitTest

echo "Assembling debug APK..."
gradle assembleDebug

cd "$PROJECT_ROOT"

echo "Running Fastlane deploy (track=${TRACK})..."
bundle exec fastlane deploy track:"$TRACK"
