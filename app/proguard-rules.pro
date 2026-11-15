# Only the WebView bridge is called by name from JavaScript.
-keepclassmembers class com.xtr3.downloader.BrowserActivity$MediaBridge {
    @android.webkit.JavascriptInterface public *;
}
-keepattributes JavascriptInterface
-dontwarn org.jetbrains.annotations.**

# Cronet loads its embedded provider/native bridge reflectively.
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.**

# youtubedl-android initializes bundled Python/FFmpeg/QuickJS and maps JSON reflectively.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-dontwarn com.yausername.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.fasterxml.jackson.**
