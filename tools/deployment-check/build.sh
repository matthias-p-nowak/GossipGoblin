#!/usr/bin/env bash
# Build a debug-signed deployment probe using Debian packages; no tablet changes.
set -euo pipefail

# Resolve sources and keep all generated artifacts in the ignored workspace.
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
workspace_dir="$(cd -- "$source_dir/../.." && pwd)"
build_dir="$workspace_dir/tmp/deployment-check"
mkdir -p "$build_dir"

# Assemble the single activity and package it with the Android framework resources.
java -jar /usr/share/java/smali.jar assemble "$source_dir/MainActivity.smali" -o "$build_dir/classes.dex"
aapt package -f -M "$source_dir/AndroidManifest.xml" \
    -I /usr/share/android-framework-res/framework-res.apk -F "$build_dir/unsigned.apk"
(
    cd "$build_dir"
    aapt add unsigned.apk classes.dex
)
zipalign -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"

# Reuse a local test key so subsequent installations can update the package.
if [[ ! -f "$build_dir/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$build_dir/debug.keystore" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 3650 -dname 'CN=Android Debug'
fi
apksigner sign --ks "$build_dir/debug.keystore" --ks-pass pass:android \
    --out "$build_dir/deployment-check.apk" "$build_dir/aligned.apk"
apksigner verify "$build_dir/deployment-check.apk"
printf 'Verified APK: %s\n' "$build_dir/deployment-check.apk"
