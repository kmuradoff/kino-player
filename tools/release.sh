#!/bin/bash
# Выпуск «Кино»: собрать подписанный APK, сделать kino-update.json и выложить релиз на GitHub.
#   tools/release.sh "что нового (одна-две строки)"
# Нужно: local.properties с kino.* (ключ подписи), gh (вход в GitHub), Gradle (GRADLE=путь, если не в PATH).
set -euo pipefail
cd "$(dirname "$0")/.."
notes=${1:?usage: tools/release.sh "что нового"}
ver=$(sed -n "s/^ *versionName '\(.*\)'.*/\1/p" app/build.gradle)
code=$(sed -n 's/^ *versionCode \([0-9]*\).*/\1/p' app/build.gradle)
grep -q '^kino.storeFile=' local.properties || { echo "нет ключа подписи в local.properties" >&2; exit 1; }
"${GRADLE:-gradle}" -q assembleRelease
out=build/release; mkdir -p "$out"
apk="$out/kino-$ver.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
# APK должен быть подписан релизным ключом, а не отладочным
apksigner=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner | tail -1)
"$apksigner" verify --print-certs "$apk" | grep -q 'CN=Kino' || { echo "APK подписан не ключом Кино" >&2; exit 1; }
sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
size=$(wc -c < "$apk" | tr -d ' ')
python3 - "$out/kino-update.json" "$code" "$ver" "$sha" "$size" "$notes" <<'PY'
import json, sys
f, code, ver, sha, size, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": ver,
           "apk": f"https://github.com/kmuradoff/kino-player/releases/download/v{ver}/kino-{ver}.apk",
           "size": int(size), "sha256": sha, "notes": notes}, open(f, "w"), ensure_ascii=False, indent=1)
PY
gh release create "v$ver" "$apk" "$out/kino-update.json" -R kmuradoff/kino-player --title "Кино $ver" --notes "$notes" --latest
echo "готово: v$ver ($sha)"
