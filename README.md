# Xtr3

Xtr3 is a small Android app for pasting a page URL or direct video URL, finding the video, and saving it to `Movies/Xtr3`.

Current test build: **1.4.0** — adds site-aware downloading for public, non-DRM **Bilibili/B23, Facebook, YouTube, TikTok and TikTok drama episode** links using bundled yt-dlp 2026.10.26, Python, QuickJS, yt-dlp-ejs and FFmpeg. It keeps the Cronet/Media3 engine from 1.2.0 for ordinary pages/direct/HLS links, verifies downloaded bytes, and plays saved videos in-app. Since 1.3.1 the workflow builds a minified **release** and splits it into one APK per CPU ABI (arm64-v8a, armeabi-v7a, x86_64), so a phone installs only the native engines it can run instead of one ~167 MB universal file. The weight is real extraction/merge/playback engine code, not padding. It does not read browser cookies or bypass DRM, paid memberships, login-only/private videos, or live streams.

Since 1.4.0 there is also an opt-out **TikTok micro-drama fallback**: TikTok's newer
`/shortdrama/episode/...` links have no yt-dlp extractor, so when the bundled engine
cannot read a TikTok link Xtr3 can resolve it through the same public form flow a
browser uses on ssstik.io, then download the direct MP4 from TikTok's own CDN.
It stops honestly at a bot challenge, reads no browser or TikTok cookies, and can be
turned off in **Developer options**. See [docs/ssstik-fallback.md](docs/ssstik-fallback.md).

Example link included in the app:

`https://xstree.com/vplay/35835513.html`

> សំខាន់៖ ទំព័រឧទាហរណ៍នេះជា JavaScript shell; HTML ដំបូងបង្ហាញតែ “加载中” (កំពុងផ្ទុក)។ ដូច្នេះ **Find video** ធម្មតាអាចមិនឃើញ player ទេ។ សូមប្រើ **Open page detector** ចុច Play ម្តង រួចចុច **Grab source** ដើម្បីស្វែងរកតំណវីដេអូដើមនៅក្នុងកូដ player និងទិន្នន័យបណ្តាញ។ បើនៅតែមិនឃើញ បើក **Developer options** ដើម្បីកត់ត្រា request ហើយផ្ញើ log មកពិនិត្យ។ មិនធានាថាគ្រប់វីដេអូលើ xstree.com អាចទាញយកបានទេ។

## Features

- Khmer and English UI, following the phone language
- Paste a page URL or direct video URL
- Site-aware extraction for Bilibili/B23, Facebook, YouTube, TikTok and individual TikTok drama episodes; separate video+audio streams are merged losslessly with bundled FFmpeg
- Find links in `<video>`, `<source>`, OpenGraph metadata, JSON, and HTML
- Open the page in a WebView to capture MP4/WebM/MOV/HLS URLs, including media responses loaded by player `fetch`/XHR in embedded frames
- **Grab source**: deep-search player globals, inline/JSON code, `data-*` attributes, iframe `srcdoc`, resource timing, and small JSON/JS metadata responses for the original HTTP(S) video URL
- Explain blob/MSE, WebRTC, and protected playback instead of offering a browser-only `blob:` address as a download
- Replay the player's captured request headers (never cookies, authorization tokens, or DRM keys) plus bounded Range `GET`s, with embedded Cronet and desktop-Chrome/platform fallbacks, for hotlink/session-checked CDNs
- Label direct MP4/WebM/MOV responses as video files even when an unrelated query parameter contains `.m3u8`; `Size not reported yet` means the server has not supplied a length, not that the link is fake
- Verify downloaded bytes as a video container and, when the device can read it, a real video track; reject HTML/JSON/playlists masquerading as video
- Play saved videos inside Xtr3 with Media3/ExoPlayer
- Download direct video files and normal HLS VOD streams
- HLS MPEG-TS segments, byte ranges, and AES-128 with playlist-provided keys
- Lossless TS-to-MP4 packaging with Android MediaExtractor/MediaMuxer; if the device cannot package MP4, the app keeps the `.ts` file and tells you
- Fragmented MP4 HLS with a single initialization segment
- Selects the highest-bandwidth variant from an HLS master playlist
- Progress, cancel, retry, and background download notification
- Saves through MediaStore to `Movies/Xtr3` and keeps an in-app video list
- No ads, no analytics, and no Xtr3 server collecting your video list
- Opt-in Developer mode: request/console log, copy/share log, and WebView remote debugging for `chrome://inspect`

## Requirements

- Android **10 or newer** (API 29+)
- Internet connection and free storage for the video
- Notification permission is optional for progress; no broad storage permission is required

## Download the APK from GitHub Actions

Every push to `main` or `arena/**`, and every manual **Run workflow**, runs **Build Xtr3 APK**.

1. Open the repository → **Actions** → **Build Xtr3 APK**.
2. Select a successful run.
3. In **Artifacts**, download the APK for your phone's ABI: **Xtr3-APK-arm64-v8a** for most modern phones, **Xtr3-APK-armeabi-v7a** for older 32-bit ARM phones, or **Xtr3-APK-x86_64** for emulators / x86 tablets.
4. Unzip it and install the `Xtr3-<abi>.apk` inside on an Android phone. You may need to allow installs from unknown sources for the app used to open the APK.

