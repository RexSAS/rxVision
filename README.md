# rxVision

A custom Android TV launcher, part of [rxenv](https://github.com/RexSAS/rxenv).

- App grid and dock, with recent apps history
- RxFilms rows (Continue watching, Next up) pulled from Jellyfin, signed in with Quick Connect
- Now-playing card with synced lyrics from lrclib.net
- ClaudeFm: picks and plays music through SmartTube
- Red key on the remote opens the launcher menus (via an accessibility service + `READ_LOGS` granted over adb)

## Build

Plain Android SDK command-line tools, no Gradle:

```sh
./build.sh          # -> build/TvLauncher.apk
adb install -r build/TvLauncher.apk
adb shell pm grant com.andrea.tvlauncher android.permission.READ_LOGS
```

`build.sh` creates a local `signing.keystore` on first run; it's git-ignored.
