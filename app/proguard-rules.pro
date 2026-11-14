# Only the WebView bridge is called by name from JavaScript.
-keepclassmembers class com.xtr3.downloader.BrowserActivity$MediaBridge {
    @android.webkit.JavascriptInterface public *;
}
-keepattributes JavascriptInterface
-dontwarn org.jetbrains.annotations.**
