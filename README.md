# Lumen Play

[Русский](#русский) | [English](#english)

## Русский

Lumen Play это простой видеоплеер для Android TV, который мы используем на проекторах с
[Lumen OS](https://github.com/Project-Lumen-OS/lumen-os). Интерфейс сделан под пульт.

Что умеет:

- **Файлы**: память устройства и флешки USB. Вся папка становится списком, следующая серия включается сама.
- **Папки в сети**: общие папки Windows и NAS (SMB), NFS, FTP и SFTP, серверы DLNA. Серверы в той же сети
  появляются сами, свою папку можно добавить по адресу, логин и пароль хранятся только в приложении
  (пароль зашифрован ключом из хранилища ключей Android).
- **Открыть в Lumen Play** из любого приложения: понимает параметры внешних плееров MX Player и VLC
  (название, позиция, список серий, субтитры, заголовки запроса) и возвращает позицию остановки.
- **Трансляция с телефона (DLNA)**: Lumen Play виден как телевизор в приложениях с кнопкой «На телевизор»
  (BubbleUPnP, Web Video Cast, VLC, «Передать на устройство» в Windows). Фото открываются на весь экран.
- **Отправка из браузера**: расширение `extension/` для Chrome отправляет видео со страницы на проектор,
  браузер подключается один раз по коду с экрана. Протокол: `docs/send-protocol.md`.
- **Встроенный TorrServer** для Лампы и других приложений: адрес `127.0.0.1:8090`, отдельный TorrServe не нужен.
  Включается в настройках, раздел TorrServer.
- **Magnet-ссылки и файлы .torrent** открываются сразу: Lumen Play показывает видео из торрента как папку и
  играет их через встроенный TorrServer. Файлы .torrent видны и в папках на флешке.
- **Продолжить просмотр**: на главном экране и в лаунчере (Lumen Home, Google TV). Для файлов с понятным
  названием фильма или сериала лаунчер получает фон из TMDB, иначе кадр из видео.
- **Движок Media3** (ExoPlayer) играет всё, что может, на аппаратных декодерах, включая 4K HDR и Dolby Vision,
  и папки SMB. libVLC берёт только NFS, FTP, SFTP и старые форматы, которые Media3 не открывает.
- **Настройки плеера, как в Kodi.**
  - Видео: декодер (аппаратный, программный), туннельный режим, Dolby Vision 5 (SDR или HDR10),
    масштаб, соотношение сторон, продолжение с места остановки, шаг перемотки и
    ускорение при удержании, большой шаг, скорость при запуске, следующая серия.
  - Звук: язык, передача на ресивер по форматам (AC3, E-AC3, Atmos в E-AC3, TrueHD, DTS, DTS-HD), сведение
    в стерео, задержка звука, усиление громкости, ночной режим.
  - Субтитры: когда показывать (как в файле, только надписи, выключены, нужный язык и запасной), внешние
    файлы .srt и .ass рядом с видео, кодировка (сама определяет UTF-8, Windows-1251, KOI8-R и другие),
    размер, цвет, фон, обводка, жирный шрифт, отступ снизу, задержка, стиль из файла.
  - Интерфейс: когда прятать панель, сведения о видео и звуке, часы, кнопки пульта (OK, вверх и вниз).
- Тёмная тема, светлую можно включить в настройках. Шрифт Inter.

### Сборка

Нужны JDK 17, Android SDK с платформой 36 и NDK, Gradle 8.10 или новее в ветке 8.x и Go для TorrServer
(`torrserver/build.sh`, без Go: `gradle -Pkino.noTorrServer ...`, APK без встроенного движка).

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # или sdk.dir в local.properties
gradle assembleDebug testDebugUnitTest
```

Собираются два APK, `arm64-v8a` и `armeabi-v7a`: `app/build/outputs/apk/<тип>/app-<abi>-<тип>.apk`.
Ключ TMDB берётся только с машины сборки (`~/.lumen-keys/tmdb.txt`), без него фон из TMDB выключен.

### Лицензия

- Код проекта: Apache 2.0 (`LICENSE`).
- `DoviRpu.kt` и `DoviRenderer.kt` в пакете `dovi`: LGPL-2.1-or-later (порт из FFmpeg и libplacebo).
- `torrserver/`: TorrServer (GPL-3.0), отдельная программа внутри APK, патчи под GPL-3.0.
- APK содержит FFmpeg-декодер Jellyfin под GPL-3.0, поэтому APK целиком распространяется на условиях GPL-3.0.
- Шрифт Inter: SIL Open Font License 1.1 (`app/src/main/assets/fonts/OFL-Inter.txt`). Значки: Material Symbols (Apache 2.0).

Подробности в `NOTICE`. Проект не связан с XGIMI, Dolby, VideoLAN или Google.

## English

Lumen Play is a plain video player for Android TV that we use on projectors running
[Lumen OS](https://github.com/Project-Lumen-OS/lumen-os). It is built for a TV remote.

- **Files**: device storage and USB sticks, the whole folder becomes a playlist.
- **Network folders**: Windows shares and NAS (SMB), NFS, FTP, SFTP and DLNA servers. Servers on the same network
  show up by themselves, logins stay inside the app, passwords encrypted with an Android Keystore key.
- **Open with Lumen Play** from any app, with the MX Player and VLC intent extras, and the stop position is returned.
- **Cast from phones (DLNA)**: Lumen Play shows up as a TV in apps that send to a TV (BubbleUPnP, Web Video Cast,
  VLC, Windows "Cast to device"), photos open full screen.
- **Send from a browser**: the Chrome extension in `extension/` sends the video of a page to the projector after a
  one-time code from the screen. Protocol: `docs/send-protocol.md`.
- **Built-in TorrServer** for Lampa and other apps on `127.0.0.1:8090`, no separate TorrServe needed.
- **Magnet links and .torrent files** open at once: the videos of the torrent show up like a folder and play
  through the built-in TorrServer. .torrent files also show up in the folders of a USB stick.
- **Continue watching** on the home screen and in the launcher, with a TMDB backdrop for recognisable titles.
- **Media3 engine** for everything it can play on the hardware decoders, 4K HDR, Dolby Vision and SMB folders
  included. libVLC only for NFS, FTP, SFTP and legacy formats Media3 refuses.
- **Kodi-style player settings**: video (decoder, tunnelling, zoom, aspect, resume, seek steps,
  speed), audio (language, passthrough per format, downmix, delay, boost, night mode), subtitles (when to show,
  external files, character set detection incl. Windows-1251, size, colour, background, edge, position, delay)
  and interface (bar timeout, details, clock, remote keys).
- Dark theme, a light one can be picked in the settings. Inter font.

Build: JDK 17, Android SDK platform 36 with the NDK, Gradle 8.x, Go for TorrServer: `gradle assembleDebug testDebugUnitTest`
(`-Pkino.noTorrServer` builds without the engine).
Licence: Apache 2.0 for the project code, LGPL-2.1-or-later for `dovi/DoviRpu.kt` and `dovi/DoviRenderer.kt`,
GPL-3.0 for `torrserver/` (TorrServer) and for the APK as a whole (Jellyfin FFmpeg decoder). Inter: SIL OFL 1.1.
