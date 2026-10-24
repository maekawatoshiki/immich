#!/usr/bin/env bash
set -eux -o pipefail

ALIAS=androiddebugkey ANDROID_KEY_PASSWORD=android ANDROID_STORE_PASSWORD=android mise exec -- flutter build apk --release --dart-define=IMMICH_AUTO_HDR=true "$@"
~/Library/Android/sdk/platform-tools/adb install -r /Users/uint/work/immich/mobile/build/app/outputs/flutter-apk/app-release.apk
