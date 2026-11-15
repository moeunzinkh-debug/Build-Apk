# ការត្រួតពិនិត្យ SSSTik និង workflow

តំណគោលដៅ៖ `https://vt.tiktok.com/ZSpqSc5Rq/`

## សេចក្តីសន្និដ្ឋានខ្លី

- អាចអានទំព័រ SSSTik និង JavaScript ដែលគេកំពុងផ្តល់ឱ្យតាមឧបករណ៍ទាញទំព័រ។
- មិនអាចបញ្ចូលតំណ រួចចុច Download ក្នុង browser ជាក់ស្តែងបានទេ៖ ការតភ្ជាប់ផ្ទាល់ពី sandbox ត្រូវបានបិទមុនទំព័រផ្ទុក។
- ដូច្នេះ **មិនទាន់មានភស្តុតាងថាតំណនេះ resolve ជា MP4 បានទេ** ហើយមិនមាន response `HX-Trigger` ឬ media URL ពិតសម្រាប់តំណនេះ។
- មិនបានដោះ captcha, bypass Cloudflare, ប្រើ login/cookies របស់អ្នកប្រើ ឬទាញយក media នោះទេ។

## អ្វីដែលបានសាក និងលទ្ធផល

| ការសាក | លទ្ធផល |
|---|---|
| អាន `https://ssstik.io/` និង `/en` តាមឧបករណ៍ទាញទំព័រ | ទទួលបានអត្ថបទទំព័រ ប៉ុន្តែមិនបាន raw form/network capture |
| អាន `https://ssstik.io/js/script_ssstik.min.js` | ទទួលបានកូដខាងមុខថ្មី រួមទាំង HTMX និង event handlers |
| `curl` ផ្ទាល់ទៅ SSSTik | `SSL_ERROR_SYSCALL` |
| Chromium ធម្មតាបើក `/` និង `/en` | `net::ERR_CONNECTION_CLOSED`; `formSubmitted=false`; គ្មាន POST ទៅ `/abc` |
| ទាញតំណ TikTok គោលដៅផ្ទាល់ | HTTP 403; មិនដឹង redirect ចុងក្រោយ |

ការអាន JavaScript ដោយឡែក មិនស្មើនឹងការបញ្ជូនទម្រង់ជោគជ័យទេ។

## Workflow ដែលមើលឃើញពី JavaScript ថ្មី

1. អ្នកប្រើបញ្ចូលតំណក្នុង `#main_page_text` ហើយចុច `#submit`។ កូដមានប៊ូតុង Paste/Clear ផងដែរ។
2. `htmx:confirm` បិទប៊ូតុងបណ្តោះអាសន្ន, ពិនិត្យស្ថានភាព ads ហើយអាចហៅ `/cdn-cgi/trace` ដើម្បីយក `loc`/`ip`។ វាបង្កើត `debugstring` ដូចជា `ab=...`, `loc=...`, `ip=...` ឬ `traceomit=1`។
3. `htmx:configRequest` បន្ថែមតម្លៃនោះជា form parameter `debug`។ នេះជាផ្នែក telemetry/anti-abuse របស់គេ; **មិនទាន់ដឹងថា server តម្រូវឱ្យមានវាដើម្បី resolve video ឬអត់**។
4. `htmx:beforeRequest` សម្អាត error, កត់ត្រា `getInsertedLink`, ពិនិត្យ partner/unknown link ហើយទទួលទម្រង់ TikTok ដែលត្រូវនឹង host `tiktok.com`, `xzcs3zlph.com` ឬ `tt.site`។
5. HTMX បញ្ជូនសំណើទៅ backend ហើយយក HTML fragment មកជំនួសក្នុងផ្ទៃលទ្ធផល ដោយមិន reload ទំព័រពេញ។ Bundle ថ្មីបង្ហាញ HTMX version `2.0.6`។
6. Backend ប្រាប់លទ្ធផលតាមព្រឹត្តិការណ៍ `HX-Trigger`។ កូដថ្មីស្តាប់ success/error ដូចជា៖
   - `ssssuccess_video`, `ssssuccess_videoandmp3`, `ssssuccess_slides`, `ssssuccess_music`, `ssssuccess_wmonly`
   - `sssinvalidlink`, `sssblockedclient`, `sssterror`, `ssscurlerror`, `ssstokenfail`, `ssslimitexceed`
   - `ssssuccess_profile`, `ssssuccess_ttviewer` និងព្រឹត្តិការណ៍ RapidAPI/HD ផ្សេងទៀត
