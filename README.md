# Xtr3

Xtr3 is a small Android app for pasting a page URL or direct video URL, finding the video, and saving it to `Movies/Xtr3`.

Example link included in the app:

`https://xstree.com/vplay/35835512.html`

> សំខាន់៖ តំណឧទាហរណ៍នេះមិនត្រូវបានផ្ទៀងផ្ទាត់ជាមួយគេហទំព័រផ្ទាល់នៅក្នុង sandbox ទេ។ បើទំព័រប្រើ JavaScript ឬតំណមានអាយុកាលខ្លី សូមប្រើ **Open page detector** ហើយចុច Play ម្តង ដើម្បីចាប់តំណវីដេអូថ្មី។ មិនធានាថាគ្រប់វីដេអូលើ xstree.com អាចទាញយកបានទេ។

## Features

- Khmer and English UI, following the phone language
- Paste a page URL or direct video URL
- Find links in `<video>`, `<source>`, OpenGraph metadata, JSON, and HTML
- Open the page in a WebView to capture MP4/WebM/MOV/HLS URLs requested by the player
- Download direct video files and normal HLS VOD streams
- HLS MPEG-TS segments, byte ranges, and AES-128 with playlist-provided keys
- Lossless TS-to-MP4 packaging with Android MediaExtractor/MediaMuxer; if the device cannot package MP4, the app keeps the `.ts` file and tells you
- Fragmented MP4 HLS with a single initialization segment
- Selects the highest-bandwidth variant from an HLS master playlist
- Progress, cancel, retry, and background download notification
- Saves through MediaStore to `Movies/Xtr3` and keeps an in-app video list
- No ads, no analytics, and no Xtr3 server collecting your video list

## Requirements

- Android **10 or newer** (API 29+)
- Internet connection and free storage for the video
- Notification permission is optional for progress; no broad storage permission is required

## Download the APK from GitHub Actions

Every push to `main` or `arena/**`, and every manual **Run workflow**, runs **Build Xtr3 APK**.

1. Open the repository → **Actions** → **Build Xtr3 APK**.
2. Select a successful run.
3. In **Artifacts**, download **Xtr3-APK**.
4. Unzip it and install `Xtr3.apk` on an Android phone. You may need to allow installs from unknown sources for the app used to open the APK.

The workflow fails if the APK is larger than **15,000,000 bytes**. The APK is not padded to reach 15 MB; it stays small because no FFmpeg or Python runtime is bundled. The artifact includes `Xtr3.apk.sha256` for verification and is retained for 14 days.

**Build type:** the workflow builds `assembleDebug`, signed with the runner's debug key so it can be installed for testing immediately. This is not a Play Store release. Debug keys can differ between runs, so installing over an older build may require uninstalling the old build first. For a public release, add your own private signing configuration and store the keystore in GitHub Secrets, not in the repository.

## How to use

1. Open **Xtr3**.
2. Tap **Use example link** or paste another URL.
3. Tap **Find video**. If a video link is found, tap **Download** on the one you want.
4. If nothing is found, tap **Open page detector**. On the page that opens, press Play once. When the bottom bar says a link was detected, tap **Choose video** and confirm the download.
5. Watch progress. When finished, tap **Open** or find the file in **My videos** / `Movies/Xtr3`.

## Limitations

- No DRM/Widevine, SAMPLE-AES, special-access videos you are not authorized to save, or live HLS streams without `ENDLIST`.
- HLS with separate audio playlists, multiple initialization segments, or fragmented-MP4 discontinuities is not supported in this lightweight build.
- TS-to-MP4 packaging depends on device codec support. If it fails, the app saves TS instead and does not pretend it is MP4; open it with a player such as VLC.
- Websites can change their player or expire media URLs. In that case, open the page again and detect a fresh link.
- Only download videos you own or have permission to save, and respect the website's terms.

## Build locally

Requires JDK 17 and Android SDK 35.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
# Windows: gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Architecture: native Java Android UI, WebView for link detection, HttpURLConnection for downloads, a small HLS parser, MediaStore for saving, and a foreground service for user-started transfers. Unit tests cover the URL/HTML/HLS parsers; Node tests cover the browser detector. Real website and video playback checks must be done on an Android device.
