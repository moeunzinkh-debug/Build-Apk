# TikTok micro-drama fallback — what was adopted from ssstik.io

## Why this exists

TikTok's newer **micro drama / short-drama** entry point uses URLs like
`https://www.tiktok.com/shortdrama/episode/<series>/<episode>`. The bundled yt-dlp
2026.10.26 has **no extractor** for that shape, so it falls through to the generic
extractor and fails with `Unsupported URL` (upstream report: yt-dlp/yt-dlp#17725).
Ordinary `@user/video/<id>` posts and `vm`/`vt` share links still work, which is
exactly the split the user reported: everything else resolves, micro drama does not.

## How ssstik.io does it (public client-side flow)

SSSTik is a server-rendered HTMX page. The browser does no extraction at all:

1. `GET https://ssstik.io/en` — the shell inlines a short-lived per-page token,
   published either as `var s_tt = '…'` or as an `include-vals="tt:'…'"` attribute.
2. `POST https://ssstik.io/abc?url=dl` with `id=<post url>&locale=en&tt=<token>`
   and the HTMX headers (`hx-request`, `hx-target`, `hx-trigger`, `hx-current-url`).
3. The response is an **HTML fragment**, not JSON. The MP4 is the
   `a.download_link.without_watermark` anchor; `a.music` is the MP3.
4. The backend verdict arrives in the **`HX-Trigger` response header**
   (`ssssuccess_video`, `sssinvalidlink`, `sssterror`, `ssslimitexceed`,
   `ssstokenfail`, `ssssuccess_slides`, `ssssuccess_music`, `ssssuccess_wmonly`, …).
5. The anchor is **not** a raw CDN URL. It is re-wrapped behind `ssscdn.io` /
   `tikcdn.io`, with the real signed TikTok CDN URL **base64-encoded into the path**.

### The detail that makes or breaks it

`/` is part of the base64 alphabet, so a long signed payload is split across
**several path segments**. Decoding only the final segment yields a fragment that
is valid text but not a URL, so a naive client reports "no MP4 link found" for
exactly the episodes that matter. `SsstikResolver.unwrapMediaUrl()` rejoins
candidate spans and tries each one; `SsstikResolverTest` pins this with a fixture
whose tail deliberately decodes to a non-URL.

Decoded URLs are signed and expire (`refresh_token`, `nonce`, `t`), so they are
used immediately and never persisted.

## What Xtr3 implements

`SsstikResolver.java` + routing in `MediaResolver.java`:

- Short-drama URLs skip the engine entirely and go straight to the fallback.
- Any other TikTok link tries the bundled engine first; the fallback runs only
  if the engine fails, and only while the toggle is on.
- One retry on `ssstokenfail` (fresh shell + token) and one on `ssslimitexceed`
  after a ~12 s cooldown; requests are paced ≥1.5 s apart.
- Every decoded URL is re-checked against a host allow-list (SSSTik wrappers plus
  the regional TikTok/ByteDance CDNs) and must be HTTPS with no userinfo.
- Verdicts map to localized messages, so a photo carousel, audio-only post or
  watermarked-only post says so instead of failing generically.

## Deliberate limits

- **No captcha solving and no Cloudflare challenge defeat.** A missing token or a
  401/403 is an honest stop (`platform_challenge`).
- No login, no DRM bypass, no watermark stripping, no paid "HD" tier.
- No browser or TikTok cookies are read; only SSSTik's own `Set-Cookie` is echoed
  back to SSSTik.
- None of their telemetry (`/log/e`, GTM, Smartlook), ad stack, or `eval` of
  attribute strings is reproduced.
- Only the single public post URL the user pasted is sent, and the result card is
  labelled "Resolved via SSSTik (third-party)".
- The whole fallback can be turned off in **Developer options**.

## Source of the protocol notes

Ported from this account's own prior teardown in `moeunzinkh-debug/VTgrabing`
(`docs/research/ssstik-frontend-analysis.md`, PR #12), which analysed publicly
served client assets and three independent open-source clients. A later inspection
read the current public JavaScript bundle (HTMX 2.0.6 plus a `debug` telemetry
parameter), but direct curl/Chromium access still failed before a form POST and the
TikTok target returned HTTP 403. Live end-to-end behaviour is therefore still
**unverified**; see `docs/ssstik-live-check.km.md` for the observed workflow and the
limits of that check.
