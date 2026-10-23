#!/bin/bash
# Builds dist/SlaterLauncher.apk without Gradle: aapt2 -> javac -> dex -> align -> sign.
#
# Tool locations come from environment variables so the same script runs on
# GitHub Actions (official Android SDK) and anywhere else:
#   BUILD_TOOLS   Android SDK build-tools folder (provides aapt2, d8, zipalign, apksigner)
#   ANDROID_JAR   path to platforms/android-28/android.jar
# or, when no SDK is installed, individual overrides:
#   AAPT2, DX_JAR (old dx dexer), APKSIGNER_JAR
# Signing:
#   KEYSTORE (default app/release.keystore), KEYSTORE_PASSWORD, KEY_ALIAS (default redline)
#   VERSION_CODE (default 1), VERSION_NAME (default 1.0.<code>)
set -euo pipefail
cd "$(dirname "$0")"

AAPT2=${AAPT2:-${BUILD_TOOLS:-}/aapt2}
ANDROID_JAR=${ANDROID_JAR:?set ANDROID_JAR to android-28/android.jar}
KEYSTORE=${KEYSTORE:-app/release.keystore}
KEY_ALIAS=${KEY_ALIAS:-redline}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0.$VERSION_CODE}
B=build

rm -rf $B dist && mkdir -p $B/flat $B/gen $B/classes $B/dex dist

echo "==> Compiling resources"
"$AAPT2" compile --dir app/res -o $B/flat/
"$AAPT2" link -o $B/unsigned.apk -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml \
  --java $B/gen --min-sdk-version 21 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" $B/flat/*.flat

echo "==> Compiling Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d $B/classes $(find app/src $B/gen -name '*.java')

echo "==> Converting to dex"
if [ -n "${BUILD_TOOLS:-}" ] && [ -x "$BUILD_TOOLS/d8" ]; then
  "$BUILD_TOOLS/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output $B/dex $(find $B/classes -name '*.class')
else
  java -cp "${DX_JAR:?set BUILD_TOOLS or DX_JAR}" com.android.dx.command.Main --dex --min-sdk-version=21 \
    --output=$B/dex/classes.dex $B/classes
fi

cp $B/unsigned.apk $B/with_dex.apk
(cd $B/dex && zip -q -j ../with_dex.apk classes.dex)

echo "==> Aligning"
if [ -n "${BUILD_TOOLS:-}" ] && [ -x "$BUILD_TOOLS/zipalign" ]; then
  "$BUILD_TOOLS/zipalign" -f -p 4 $B/with_dex.apk $B/aligned.apk
else
  python3 tools/align.py $B/with_dex.apk $B/aligned.apk
fi

echo "==> Signing"
if [ ! -f "$KEYSTORE" ]; then
  echo "WARNING: $KEYSTORE not found - creating a throwaway key. This APK will NOT install over"
  echo "         an existing SlaterLauncher signed with the real key (uninstall first)."
  KEYSTORE=$B/throwaway.keystore; KEYSTORE_PASSWORD=throwaway
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SlaterLauncher" >/dev/null 2>&1
fi
SIGN_ARGS=(sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD}" \
  --key-pass "pass:$KEYSTORE_PASSWORD" --out dist/SlaterLauncher.apk $B/aligned.apk)
if [ -n "${BUILD_TOOLS:-}" ] && [ -x "$BUILD_TOOLS/apksigner" ]; then
  "$BUILD_TOOLS/apksigner" "${SIGN_ARGS[@]}"
  "$BUILD_TOOLS/apksigner" verify dist/SlaterLauncher.apk
else
  java -jar "${APKSIGNER_JAR:?set BUILD_TOOLS or APKSIGNER_JAR}" "${SIGN_ARGS[@]}"
  java -jar "$APKSIGNER_JAR" verify dist/SlaterLauncher.apk
fi
ls -la dist/SlaterLauncher.apk
