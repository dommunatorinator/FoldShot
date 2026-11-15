#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK_ROOT/build-tools/35.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-35/android.jar}"
OUT="$ROOT_DIR/.build"
APK_OUT="${APK_OUT:-$ROOT_DIR/FoldShot.apk}"

KEYSTORE="${FOLDSHOT_KEYSTORE:-$OUT/ci-release.keystore}"
KEY_ALIAS="${FOLDSHOT_KEY_ALIAS:-foldshotrelease}"
KEY_PASS="${FOLDSHOT_KEY_PASS:-foldshot-ci-signing}"

if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "Bootstrapping Android SDK 35 & Build-Tools 35.0.0 into $SDK_ROOT..."
    mkdir -p "$SDK_ROOT/platforms" "$SDK_ROOT/build-tools"
    curl -sSL "https://dl.google.com/android/repository/build-tools_r35_linux.zip" -o "$SDK_ROOT/bt.zip"
    unzip -q -o "$SDK_ROOT/bt.zip" -d "$SDK_ROOT/build-tools"
    mv "$SDK_ROOT/build-tools/android-15" "$BUILD_TOOLS"
    rm -f "$SDK_ROOT/bt.zip"

    curl -sSL "https://dl.google.com/android/repository/platform-35_r01.zip" -o "$SDK_ROOT/pf.zip"
    unzip -q -o "$SDK_ROOT/pf.zip" -d "$SDK_ROOT/platforms"
    [ -d "$SDK_ROOT/platforms/android-15" ] && mv "$SDK_ROOT/platforms/android-15" "$SDK_ROOT/platforms/android-35"
    rm -f "$SDK_ROOT/pf.zip"
fi

rm -rf "$OUT/gen" "$OUT/obj" "$OUT/dex" "$OUT/unaligned.apk" "$OUT/aligned.apk" "$OUT/res.zip"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass "$KEY_PASS" \
        -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 3072 \
        -sigalg SHA256withRSA \
        -validity 10000 \
        -dname "CN=FoldShot Utility,OU=Mobile Engineering,O=FoldShot,L=San Jose,ST=CA,C=US"
fi

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT_DIR/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$OUT/unaligned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT_DIR/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res.zip"

javac --release 11 -cp "$ANDROID_JAR" -d "$OUT/obj" \
    $(find "$ROOT_DIR/src" "$OUT/gen" -name "*.java")

"$BUILD_TOOLS/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/obj" -name "*.class")
(cd "$OUT/dex" && zip -X -q "$OUT/unaligned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$APK_OUT" \
    "$OUT/aligned.apk"

echo "Built and signed: $APK_OUT"
