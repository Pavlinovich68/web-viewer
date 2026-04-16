# WebViewer — добавьте сюда правила ProGuard
-keepattributes *Annotation*
-keepattributes JavascriptInterface

-keepclassmembers class com.webviewer.app.MainActivity$CredentialBridge {
    public *;
}
