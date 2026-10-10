# Кино

[Русский](#русский) | [English](#english)

## Обновления

«Кино» само проверяет новые версии на GitHub раз в 12 часов и показывает плашку на главном экране. Обновление ставится по OK. В первый раз Android попросит разрешить «Кино» установку приложений.

В каждом релизе с 0.9 два APK: `kino-<версия>-arm64-v8a.apk` для 64-битных устройств (Z9X и почти все приставки и телевизоры последних лет) и `kino-<версия>-armeabi-v7a.apk` для 32-битных. Если не знаете, какой нужен, ставьте arm64: на 32-битное устройство Android его просто не установит. Обновление само скачивает APK под ваше устройство.

Version 0.8 and later update themselves from the GitHub releases. Builds before 0.8 were signed with a debug key: remove the old Kino once and install 0.8.

Since 0.9 every release has two APKs: `kino-<version>-arm64-v8a.apk` for 64-bit devices (the Z9X and nearly every recent box or TV) and `kino-<version>-armeabi-v7a.apk` for 32-bit ones. If unsure, take arm64: a 32-bit device simply refuses to install it. Updates download the APK that fits the device.

## Русский

«Кино» это видеоплеер для Android TV, который мы используем на проекторах с
[Lumen OS](https://github.com/kmuradoff/lumen-os) (первая цель: XGIMI Z9X). Интерфейс сделан
под пульт, язык интерфейса русский.

Что умеет:

- **Два движка, один интерфейс.** Media3 (ExoPlayer) играет MKV, MP4 и TS через аппаратные
  декодеры, включая 4K HDR. libVLC берёт старые форматы (AVI, XviD, WMV и подобные) и
  подхватывает файл с того же места, если Media3 не справился.
- **Dolby Vision профиля 5** там, где прошивка его не поддерживает: плеер сам разбирает
  метаданные RPU каждого кадра, а шейдер OpenGL ES переводит картинку в HDR10 или в SDR с
  тон-маппингом. У профилей 7 и 8 слой Dolby Vision вырезается, и видео идёт как обычный HDR10.
- **Внешний плеер для Лампы, TorrServe и других приложений.** Понимает параметры, которые
  передают MX Player и VLC (название, позиция, список серий, субтитры), и возвращает позицию,
  на которой вы остановились.
- «Продолжить просмотр» на главном экране, предложение следующей серии за 30 секунд до конца,
  скорость загрузки и число пиров TorrServe.
- Панель «звук, субтитры, скорость, экран» по кнопке вниз; перемотка ускоряется, пока кнопку
  держат (10 с, 30 с, 1 мин, 2 мин).

### Сборка

Нужны JDK 17, Android SDK с платформой 36 и Gradle 8.10 или новее в ветке 8.x (проверено на
8.14.3). Можно просто открыть папку в Android Studio.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # или sdk.dir в local.properties
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`gradle assembleRelease` собирает неподписанный APK; подпишите его своим ключом (`apksigner`).
Ключи, `local.properties` и результаты сборки в репозиторий не попадают (см. `.gitignore`).

Собираются два APK, `arm64-v8a` и `armeabi-v7a` (`splits.abi` в `app/build.gradle`):
`app/build/outputs/apk/<тип>/app-<abi>-<тип>.apk`. Иконку и баннер рисует `art/make_icons.py`.

### Лицензия

- Код проекта распространяется по лицензии Apache 2.0 (`LICENSE`).
- `DoviRpu.kt` и `DoviRenderer.kt` в пакете `dovi` распространяются по LGPL-2.1-or-later
  (`LICENSES/LGPL-2.1-or-later.txt`): это порт разбора RPU из FFmpeg и шейдера Dolby Vision из
  libplacebo.
- Собранный APK содержит FFmpeg-декодер Jellyfin под GPL-3.0, поэтому APK целиком
  распространяется на условиях GPL-3.0.

Подробности и авторы исходного кода в `NOTICE`. Проект не связан с XGIMI, Dolby, VideoLAN
или Google. Dolby Vision является товарным знаком Dolby Laboratories.

## English

Kino («Кино», Russian for "cinema") is a video player for Android TV. We use it on projectors
running [Lumen OS](https://github.com/kmuradoff/lumen-os) (first target: XGIMI Z9X). It is
built for a TV remote, and its interface is in Russian.

Features:

- **Two engines behind one interface.** Media3 (ExoPlayer) plays MKV, MP4 and TS on the
  hardware decoders, 4K HDR included. libVLC handles legacy formats (AVI, XviD, WMV and the
  like) and takes over at the same position when Media3 fails on a file.
- **Dolby Vision profile 5** on firmware that cannot decode it: the player parses the RPU
  metadata of every frame, and an OpenGL ES shader converts the picture to HDR10 or to
  tone-mapped SDR. For profiles 7 and 8 the Dolby Vision layer is stripped and the video plays
  as plain HDR10.
- **External player for Lampa, TorrServe and other apps.** It understands the intent extras
  used with MX Player and VLC (title, position, playlist, subtitles) and returns the position
  where you stopped.
- "Continue watching" on the home screen, a next-episode prompt 30 seconds before the end,
  TorrServe download speed and peer count.
- An audio, subtitles, speed and zoom panel on the Down key; seeking speeds up while the key is
  held (10 s, 30 s, 1 min, 2 min).

### Building

You need JDK 17, the Android SDK with platform 36, and Gradle 8.10 or a later 8.x (tested with
8.14.3). Opening the folder in Android Studio works too.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or sdk.dir in local.properties
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`gradle assembleRelease` produces an unsigned APK; sign it with your own key (`apksigner`).
Keys, `local.properties` and build output are never committed (see `.gitignore`).

Two APKs are built, `arm64-v8a` and `armeabi-v7a` (`splits.abi` in `app/build.gradle`):
`app/build/outputs/apk/<type>/app-<abi>-<type>.apk`. `art/make_icons.py` draws the icon and the TV banner.

### Licence

- The project's own code is licensed under the Apache License 2.0 (`LICENSE`).
- `DoviRpu.kt` and `DoviRenderer.kt` in the `dovi` package are licensed under
  LGPL-2.1-or-later (`LICENSES/LGPL-2.1-or-later.txt`), because they port the RPU parser from
  FFmpeg and the Dolby Vision shader from libplacebo.
- A built APK includes the Jellyfin FFmpeg decoder, which is GPL-3.0, so the APK as a whole is
  distributed under GPL-3.0.

See `NOTICE` for details and the authors of the original code. This project is not affiliated
with XGIMI, Dolby, VideoLAN or Google. Dolby Vision is a trademark of Dolby Laboratories.
