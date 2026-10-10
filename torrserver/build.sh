#!/bin/bash
# Встроенный торрент-движок «Кино»: TorrServer (GPL-3.0, https://github.com/YouROK/TorrServer)
# из подмодуля torrserver/upstream плюс наши патчи из torrserver/patches. Собирается отдельной
# программой под Android и кладётся в APK как lib/<abi>/libtorrserver.so; «Кино» запускает её
# дочерним процессом и говорит с ней только по HTTP.
#   torrserver/build.sh                 собрать оба ABI в torrserver/out/jniLibs (пропуск, если ничего не менялось)
#   torrserver/build.sh --source F.tgz  архив исходников для релиза: upstream + патчи + vendor + этот скрипт
# Нужно: Go (версия не ниже строки go в server/go.mod), Android NDK (ANDROID_NDK_HOME, или ndk/ в SDK из
# ANDROID_HOME / sdk.dir в local.properties). Сеть нужна в первый раз: Go скачивает модули.
set -euo pipefail
ts=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$ts")
up=$ts/upstream
work=$ts/build
out=$ts/out/jniLibs
api=26                                  # minSdk «Кино»
tags=nosqlite,kino,nomsgpack            # kino: без веб-интерфейса, Swagger, MCP, WebDAV, FUSE, Telegram
version=$(cat "$ts/VERSION")            # тег upstream, его же отдаёт /echo (Лампа смотрит на «MatriX»)

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

# Чистая копия upstream (всё дерево или только server/) с наложенными патчами; сам подмодуль не трогаем.
# Своё git init: иначе git apply внутри репозитория «Кино» искал бы пути от его корня.
prepare() {
  rm -rf "$work/src"; mkdir -p "$work/src"
  git -C "$up" archive --format=tar HEAD "$@" | tar -x -C "$work/src"
  ( cd "$work/src" && git init -q && for p in "$ts"/patches/*.patch; do git apply -p1 "$p"; done && rm -rf .git )
}

if [ "${1:-}" = "--source" ]; then
  dest=${2:?usage: torrserver/build.sh --source FILE.tar.gz}
  prepare
  # Готовые библиотеки GStreamer для Windows/Linux/macOS в этой сборке не участвуют (тег gst не задан).
  rm -rf "$work/src/server/gstreamer/gst-libs"
  ( cd "$work/src/server" && go mod vendor )
  mkdir -p "$work/src/kino"
  cp -R "$ts/build.sh" "$ts/VERSION" "$ts/patches" "$work/src/kino/"
  cat > "$work/src/kino/README.txt" <<EOF
TorrServer $version ($head) as built into the Kino player for Android TV.

Upstream: https://github.com/YouROK/TorrServer, licence GPL-3.0 (LICENSE).
This tree is upstream at that commit with kino/patches applied, Go modules vendored
(server/vendor), and server/gstreamer/gst-libs removed (prebuilt GStreamer libraries
for desktop systems, not used by this build).

Build, per ABI (NDK clang for API $api):
  cd server
  CC=<ndk>/bin/aarch64-linux-android$api-clang GOOS=android GOARCH=arm64 CGO_ENABLED=1 \\
    go build -mod=vendor -trimpath -buildvcs=false -tags $tags \\
    -ldflags "-s -w -checklinkname=0 -X server/version.Version=$version" -o libtorrserver.so ./cmd
  armeabi-v7a: CC=<ndk>/bin/armv7a-linux-androideabi$api-clang GOARCH=arm GOARM=7
EOF
  mkdir -p "$(dirname "$dest")"
  dest=$(cd "$(dirname "$dest")" && pwd)/$(basename "$dest")
  tar -C "$work/src" -czf "$dest" .
  echo "исходники: $dest"
  exit 0
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
[ -d "$ndk" ] || { echo "нет Android NDK: задайте ANDROID_NDK_HOME или поставьте ndk в SDK" >&2; exit 1; }
host=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64       # у NDK для macOS одна сборка, и на Apple Silicon тоже
tc="$ndk/toolchains/llvm/prebuilt/$host/bin"
command -v go >/dev/null || { echo "нет Go (https://go.dev/dl/); без движка: gradle -Pkino.noTorrServer ..." >&2; exit 1; }

# Пропуск, если ничего не поменялось: коммит upstream, патчи, этот скрипт, Go и NDK.
stamp=$( { echo "$head $version $tags $api"; go version; basename "$ndk"; cat "$ts"/patches/*.patch "$0"; } | shasum -a 256 | cut -d' ' -f1)
if [ -f "$out/stamp" ] && [ "$(cat "$out/stamp")" = "$stamp" ] \
   && [ -f "$out/arm64-v8a/libtorrserver.so" ] && [ -f "$out/armeabi-v7a/libtorrserver.so" ]; then
  echo "torrserver $version: уже собран"
  exit 0
fi

prepare server
ldflags="-s -w -checklinkname=0 -X server/version.Version=$version"
build() { # abi goarch goarm clang
  mkdir -p "$out/$1"
  echo "torrserver $version: $1"
  ( cd "$work/src/server" && CC="$tc/$4" GOOS=android GOARCH=$2 GOARM=$3 CGO_ENABLED=1 \
    go build -trimpath -buildvcs=false -tags "$tags" -ldflags "$ldflags" -o "$out/$1/libtorrserver.so" ./cmd )
}
rm -f "$out/stamp"
build arm64-v8a arm64 "" "aarch64-linux-android$api-clang"
build armeabi-v7a arm 7 "armv7a-linux-androideabi$api-clang"
echo "$stamp" > "$out/stamp"
ls -l "$out"/*/libtorrserver.so
