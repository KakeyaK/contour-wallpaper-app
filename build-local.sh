#!/usr/bin/env bash
# Build without Gradle/Android Studio, using only Ubuntu packages + kotlinc.
#   apt install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk-headless
#   kotlinc: https://github.com/JetBrains/kotlin/releases  (unzip into /opt/kotlinc)
# Compiles against the API 23 android.jar (the code only uses old APIs); minSdk 26 in the manifest.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KOTLINC=${KOTLINC:-/opt/kotlinc/bin/kotlinc}
KOTLIN_STDLIB=${KOTLIN_STDLIB:-/opt/kotlinc/lib/kotlin-stdlib.jar}
DX=${DX:-dalvik-exchange}
OUT=build-local
SRC=app/src/main

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex,stdlib}

# Manifest with min/target SDK (Gradle injects this; here it is done by hand).
sed -e 's|<application|<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />\n    <application|' \
    -e 's|<manifest |<manifest package="dev.contour.wallpaper" android:versionCode="1" android:versionName="1.0" |' \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "[1/6] aapt2 compile/link"
aapt2 compile --dir "$SRC/res" -o "$OUT/res/res.zip"
aapt2 link -o "$OUT/app-unaligned.apk" -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" -A "$SRC/assets" \
    --java "$OUT/gen" --auto-add-overlay "$OUT/res/res.zip"

echo "[2/6] javac (R.java)"
javac --release 8 -cp "$ANDROID_JAR" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "[3/6] kotlinc"
"$KOTLINC" -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
    -no-reflect -cp "$ANDROID_JAR:$OUT/classes" -d "$OUT/classes" $(find "$SRC/java" -name '*.kt')

echo "[4/6] stdlib without module-info (dx cannot read classfiles > Java 8)"
(cd "$OUT/stdlib" && unzip -q "$KOTLIN_STDLIB" -x 'module-info.class' 'META-INF/versions/*')

echo "[5/6] dx"
"$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes" "$OUT/stdlib"

echo "[6/6] package, align, sign"
(cd "$OUT/dex" && zip -q ../app-unaligned.apk classes.dex)
zipalign -f -p 4 "$OUT/app-unaligned.apk" "$OUT/app-aligned.apk"
KS="$OUT/debug.keystore"
[ -f "$HOME/.android/debug.keystore" ] && KS="$HOME/.android/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT/contour-debug.apk" "$OUT/app-aligned.apk"
apksigner verify "$OUT/contour-debug.apk"
echo "OK -> $OUT/contour-debug.apk"
