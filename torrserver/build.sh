#!/bin/bash
# Встроенный торрент-движок «Кино»: TorrServer (GPL-3.0, https://github.com/YouROK/TorrServer)
# из подмодуля torrserver/upstream плюс наши патчи из torrserver/patches. Собирается отдельной
# программой под Android и кладётся в APK как lib/<abi>/libtorrserver.so; «Кино» запускает её
# дочерним процессом и говорит с ней только по HTTP.
#   torrserver/build.sh                 собрать оба ABI в torrserver/out/jniLibs (пропуск, если ничего не менялось)
#   torrserver/build.sh --source F.tgz  архив исходников для релиза: upstream + патчи + vendor + этот скрипт
# Нужно: Go (версия не ниже строки go в server/go.mod), Android NDK (ANDROID_NDK_HOME, или ndk/ в SDK из
# ANDROID_HOME / sdk.dir в local.properties). Сеть нужна в первый раз: Go скачивает модули.
#
# Сборка идёт из той же папки, что уходит в архив исходников (модули Go в server/vendor), поэтому
# из архива с тем же Go и NDK получается байт в байт тот же libtorrserver.so, что лежит в APK.
set -euo pipefail
ts=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$ts")
up=$ts/upstream
work=$ts/build
src=$work/src
out=$ts/out/jniLibs
api=26                                  # minSdk «Кино»
tags=nosqlite,kino,nomsgpack            # kino: без веб-интерфейса, Swagger, MCP, WebDAV, FUSE, Telegram
version=$(cat "$ts/VERSION")            # тег upstream, его же отдаёт /echo (Лампа смотрит на «MatriX»)
ldflags="-s -w -checklinkname=0 -X server/version.Version=$version"

command -v go >/dev/null || { echo "нет Go (https://go.dev/dl/); без движка: gradle -Pkino.noTorrServer ..." >&2; exit 1; }

# Подмодуль: скачать, если его нет, и проверить, что это ровно закреплённый коммит.
pinned=$(git -C "$root" ls-tree HEAD torrserver/upstream 2>/dev/null | awk '{print $3}')
if [ ! -e "$up/.git" ]; then
  git -C "$root" submodule update --init --depth 1 torrserver/upstream
fi
head=$(git -C "$up" rev-parse HEAD)
if [ -n "$pinned" ] && [ "$head" != "$pinned" ]; then
  echo "torrserver/upstream на $head, а закреплён $pinned: git submodule update torrserver/upstream" >&2
  exit 1
fi

# NDK
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
  sdk=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")
fi
ndk=${ANDROID_NDK_HOME:-}
if [ -z "$ndk" ] && [ -n "$sdk" ] && [ -d "$sdk/ndk" ]; then
  ndk="$sdk/ndk/$(ls "$sdk/ndk" | sort -V | tail -1)"
fi
ndkver=$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk/source.properties" 2>/dev/null || true)

# Чистое дерево upstream с нашими патчами и модулями Go в server/vendor; сам подмодуль не трогаем.
# Своё git init: иначе git apply внутри репозитория «Кино» искал бы пути от его корня.
# Готовые библиотеки GStreamer для Windows/Linux/macOS не участвуют (тег gst не задан): убираем.
prepare() {
  rm -rf "$src"; mkdir -p "$src"
  git -C "$up" archive --format=tar HEAD | tar -x -C "$src"
  ( cd "$src" && git init -q && for p in "$ts"/patches/*.patch; do git apply -p1 "$p"; done && rm -rf .git )
  rm -rf "$src/server/gstreamer/gst-libs"
  ( cd "$src/server" && go mod vendor )
}

# Пропуск, если ничего не поменялось: коммит upstream, патчи, этот скрипт, Go и NDK.
stamp=$( { echo "$head $version $tags $api $ndkver"; go version; cat "$ts"/patches/*.patch "$0"; } | shasum -a 256 | cut -d' ' -f1)
fresh() { [ -f "$out/stamp" ] && [ "$(cat "$out/stamp")" = "$stamp" ] && [ -d "$src/server/vendor" ] \
  && [ -f "$out/arm64-v8a/libtorrserver.so" ] && [ -f "$out/armeabi-v7a/libtorrserver.so" ]; }

if [ "${1:-}" = "--source" ]; then
  dest=${2:?usage: torrserver/build.sh --source FILE.tar.gz}
  fresh || { echo "сначала соберите движок: torrserver/build.sh" >&2; exit 1; }
  mkdir -p "$src/kino"
  cp -R "$ts/build.sh" "$ts/VERSION" "$ts/patches" "$src/kino/"
  cat > "$src/kino/README.txt" <<EOF
TorrServer $version ($head) as built into the Kino player for Android TV
(https://github.com/kmuradoff/kino-player).

Upstream: https://github.com/YouROK/TorrServer, licence GPL-3.0 (LICENSE).
This tree is upstream at that commit with kino/patches applied and Go modules vendored
(server/vendor). server/gstreamer/gst-libs (prebuilt GStreamer libraries for desktop
systems) is removed: this build does not use it.

Built with $(go version | cut -d' ' -f3-) and Android NDK $ndkver, per ABI:
  cd server
  CC=<ndk>/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android$api-clang \\
    GOOS=android GOARCH=arm64 CGO_ENABLED=1 go build -mod=vendor -trimpath -buildvcs=false \\
    -tags $tags -ldflags "$ldflags" -o libtorrserver.so ./cmd
  armeabi-v7a: CC=.../armv7a-linux-androideabi$api-clang GOARCH=arm GOARM=7

With the same Go and NDK this gives the exact files shipped in the APKs:
  arm64-v8a    lib/arm64-v8a/libtorrserver.so    sha256 $(shasum -a 256 "$out/arm64-v8a/libtorrserver.so" | cut -d' ' -f1)
  armeabi-v7a  lib/armeabi-v7a/libtorrserver.so  sha256 $(shasum -a 256 "$out/armeabi-v7a/libtorrserver.so" | cut -d' ' -f1)
EOF
  mkdir -p "$(dirname "$dest")"
  dest=$(cd "$(dirname "$dest")" && pwd)/$(basename "$dest")
  tar -C "$src" -czf "$dest" .
  rm -rf "$src/kino"
  echo "исходники: $dest"
  exit 0
fi

if fresh; then
  echo "torrserver $version: уже собран"
  exit 0
fi

[ -d "$ndk" ] || { echo "нет Android NDK: задайте ANDROID_NDK_HOME или поставьте ndk в SDK" >&2; exit 1; }
host=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64       # у NDK для macOS одна сборка, и на Apple Silicon тоже
tc="$ndk/toolchains/llvm/prebuilt/$host/bin"

prepare
build() { # abi goarch goarm clang
  mkdir -p "$out/$1"
  echo "torrserver $version: $1"
  ( cd "$src/server" && CC="$tc/$4" GOOS=android GOARCH=$2 GOARM=$3 CGO_ENABLED=1 \
    go build -mod=vendor -trimpath -buildvcs=false -tags "$tags" -ldflags "$ldflags" -o "$out/$1/libtorrserver.so" ./cmd )
}
rm -f "$out/stamp"
build arm64-v8a arm64 "" "aarch64-linux-android$api-clang"
build armeabi-v7a arm 7 "armv7a-linux-androideabi$api-clang"
echo "$stamp" > "$out/stamp"
ls -l "$out"/*/libtorrserver.so
