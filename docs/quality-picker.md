# Pre-download quality picker

Xtr3 1.5.0 lets the user choose a real video quality before a transfer starts.

## Supported-platform links

For Bilibili/B23, Facebook, YouTube, TikTok and individual TikTok drama episodes handled by the bundled yt-dlp engine:

1. **Find video** runs one `--dump-single-json --skip-download` inspection.
2. `PlatformFormats` reads the returned `formats` array and builds a small menu of real choices:
   - resolution (and high frame rate when reported);
   - container/packaging expectation;
   - estimated bytes when the platform reports `filesize`/`filesize_approx`;
   - whether audio is included. Video-only choices are labelled honestly.
3. The exact single-video info JSON is kept in private app cache (`platform-info`). It is not a playlist JSON and is not shared.
4. The user taps **Quality** or **Download**. If several choices exist, a single-choice dialog appears before the save confirmation.
5. The transfer uses `--load-info-json <cached file> --format <exact selector>`. No extra URL is appended, so yt-dlp does not extract the page again. Separate video+audio selectors such as `137+140` are merged by bundled FFmpeg.
6. The cached info file is deleted after the transfer attempt. Old cache files are pruned after 24 hours. If the cache is missing, Xtr3 re-extracts once but still passes the selected format selector.

The default choice prefers a quality with audio at or below 1080p; the menu can still show higher resolutions when the platform reports them. DRM, storyboard/image formats, unsafe format ids, paid/login-only/private/live bypasses are not offered.

## HLS links

For an HLS master playlist, Xtr3 lists the actual `EXT-X-STREAM-INF` variants instead of silently downloading the highest bandwidth. The default is the highest-bandwidth variant with combined audio/video. Variants that require a separate audio playlist are not offered by the lightweight HLS path. The selected variant URL is passed to the HLS downloader. If that chosen URL unexpectedly points at another master playlist, Xtr3 stops and asks you to find the video again rather than silently switching to a different quality.

## Direct files / TikWM & SSSTik fallbacks

- **TikWM fallback (`TikwmResolver`)**: requests `hd=1` on TikWM's public JSON endpoint and builds real selectable options whenever distinct HTTPS video streams are returned (`HD (no watermark) · MP4`, `Standard (no watermark) · MP4`, and `Watermarked · MP4`), including `hd_size` / `size` / `wm_size` when reported. Identical URLs are deduplicated so fake duplicate choices are never shown.
- **Direct files & SSSTik fallback (`SsstikResolver`)**: an ordinary MP4/WebM/MOV URL or the SSSTik HTML fragment exposes one media file, not a format ladder. Xtr3 shows the detected file type and size when available, then asks for download confirmation.

## Limits

- Sizes are estimates only when the platform reports them; otherwise the UI says **Size not reported yet**.
- Signed platform URLs can expire. If the selected quality is no longer available, run **Find video** again.
- The quality menu is only as truthful as the platform metadata. Xtr3 verifies the downloaded bytes as a real video container before saving.
