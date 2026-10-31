#!/usr/bin/env bash
# Compila el APK sin Gradle (aapt2 → javac → d8 → zipalign → apksigner). Salida: public/selfie-beep.apk
# Requiere JDK 17 y Android SDK (platform 34 + build-tools 34.0.0) en $ANDROID_TOOLS.
set -euo pipefail
TOOLS=${ANDROID_TOOLS:-/c/Users/Kernel/android-tools}
JAVA_HOME=$(echo "$TOOLS"/jdk/jdk-17*); export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
BT=$TOOLS/sdk/build-tools/34.0.0
JAR=$TOOLS/sdk/platforms/android-34/android.jar

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/res/mipmap-xxxhdpi build/classes build/dex
cp ../public/icon-192.png build/res/mipmap-xxxhdpi/ic_launcher.png

"$BT/aapt2" compile --dir build/res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 -o build/app.apk build/res.zip
javac -encoding UTF-8 --release 8 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8.bat" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
jar -u -M -f build/app.apk -C build/dex classes.dex
"$BT/zipalign" -f -p 4 build/app.apk build/aligned.apk

# La misma clave de firma siempre: si cambia, Android no deja actualizar la app instalada
[ -f selfie-beep.keystore ] || keytool -genkeypair -keystore selfie-beep.keystore -alias selfiebeep \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass selfiebeep -keypass selfiebeep -dname "CN=Selfie Beep"
"$BT/apksigner.bat" sign --ks selfie-beep.keystore --ks-pass pass:selfiebeep --v4-signing-enabled false \
  --out ../public/selfie-beep.apk build/aligned.apk
"$BT/apksigner.bat" verify ../public/selfie-beep.apk
echo "OK → public/selfie-beep.apk"