7. ពេលបានវីដេអូ ទំព័របង្ហាញប៊ូតុងទាញយក; ads/vignette អាចបង្ហាញមុនបើកតំណ media។ ផ្នែក ads នេះមិនមែនជាយន្តការស្វែងរក video ទេ។

## ផ្នែក protocol ដែលមកពីកំណត់ត្រាចាស់/public clients

ចំណុចខាងក្រោមបានប្រើក្នុង Xtr3 ប៉ុន្តែ **មិនត្រូវបានបញ្ជាក់ឡើងវិញដោយ POST ជាក់ស្តែងក្នុង session នេះទេ**៖

```text
GET https://ssstik.io/en
  -> យក page token (s_tt ឬ tt)

POST https://ssstik.io/abc?url=dl
  form: id=<TikTok URL>&locale=en&tt=<token>
  headers: HX-Request, HX-Target, HX-Trigger, HX-Current-URL
  -> HTML fragment + response HX-Trigger

MP4: a.download_link.without_watermark
MP3: a.music
  -> តំណត្រូវបានរុំតាម ssscdn.io/tikcdn.io
  -> base64 ក្នុង path អាចឆ្លងកាត់ច្រើន segment ព្រោះ '/' ស្ថិតក្នុង base64 alphabet
  -> បើកបានជា signed TikTok CDN URL ដែលមានថ្ងៃផុតកំណត់
```

JavaScript ថ្មីបន្ថែម `debug` ដល់ទម្រង់ ប៉ុន្តែកូដចាស់/public clients ខ្លះធ្លាប់ហៅដោយគ្មានវា។ ដោយសារមិនមាន live POST យើងមិនអាចសន្និដ្ឋានថាការដក `debug` ចេញធ្វើឱ្យខូច compatibility នោះទេ។

## ការប្រៀបធៀបជាមួយ Xtr3

`SsstikResolver.java` បានអនុវត្តផ្នែកស្នូលរួចហើយ៖ យក token, POST តំណសាធារណៈ, អាន verdict/anchor, បើក base64 ច្រើន segment, ពិនិត្យ HTTPS/host, retry និង cooldown។

Xtr3 **មិនបានចម្លង** `debug` ដែលប្រមូល IP/location, ads, telemetry, captcha ឬ paid HD flow ទេ។ នេះជាការការពារ privacy ហើយ compatibility ជាមួយ server បច្ចុប្បន្ននៅតែមិនទាន់ live-verified។

មិនមានការកែ runtime code ឬបង្កើត APK ថ្មីពីការត្រួតពិនិត្យ SSSTik នេះទេ ព្រោះមិនទាន់រកឃើញ server mismatch ដែលមានភស្តុតាងផ្ទាល់។ នៅពេលត្រួតពិនិត្យនោះ APK ចុងក្រោយគឺ **v1.4.0**។

## របៀបចាប់ Network ពិតនៅលើឧបករណ៍ដែលបើកគេហទំព័របាន

1. បើក SSSTik ក្នុង browser លើកុំព្យូទ័រ ហើយបើក DevTools (`F12`) → **Network**។
2. បើក **Preserve log** ហើយត្រងយក **Fetch/XHR**។
3. បញ្ចូល `https://vt.tiktok.com/ZSpqSc5Rq/` ហើយចុច Download ម្តង។
4. ជ្រើសសំណើ `abc?url=dl` ហើយមើល **Headers**, **Payload** និង **Response**៖
   - Payload គួរមាន `id`, `locale`, `tt` និងប្រហែល `debug`
   - Response headers អាចមាន `HX-Trigger`
   - Response HTML អាចមាន `a.download_link.without_watermark`
5. បើផ្ញើ HAR ឬ screenshot មកវិភាគ សូមលាក់ Cookie, `tt`, `debug` ដែលមាន IP និង signed media query parameters ជាមុន។ កុំផ្ញើព័ត៌មានចូលគណនី។