The workflow fails if any single per-ABI APK is larger than **120,000,000 bytes**. Each split APK still bundles Cronet, Media3/ExoPlayer and yt-dlp/Python/QuickJS/EJS/FFmpeg for exactly one ABI; it is not padded with useless bytes. Each artifact includes a matching `.apk.sha256` for verification and is retained for 14 days.

**Build type:** the workflow builds `assembleRelease` (minified, resource-shrunk, non-debuggable) and signs it with a throwaway self-signed key generated inside the run, so the APK installs immediately without storing any private key in this public repository. This is not a Play Store release. The CI key is regenerated per run, so installing over an older CI build may require uninstalling the old build first. For a public / stable-upgrade release, add your own private signing configuration and store the keystore in GitHub Secrets, not in the repository.

## How to use

1. Open **Xtr3**.
2. Tap **Use example link** or paste another URL.
3. Tap **Find video**. If a video link is found, tap **Download** on the one you want.
4. If nothing is found, tap **Open page detector**. On the page that opens, press Play once, then tap **Grab source**. Xtr3 deep-searches the player code, page code, and small network metadata responses for the original video URL. **Choose video** stays dimmed and says **No video link** until a real HTTP(S) file or playlist is found. A playing picture alone is not enough: the player may use a temporary browser buffer or a peer connection.
5. Watch progress. When finished, tap **Open** or find the file in **My videos** / `Movies/Xtr3`.

## Developer mode / ពិនិត្យពេលរកវីដេអូមិនឃើញ

1. In Xtr3, tap **Developer options** and enable the switch.
2. Tap **Open detector**, load the page, press Play once, then tap **Grab source**. If the site pushes its own app instead of playing (common on Android), tap **Desktop** in the top bar to request the desktop site, then press Play and Grab source again.
3. Return to **Developer options** and tap **Copy log** or **Share log**. The log shows page loads, network requests, console messages, detector traces, and detected media URLs.
4. For deeper inspection: enable Android **USB debugging**, connect the phone to a computer, open `chrome://inspect/#devices` in desktop Chrome, inspect the Xtr3 WebView, and watch the **Network** and **Media** panels while the video plays.

The log is stored only in memory on the phone. Common token/password query fields are redacted, but the log can still contain page text and media URLs, so share it only with someone you trust. Remote debugging is enabled only for debug builds while the switch is on.

The detector ignores website API calls such as `video/detail`, `hot_search`, `rtcOffer`, chat/report endpoints, and APK downloads. Those are not video files. For a page to produce a downloadable candidate, Xtr3 needs to see an actual video element/source, a direct media file, or an HLS playlist. If the page only shows an app-install/login/DRM notice, the status bar explains that (in English or Khmer) and the log records a `GATE` entry instead of offering a fake video link.

Some sites (including xstree on Android) push their own app to mobile browsers. The **Desktop** toggle requests the desktop version of the site, the same way Chrome's "Desktop site" does. Playback succeeding in Desktop mode does **not** guarantee that a downloadable URL exists.

Since 1.1.3, a passive document-start observer watches player fetch/XHR response URLs and Content-Type metadata in embedded frames on supported Android System WebView versions. Since 1.1.4, **Grab source** also deep-searches player globals, inline/JSON code, `data-*` attributes, iframe `srcdoc`, resource timing, and small JSON/JS/text metadata responses. Metadata cloning is bounded (256 KiB per response, 24 responses per frame) and skips sensitive keys such as token/password/license/DRM. Xtr3 does not read media bodies or request bodies, does not record the screen, and does not bypass DRM. It also records whether video uses blob/MSE, WebRTC `MediaStream`, or protected media. Older WebView versions fall back to main-frame scanning. Repeated polling messages are deduplicated so important player events remain in the log.

A `blob:` address is local to the running page and is not an HTTP download link. MSE assembles media inside the browser; WebRTC delivers a peer stream. Xtr3 can offer a download only when it also finds the underlying HTTP(S) video file or supported playlist. If only fragments or a protected/peer stream are available, it explains that rather than enabling an empty choice. This improves diagnosis but does not guarantee downloading from every player.

## Limitations

- No DRM/Widevine, SAMPLE-AES, special-access videos you are not authorized to save, or live HLS streams without `ENDLIST`.
- For ordinary pages, MPEG-DASH (`.mpd`) may appear in diagnostics but is not offered as a fake video file. For the supported platforms, yt-dlp handles DASH/HLS formats and merges audio+video when needed.
- The lightweight direct/HLS path still does not support separate audio playlists, multiple initialization segments, or fragmented-MP4 discontinuities; supported-platform transfers use yt-dlp/FFmpeg instead.
- TS-to-MP4 packaging depends on device codec support. If it fails, the app saves TS instead and does not pretend it is MP4; open it with a player such as VLC.
- Websites can change their player or expire media URLs. In that case, open the page again and detect a fresh link.
- `HTTP 403` means the server refused the download. Xtr3 retries with the captured player context over embedded Cronet, then desktop/platform fallbacks, but it cannot bypass a server's access decision; sign in on the page if required, grab a fresh source, and try again.
- `Size not reported yet` is not an “unknown file” verdict. It means the detected URL did not expose a reliable `Content-Length`/total range before download. Xtr3 still verifies the actual bytes before saving.
- TikTok drama: paste the share link of one episode/video, not a whole collection/series/playlist page. Paid, login-only, private, region-blocked, live and DRM videos are not bypassed.
- Only download videos you own or have permission to save, and respect the website's terms.

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
