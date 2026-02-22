# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.example.bluetoothkeymapper.** { *; }

# Bluetooth
-keep class android.bluetooth.** { *; }

# AccessibilityService
-keep class android.accessibilityservice.** { *; }
-keep class android.view.accessibility.** { *; }