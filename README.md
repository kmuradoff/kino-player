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
- **Dolby Vision через проектор, как в Kodi**: если экран и декодер умеют Dolby Vision, плеер отдаёт
  видео им, а профиль 7 (двухслойные BD-remux) на лету переводит в 8.1, как libdovi в Kodi.
  В настройках можно убрать поля кадра (L5) и выключить HDR10+. Пункты видны только там, где
  устройство это умеет.
- **Внешний плеер для Лампы, TorrServe и других приложений.** Понимает параметры, которые
  передают MX Player и VLC (название, позиция, список серий, субтитры), и возвращает позицию,
  на которой вы остановились.
- **Торренты без отдельного TorrServe.** Внутри «Кино» есть свой торрент-движок
  ([TorrServer](https://github.com/YouROK/TorrServer)): magnet-ссылки и .torrent-файлы
  открываются сразу, без других приложений. Можно по-прежнему пользоваться внешним TorrServe.
- «Продолжить просмотр» на главном экране, скорость загрузки и число пиров TorrServe.
- **Следующая серия на титрах.** Если в MKV есть глава с титрами («Титры», «Credits»,
  «Ending», «ED»), с её начала появляется карточка следующей серии и через 10 секунд серия
  меняется сама (Назад: досмотреть титры). Без такой главы карточка появляется за 30 секунд
  до конца. В конце серии следующая включается сама.
- Панель «звук, субтитры, скорость, экран» по кнопке вниз; перемотка ускоряется, пока кнопку
  держат (10 с, 30 с, 1 мин, 2 мин).

### Встроенный торрент-движок

Внутри APK лежит TorrServer, собранный отдельной программой (`lib/<abi>/libtorrserver.so`).
Когда вы открываете magnet-ссылку или .torrent-файл, «Кино» запускает его, показывает список видео
из торрента и играет выбранное; остальные серии идут следом. Субтитры .srt/.ass из торрента
подключаются сами.

- Движок слушает только 127.0.0.1, из сети его не видно. Порт случайный.
- Работает, пока вы смотрите торрент, и останавливается через полторы минуты после выхода из
  плеера. Пока работает, висит служба переднего плана, чтобы прошивка его не убила.
- Кэш в памяти подбирается по объёму памяти устройства: 48 МБ до 1,2 ГБ, 64 МБ до 1,8 ГБ, 96 МБ до
  2,5 ГБ, дальше 128 МБ. В настройках можно задать свой.
- **Настройки** (кнопка на главном экране): встроенный движок или внешний TorrServe по адресу.
  По умолчанию встроенный, если адрес внешнего не задан. Видео из Лампы всегда играет через
  тот TorrServe, что указан в самой Лампе.
- **Для Лампы и других приложений** (по умолчанию выключено): движок работает постоянно на
  127.0.0.1:8090 и запускается при включении устройства. Тогда в Лампе можно указать
  TorrServe `127.0.0.1:8090` и не ставить отдельный TorrServe. Отдельный TorrServe на том же порту
  при этом должен быть выключен.
- Журнал движка: `Android/data/com.xgimiru.player/files/torrserver.log`.

Движок добавляет к каждому APK примерно 8 МБ (около 22 МБ после установки). Зато все библиотеки в APK
теперь сжаты, поэтому сами APK стали меньше: 35 МБ вместо 58 (arm64) и 33 МБ вместо 45 (32 бита).

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

Для встроенного торрент-движка ещё нужны Go (1.25 или новее) и Android NDK. Gradle сам вызывает
`torrserver/build.sh`: он достаёт подмодуль `torrserver/upstream` (TorrServer, закреплённый
коммит), накладывает патчи из `torrserver/patches` и собирает программу под оба ABI. Без Go:
`gradle -Pkino.noTorrServer assembleDebug`, тогда APK будет без встроенного движка.

Собираются два APK, `arm64-v8a` и `armeabi-v7a` (`splits.abi` в `app/build.gradle`):
`app/build/outputs/apk/<тип>/app-<abi>-<тип>.apk`. Иконку и баннер рисует `art/make_icons.py`.

### Лицензия

- Код проекта распространяется по лицензии Apache 2.0 (`LICENSE`).
- `DoviRpu.kt` и `DoviRenderer.kt` в пакете `dovi` распространяются по LGPL-2.1-or-later
  (`LICENSES/LGPL-2.1-or-later.txt`): это порт разбора RPU из FFmpeg и шейдера Dolby Vision из
  libplacebo.
- Собранный APK содержит FFmpeg-декодер Jellyfin под GPL-3.0, поэтому APK целиком
  распространяется на условиях GPL-3.0.
- Встроенный торрент-движок это TorrServer (GPL-3.0), отдельная программа: «Кино» запускает её и
  говорит с ней по HTTP. Её исходники: подмодуль `torrserver/upstream`, наши изменения в
  `torrserver/patches`, сборка в `torrserver/build.sh`. К каждому релизу приложен архив
  `torrserver-<версия>-src.tar.gz` с полными исходниками той сборки, что лежит в APK.

Подробности и авторы исходного кода в `NOTICE`. Проект не связан с XGIMI, Dolby, VideoLAN,
Google или авторами TorrServer. Dolby Vision является товарным знаком Dolby Laboratories.

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
- **Dolby Vision on the display, as in Kodi**: when the display and the decoder support Dolby Vision,
  the player hands the video to them and converts profile 7 (dual-layer BD remuxes) to 8.1 on the
  fly, as Kodi does with libdovi. Settings can remove the frame borders (L5) and turn HDR10+ off.
  These options appear only where the device supports them.
- **External player for Lampa, TorrServe and other apps.** It understands the intent extras
  used with MX Player and VLC (title, position, playlist, subtitles) and returns the position
  where you stopped.
- **Torrents without a separate TorrServe.** Kino carries its own torrent engine
  ([TorrServer](https://github.com/YouROK/TorrServer)): magnet links and .torrent files open
  directly, no other app needed. An external TorrServe still works too.
- "Continue watching" on the home screen, TorrServe download speed and peer count.
- **Next episode at the credits.** If an MKV has a credits chapter ("Титры", "Credits",
  "Ending", "ED"), a next-episode card appears when it starts and the next episode begins after
  10 seconds (Back keeps the credits). Without such a chapter the card appears 30 seconds before
  the end. At the end the next episode starts by itself.
- An audio, subtitles, speed and zoom panel on the Down key; seeking speeds up while the key is
  held (10 s, 30 s, 1 min, 2 min).

### Built-in torrent engine

The APK carries TorrServer, built as a separate program (`lib/<abi>/libtorrserver.so`). When you
open a magnet link or a .torrent file, Kino starts it, lists the videos in the torrent and plays
the one you pick, with the rest queued as next episodes. .srt/.ass subtitles from the torrent are
added automatically.

- The engine listens on 127.0.0.1 only, so the network cannot reach it. The port is random.
- It runs while you watch a torrent and stops 90 seconds after you leave the player. While it
  runs, a foreground service keeps the firmware from killing it.
- Its RAM cache follows the device memory: 48 MB under 1.2 GB, 64 MB under 1.8 GB, 96 MB under
  2.5 GB, 128 MB above. Settings can override it.
- **Settings** (button on the home screen): built-in engine or an external TorrServe address.
  The built-in engine is the default unless an external address is set. Videos from Lampa always
  play through the TorrServe configured in Lampa.
- **For Lampa and other apps** (off by default): the engine runs all the time on 127.0.0.1:8090
  and starts at boot, so Lampa can use TorrServe `127.0.0.1:8090` without a separate TorrServe app.
  A separate TorrServe on the same port must be off then.
- Engine log: `Android/data/com.xgimiru.player/files/torrserver.log`.

The engine adds about 8 MB to each APK (21 to 22 MB once installed). All native libraries are now
compressed in the APK, so the APKs got smaller anyway: 35 MB instead of 58 (arm64) and 33 MB
instead of 45 (32-bit).

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

The built-in torrent engine also needs Go (1.25 or later) and the Android NDK. Gradle runs
`torrserver/build.sh`, which fetches the `torrserver/upstream` submodule (TorrServer at a pinned
commit), applies `torrserver/patches` and builds the program for both ABIs. Without Go, use
`gradle -Pkino.noTorrServer assembleDebug` for an APK without the built-in engine.

Two APKs are built, `arm64-v8a` and `armeabi-v7a` (`splits.abi` in `app/build.gradle`):
`app/build/outputs/apk/<type>/app-<abi>-<type>.apk`. `art/make_icons.py` draws the icon and the TV banner.

### Licence

- The project's own code is licensed under the Apache License 2.0 (`LICENSE`).
- `DoviRpu.kt` and `DoviRenderer.kt` in the `dovi` package are licensed under
  LGPL-2.1-or-later (`LICENSES/LGPL-2.1-or-later.txt`), because they port the RPU parser from
  FFmpeg and the Dolby Vision shader from libplacebo.
- A built APK includes the Jellyfin FFmpeg decoder, which is GPL-3.0, so the APK as a whole is
  distributed under GPL-3.0.
- The built-in torrent engine is TorrServer (GPL-3.0), a separate program that Kino starts and
  talks to over HTTP. Its source: the `torrserver/upstream` submodule, our changes in
  `torrserver/patches`, the build in `torrserver/build.sh`. Every release has a
  `torrserver-<version>-src.tar.gz` with the complete source of the build inside the APK.

See `NOTICE` for details and the authors of the original code. This project is not affiliated
with XGIMI, Dolby, VideoLAN, Google or the TorrServer authors. Dolby Vision is a trademark of Dolby Laboratories.
