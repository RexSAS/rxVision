#!/bin/sh
# Builds build/TvLauncher.apk with the Android SDK command-line tools (no Gradle).
# Install: ~/.local/opt/platform-tools/adb install -r build/TvLauncher.apk
set -e
cd "$(dirname "$0")"

SDK=$HOME/.local/opt/android-sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build
mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 25 --target-sdk-version 30 -A assets -o build/unsigned.apk build/res.zip

javac --release 8 -classpath "$JAR" -Xlint:-options -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8" --min-api 25 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

cp build/unsigned.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f 4 build/app.apk build/aligned.apk

[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android \
    -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=TvLauncher
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:android --out build/TvLauncher.apk build/aligned.apk
echo "Built build/TvLauncher.apk"
