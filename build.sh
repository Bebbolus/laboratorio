#!/usr/bin/env bash
set -euo pipefail

project="$(cd "$(dirname "$0")" && pwd)"
tools="$(cd "$project/../.tools/lab-build" && pwd)"
platform="$tools/platform/android-35/android.jar"
android="$tools/build-tools/android-15"
llama="$tools/llama/llama-b9590"
voice="$tools/voice"
tor="$tools/tor"
apk="$tools/Laboratorio.apk"
link_options=()
if [[ "${1:-}" == "--debug" ]]; then
  apk="$tools/Laboratorio-test.apk"
  link_options+=(--debug-mode)
fi
export JAVA_HOME="$(find "$tools/jdk" -type d -path '*/Contents/Home' -print -quit)"
test -x "$JAVA_HOME/bin/javac"
export PATH="$JAVA_HOME/bin:$PATH"

stage="$(mktemp -d "$tools/apk.XXXXXX")"
trap 'find "$stage" -depth -delete' EXIT
mkdir -p "$stage/classes" "$stage/dex" "$stage/generated" "$stage/payload/lib/arm64-v8a"
unsigned="$stage/unsigned.apk"
aligned="$stage/aligned.apk"
manifest="$project/AndroidManifest.xml"
sources=("$project"/java/it/reno/lab/*.java)
if [[ "${1:-}" == "--debug" ]]; then
  manifest="$stage/AndroidManifest.xml"
  sed 's|</manifest>|  <instrumentation android:name=".VoiceSmoke" android:targetPackage="it.reno.lab"/><instrumentation android:name=".HomeWidgetSmoke" android:targetPackage="it.reno.lab"/></manifest>|' \
    "$project/AndroidManifest.xml" > "$manifest"
  sources+=("$project"/test/it/reno/lab/*.java)
fi

"$android/aapt2" compile --dir "$project/res" -o "$stage/resources.zip"
"$android/aapt2" link -I "$platform" --manifest "$manifest" -A "$project/assets" "${link_options[@]+"${link_options[@]}"}" \
  --java "$stage/generated" -o "$unsigned" "$stage/resources.zip"
javac --release 11 -classpath "$platform:$voice/vosk/classes.jar:$voice/jna/classes.jar" \
  -d "$stage/classes" "$stage/generated/it/reno/lab/R.java" "${sources[@]}"
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$android/d8" --lib "$platform" --min-api 31 --release --output "$stage/dex" "$stage/classes.jar" \
  "$voice/vosk/classes.jar" "$voice/jna/classes.jar"
cp "$stage/dex/classes.dex" "$stage/payload/"
cp "$llama/llama-server" "$stage/payload/lib/arm64-v8a/liblabexec.so"
cp "$llama"/libggml*.so "$llama"/libmtmd.so "$llama"/libllama.so "$llama"/libllama-common.so \
  "$llama"/libllama-server-impl.so "$stage/payload/lib/arm64-v8a/"
cp "$voice/vosk/jni/arm64-v8a/libvosk.so" "$voice/jna/jni/arm64-v8a/libjnidispatch.so" \
  "$stage/payload/lib/arm64-v8a/"
cp "$tor/libTor.so" "$stage/payload/lib/arm64-v8a/liblabtor.so"
mkdir -p "$stage/payload/assets"
cp "$voice/vosk-it.zip" "$stage/payload/assets/voice-it.zip"
cp "$voice/Apache-2.0.txt" "$voice/JNA-LICENSE.txt" "$stage/payload/assets/"
cp "$tor/LICENSE" "$stage/payload/assets/tor-LICENSE.txt"
if [[ "${1:-}" == "--debug" && -f "$tools/dettatura-test.pcm" ]]; then
  cp "$tools/dettatura-test.pcm" "$stage/payload/assets/"
fi
if [[ -f "$llama/LICENSE" ]]; then
  mkdir -p "$stage/payload/assets"
  cp "$llama/LICENSE" "$stage/payload/assets/llama-LICENSE.txt"
fi
(cd "$stage/payload" && zip -qr "$unsigned" .)
"$android/zipalign" -p 4 "$unsigned" "$aligned"

keystore="$tools/laboratorio-signing.p12"
secret="$tools/laboratorio-signing.pass"
if [[ ! -e "$keystore" ]]; then
  (umask 077; openssl rand -hex 24 > "$secret")
  keytool -genkeypair -noprompt -keystore "$keystore" -storetype PKCS12 -storepass:file "$secret" \
    -alias laboratorio -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Laboratorio Reno4'
  chmod 600 "$keystore"
fi
"$android/apksigner" sign --ks "$keystore" --ks-pass "file:$secret" --ks-key-alias laboratorio \
  --out "$apk" "$aligned"
"$android/apksigner" verify --verbose "$apk"
printf '%s\n' "$apk"
