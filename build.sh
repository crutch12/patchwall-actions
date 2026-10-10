#!/bin/sh
# сборка без gradle: aapt2 + javac + d8 + apksigner, на выходе out/patchwall-actions.apk
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-30/android.jar"
win() { cygpath -w "$1"; }

rm -rf out && mkdir -p out/classes out/dex out/gen
"$BT/aapt2.exe" compile --dir res -o out/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -o out/unsigned.apk out/res.zip --java out/gen
javac -nowarn -encoding UTF-8 --release 8 -classpath "$JAR" -d out/classes $(find src out/gen -name "*.java")
cmd //c "$(win "$BT/d8.bat")" --min-api 21 --lib "$(win "$JAR")" --output "$(win out/dex)" $(find out/classes -name "*.class")
(cd out/dex && jar uf ../unsigned.apk classes.dex)

[ -f patchwall-actions.keystore ] || keytool -genkeypair -keystore patchwall-actions.keystore -storepass android -keypass android \
    -alias key -keyalg RSA -validity 10000 -dname "CN=patchwall-actions" >/dev/null 2>&1
cmd //c "$(win "$BT/apksigner.bat")" sign --ks patchwall-actions.keystore --ks-pass pass:android \
    --out "$(win out/patchwall-actions.apk)" "$(win out/unsigned.apk)"
ls -la out/patchwall-actions.apk
