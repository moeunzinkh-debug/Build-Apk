# Xtr3

**Xtr3** គឺជាកម្មវិី Android សម្រាប់ដាក់តំណភ្ជាប់ទំព័រ ឬតំណវីដេអូ រួចស្វែងរក និងទាញយកវីដេអូ។ ឧទាហរណ៍តំណដែលបានដាក់ក្នុងកម្មវិធី៖

`https://xstree.com/vplay/35835512.html`

> សំខាន់៖ តំណឧទាហរណ៍នេះមិនត្រូវបានផ្ទៀងផ្ទាត់ជាមួយគេហទំព័រផ្ទាល់នៅក្នុង sandbox ទេ។ បើទំព័រប្រើ JavaScript, ការចូលគណនី ឬតំណមានអាយុកាលខ្លី សូមប្រើ **Open page detector** ហើយចុច Play ម្តងដើម្បីចាប់តំណវីដេអូថ្មី។ មិនមានការធានាថាគ្រប់វីដេអូលើ xstree.com អាចទាញយកបានទេ។

## មុខងារ / Features

- ភាសាខ្មែរ និងអង់គ្លេស តាមភាសាទូរស័ព្
- ដាក់ ឬបិទភ្ជាប់តំណទំព័រ / តំណវីដេអូផ្ទាល់
- ស្វែងរក `<video>`, `<source>`, OpenGraph metadata និងតំណក្នុង JSON/HTML
- បើកទំព័រក្នុង WebView ដើម្បីចាប់តំណ MP4/WebM/MOV/HLS ដែល player ស្នើសុំ
- ទាញយកឯកសារវីដេអូផ្ទាល់ និងវីដេអូ HLS VOD ធម្មតា
- HLS MPEG-TS segments, byte ranges និង AES-128 ដែលមានសោសាធារណៈនៅក្នុង playlist
- ព្យាយាមបំលែង TS ទៅ MP4 ដោយមិនបាត់បង់គុណភាព ដោយប្រើ Android MediaExtractor/MediaMuxer។ បើឧបករណ៍មិនគាំទ្រ វានឹងរក្សាទុកជា `.ts` ហើយប្រាប់អ្នកឱ្យដឹង
- Fragmented MP4 HLS ដែលមាន initialization segment តែមួយ
- ជ្រើសរើសគុណភាពខ្ពស់បំផុតពី HLS master playlist
- Progress, cancel, retry និងការជូនដំណឹងពេលទាញយកនៅផ្ទៃខាងក្រោយ
- រក្សាទុកក្នុង `Movies/Xtr3` តាម MediaStore និងបញ្ជីវីដេអូក្នុងកម្មវិី
- គ្មានពាណិជ្ជកម្ម គ្មាន analytics និងមិនប្រមូលបញ្ជីវីដេអូទៅ server របស់ Xtr3 ទេ

## តម្រូវការ / Requirements

- Android **10 ឬថ្មីជាង** (API 29+)
- អ៊ីនធឺណិត និងទំហំទំនេរសម្រាប់វីដេអូ
- ការអនុញ្ញាត Notification ជាជម្រើសសម្រាប់បង្ហាញ progress; មិនត្រូវការសិទ្ធិអាន storage ទាំងមូលទេ

## ទាញយក APK ពី GitHub Actions

រាល់ការ push ទៅ `main` ឬ `arena/**` និងការបើក **Run workflow** នឹងដំណើរការ workflow **Build Xtr3 APK**។

1. បើក repository → **Actions** → **Build Xtr3 APK**។
2. ជ្រើសរើស run ដែលបានបញ្ចប់ដោយជោគជ័យ។
3. ក្នុងផ្នែក **Artifacts** ទាញយក **Xtr3-APK**។
4. ពន្លាឯកសារ ZIP ហើយដំឡើង `Xtr3.apk` លើទូរស័ព្ Android។ អ្នកប្រហែលជាត្រូវអនុញ្ញាតឱ្យកម្មវិីដែលបរើដំើង APK ពីប្រភពមិនស្គាល់។

