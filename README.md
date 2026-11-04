# WLED-AMBI1: WLED Ambilight (Windows + Android TV)
Ambient lighting for HTPC and Android TV.

Matches the LEDs behind your TV to what's on screen using WLED's realtime UDP protocol.

- `windows/`  Windows app (Python + tkinter, packaged to a single .exe)
- `android/`  Android TV app (Kotlin, no external libraries)
- `.github/workflows/build.yml`  builds both automatically on GitHub

## Get the .exe and the .apk (easiest: GitHub builds them for you)

1. Create a free GitHub repository and upload everything in this folder
   (keep the `.github` folder, it's hidden on some systems).
2. Open the repo's **Actions** tab. The "Build apps" workflow runs on every push
   (or click **Run workflow**). It takes a few minutes.
3. When it finishes, open the repo's **Releases** (right sidebar) and download:
   - `WLED-Ambilight.exe`
   - `WLED-Ambilight-AndroidTV.apk`
   (If the Release is missing: Settings > Actions > General > Workflow permissions >
   Read and write, then re-run the workflow.)

### Build locally instead
- **Windows:** install Python 3, run `windows\build_exe.bat` -> `windows\dist\WLED-Ambilight.exe`.
- **Android:** open the `android` folder in Android Studio and choose Build > Build APK(s).

## Install the APK on Android TV
1. On the TV enable Developer options > USB debugging (or allow "Install unknown apps" for a file manager app).
2. Either sideload with `adb connect <tv-ip>` then `adb install app-debug.apk`,
   or copy the APK to the TV (USB drive / "Send Files to TV" app) and open it.
3. Launch **WLED Ambilight**, enter the WLED IP and LED counts, press **Test pattern** to
   check the start corner and direction, then **Start** and accept the screen-capture prompt.

## Notes
- WLED: Config > Sync Interfaces > make sure realtime UDP receive is enabled (port 21324).
- DRM-protected video (Netflix, Disney+, etc.) is blocked from screen capture on both platforms,
  so the lights go dark for that content. This is an OS limitation.
- Android TV: the capture permission has to be re-granted each time you press Start
  (Android requirement); the app can't auto-start on boot.
- To capture the TV's own picture, the Android app must run on the same device that plays the video
  (the TV itself or a Chromecast/Google TV/Fire TV box). It cannot see a console plugged into HDMI.
