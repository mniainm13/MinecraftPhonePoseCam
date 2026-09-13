#!/bin/bash
set -ex

PROJECT="/mnt/sdcard/workspace/frost-demo"
ANDROID_JAR="/root/.nitron/android/android.jar"
AAPT="/usr/bin/aapt"
D8_JAR="/tmp/r8.jar"
DX="/usr/lib/android-sdk/build-tools/debian/dx"
SIGNER_JAR="/mnt/sdcard/workspace/node_modules/nitron/vendor/uber-apk-signer.jar"

BUILD="/tmp/frost-build"
OUT_APK="/mnt/sdcard/workspace/download/FrostBlurDemo-v1.2.apk"

echo "=== Clean ==="
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj/classes" "$BUILD/apk_tmp"

echo "=== aapt: generate R.java ==="
$AAPT package -f -m \
    -J "$BUILD/gen" \
    -M "$PROJECT/AndroidManifest.xml" \
    -S "$PROJECT/res" \
    -I "$ANDROID_JAR"

echo "=== javac: compile Java ==="
find "$PROJECT/src" "$BUILD/gen" -name "*.java" > "$BUILD/java_files.txt"
cat "$BUILD/java_files.txt"

javac \
    --release 8 \
    -g:none \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/obj/classes" \
    @"$BUILD/java_files.txt"

echo "=== dx: convert to DEX ==="
find "$BUILD/obj/classes" -name "*.class" > "$BUILD/class_files.txt"

if [ -f "$D8_JAR" ]; then
  java -cp "$D8_JAR" com.android.tools.r8.D8 \
      --output "$BUILD" \
      --lib "$ANDROID_JAR" \
      --min-api 26 \
      --release \
      @"$BUILD/class_files.txt"
else
  "$DX" --dex --output="$BUILD/classes.dex" "$BUILD/obj/classes"
fi

ls -la "$BUILD/classes.dex"

echo "=== aapt: package resources ==="
$AAPT package -f \
    -M "$PROJECT/AndroidManifest.xml" \
    -S "$PROJECT/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD/app-unsigned-raw.apk"

echo "=== merge: add DEX to APK ==="
cd "$BUILD/apk_tmp"
unzip -o "$BUILD/app-unsigned-raw.apk"
cp "$BUILD/classes.dex" .
python3 -c "
import zipfile, os
with zipfile.ZipFile('$BUILD/app-unsigned.apk', 'w') as zf:
    for root, dirs, files in os.walk('.'):
        for f in files:
            fp = os.path.join(root, f)
            arcname = fp[2:] if fp.startswith('./') else fp
            with open(fp, 'rb') as src:
                data = src.read()
            if arcname == 'resources.arsc':
                info = zipfile.ZipInfo(arcname, date_time=(2026,1,1,0,0,0))
                info.compress_type = zipfile.ZIP_STORED
                zf.writestr(info, data)
            else:
                info = zipfile.ZipInfo(arcname, date_time=(2026,1,1,0,0,0))
                info.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(info, data)
"
cd "$PROJECT"
rm -rf "$BUILD/apk_tmp"

echo "=== sign: uber-apk-signer ==="
java -jar "$SIGNER_JAR" \
    --apks "$BUILD/app-unsigned.apk" \
    --out "$BUILD/signed" \
    --allowResign \
    --zipAlignPath /usr/bin/zipalign

SIGNED_APK=$(find "$BUILD/signed" -name "*.apk" | head -1)
mkdir -p "$(dirname "$OUT_APK")"
cp "$SIGNED_APK" "$OUT_APK"

echo ""
echo "=== BUILD SUCCESS ==="
ls -lh "$OUT_APK"