Workflow បរាជ័យភ្លាមបើ APK លើស **15,000,000 bytes**។ APK មិនត្រូវបានបំពេញទំហំឱ្យដល់ 15 MB ទេ؛ វាត្រូវបានរក្សាឱ្យតូចជាងនេះតាមធមមជាតិ ព្រោះមិនមាន FFmpeg ឬ Python runtime ភ្ជាប់មកជាមួយ។ Artifact មាន `Xtr3.apk.sha256` សម្រាប់ផ្ទៀងផ្ទាត់ឯកសារ និងមានអាយុកាល 14 ថ្ងៃ។

**ប្រភេទ build៖** workflow build `assembleDebug` ដែលចុះហត្ថលេខាដោយ debug key របស់ runner ដើម្បីឱ្យអាចដំឡើងសាកល្បងបានភ្លាមៗ។ វាមិនមែនជា release សម្រាប់ Play Store ទេ។ Debug key អាចខុសគ្នារវាង run ដូច្នេះការដំឡើងជាន់លើ build ចាស់អាចតម្រូវឱ្យលុប build ចាស់សិន។ សម្រាប់ការចេញផ្សាយជាផ្លូវការ ត្រូវបន្ថែម signing config ដោយប្រើ keystore ឯកជនរបស់អ្នក និងរក្សាទុកវាក្នុង GitHub Secrets មិនមែនក្នុង repository ទេ។

## របៀបប្រើ

1. បើក **Xtr3**។
2. ចុច **Use example link** ឬបិទភ្ជាប់តំណផ្សេង។
3. ចុច **Find video**។ បើរកឃើញតំណវីដេអូ ចុច **Download** លើតំណដែលចង់បាន។
4. បើរកមិនឃើញ ចុច **Open page detector**។ ក្នុងទំព័រដែលបើកឡើង ចុច Play លើវីដេអូម្តង។ ពេលខាងក្រោមបង្ហាញថារកឃើញតំណ ចុច **Choose video** ហើយបញ្ជាក់ការទាញយក។
5. តាមដាន progress។ ពេលរួចរាល់ ចុច **Open** ឬមើលក្នុង **My videos** / `Movies/Xtr3`។

## ដែនកំណត់ដែលត្រូវដឹង

- មិនគាំទ្រ DRM/Widevine, SAMPLE-AES, វីដេអូដែលត្រូវការសិទ្ធិពិសេសដែលអ្នកមិនមាន ឬការផ្សាយបន្តផទាល់ (live HLS ដែលគ្មាន `ENDLIST`)។
- HLS ដែលមាន playlist សំឡេងដាច់ដោយឡែក, initialization segments ច្រើន ឬ discontinuities ក្នុង fragmented MP4 មិនត្រូវបានគាំទ្រកនុងកំណែស្រាលនេះទេ។
- ការបំលែង TS → MP4 អាស្រ័យលើ codec របស់ឧបករណ៍។ ពេលបំលែងមិនបាន កម្មវិធីរក្សាទុក TS ដែលអាចចាក់ដោយ player ដូចជា VLC ជំនួសវិញ មិនកុហកថាជា MP4 ទេ។
- គេហទំព័រអាចផ្លាស់ប្តូរ player ឬធ្វើឱ្យតំណផុតកំណត់។ ករណីនោះ ត្រូវបើកទំព័រស្វែងរកតំណថ្មី។
- សូមទាញយកតែវីដេអូដែលអ្នកជាម្ចាស់ ឬទទួលបានការអនុញ្ញាតឱ្យរក្សាទុក ហើយគោរពលក្ខខណ្ឌរបស់គេហទំព័រ។

## Build ក្នុងម៉ាស៊ីនរបស់អ្នក

ត្រូវការ JDK 17 និង Android SDK 35។

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
# Windows: gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

លទ្ធផល៖ `app/build/outputs/apk/debug/app-debug.apk`។

រចនាសម្ព័ន្ធ៖ Java native Android UI, WebView សម្រាប់ចាប់តំណ, HttpURLConnection សម្រាប់ទាញយក, HLS parser ខ្នាតតូច, MediaStore សម្រាប់រក្សាទុក និង foreground service សម្រាប់ការផ្ទេរដែលអ្នកប្រើបានចាប់ផ្តើម។ Unit tests គ្របដណ្តប់ URL/HTML/HLS parser និង Node tests សម្រាប់ browser detector។ ការសាកល្បងជាមួយគេហទំព័រជាក់លាក់ និងការចាក់វីដេអូពិតត្រូវធ្វើលើឧបករណ៍ Android។
