#!/bin/bash
# Builds WebCraft 2 → /root/WebCraft.apk and "Apps by antigravity/webcraft/WebCraft.apk"
set -e
PROJECT_DIR="/root/webcraft-app"
TOOLS="/root/android-build-tools"
cd "$PROJECT_DIR"
rm -rf build gen && mkdir -p build/obj build/apk gen

echo "1. Generating R.java..."
aapt package -f -m -J gen -M AndroidManifest.xml -S res -I $TOOLS/android.jar

echo "2. Compiling Java sources..."
# LambdaMetafactory stub is compile-time only (android.jar lacks it; D8 desugars lambdas)
mkdir -p build/stubs
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath $TOOLS/android.jar -d build/stubs $(find stubs -name "*.java")
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath $TOOLS/android.jar:build/stubs -cp $TOOLS/android.jar \
      -d build/obj $(find src gen -name "*.java")

echo "3. Converting to DEX (D8, release)..."
java -cp $TOOLS/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $TOOLS/android.jar \
     --output build/apk $(find build/obj -name "*.class")

echo "4. Packaging resources & assets..."
aapt package -f -M AndroidManifest.xml -S res -A assets -I $TOOLS/android.jar -F build/unaligned.apk
(cd build/apk && aapt add ../unaligned.apk classes.dex >/dev/null)

echo "5. Aligning & signing..."
zipalign -f -p 4 build/unaligned.apk build/aligned.apk
apksigner sign --ks $TOOLS/release.keystore --ks-pass pass:android --key-pass pass:android \
    --out build/WebCraft.apk build/aligned.apk
apksigner verify build/WebCraft.apk

cp build/WebCraft.apk /root/WebCraft.apk
OUT="/storage/emulated/0/Apps by antigravity/webcraft"
mkdir -p "$OUT" && cp build/WebCraft.apk "$OUT/WebCraft.apk"
echo "OK: $(du -h build/WebCraft.apk | cut -f1)  →  /root/WebCraft.apk  and  $OUT/WebCraft.apk"
