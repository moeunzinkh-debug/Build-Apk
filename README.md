
## License

GPL-3.0-only; see `LICENSE` and `LICENSES.md`. The APK links GPLv3 youtubedl-android and bundles the third-party components listed there.

## Build locally

Requires JDK 17 and Android SDK 35.

```bash
node --test tests/*.cjs
./gradlew testDebugUnitTest lintDebug assembleDebug
# Windows: gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Architecture: native Java Android UI, WebView for link detection, embedded Cronet with HttpURLConnection fallback for ordinary downloads, bundled yt-dlp/Python/QuickJS/EJS/FFmpeg for supported platforms, a small HLS parser, Media3/ExoPlayer for in-app playback, MediaStore for saving, and a foreground service for user-started transfers. Unit tests cover the URL/HTML/HLS parsers and video-container probing; Node tests cover the browser detector, source grabbing, and fetch/XHR/MSE/WebRTC observation. Real website and video playback checks must be done on an Android device.
