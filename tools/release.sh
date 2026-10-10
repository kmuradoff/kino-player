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
apksigner=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner | tail -1)
# С 0.9 два APK: arm64-v8a и armeabi-v7a. Каждый должен быть подписан релизным ключом, а не отладочным.
args=()
for abi in arm64-v8a armeabi-v7a; do
  apk="$out/kino-$ver-$abi.apk"
  cp "app/build/outputs/apk/release/app-$abi-release.apk" "$apk"
  "$apksigner" verify --print-certs "$apk" | grep -q 'CN=Kino' || { echo "$apk подписан не ключом Кино" >&2; exit 1; }
  unzip -l "$apk" | grep -q "lib/$abi/libvlc.so" || { echo "$apk: нет lib/$abi" >&2; exit 1; }
  args+=("$abi" "$(shasum -a 256 "$apk" | cut -d' ' -f1)" "$(wc -c < "$apk" | tr -d ' ')")
done
python3 - "$out/kino-update.json" "$code" "$ver" "$notes" "${args[@]}" <<'PY'
import json, sys
f, code, ver, notes, *rest = sys.argv[1:]
url = f"https://github.com/kmuradoff/kino-player/releases/download/v{ver}/kino-{ver}-%s.apk"
apks = {rest[i]: {"apk": url % rest[i], "size": int(rest[i + 2]), "sha256": rest[i + 1]}
        for i in range(0, len(rest), 3)}
# верхние apk/size/sha256: сборка arm64, их читает 0.8 (она была только 64-битной)
json.dump({"versionCode": int(code), "versionName": ver, **apks["arm64-v8a"], "notes": notes,
           "apks": apks}, open(f, "w"), ensure_ascii=False, indent=1)
PY
gh release create "v$ver" "$out/kino-$ver-arm64-v8a.apk" "$out/kino-$ver-armeabi-v7a.apk" "$out/kino-update.json" \
  -R kmuradoff/kino-player --title "Кино $ver" --notes "$notes" --latest
echo "готово: v$ver"
