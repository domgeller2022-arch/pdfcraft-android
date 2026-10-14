# PdfCraft Android wrapper ProGuard rules
# Keep all WebView and AndroidX WebKit classes
-keep class android.webkit.WebView { *; }
-keep class android.webkit.WebViewClient { *; }
-keep class android.webkit.WebChromeClient { *; }
-keep class androidx.webkit.** { *; }

# Keep our own Kotlin classes
-keep class com.domgeller.pdfcraft.** { *; }

# Keep the WebViewAssetLoader
-keep class androidx.webkit.WebViewAssetLoader { *; }

# Keep WebViewRenderer classes
-keep class android.webkit.WebViewRenderer { *; }

# Keep WebViewClient overrides
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }

# Keep Kotlin parcelables
-keep class kotlin.reflect.** { *; }
-keepattributes Signature, *Annotation*

# Keep R8 configuration for the AndroidX Activity Result API
-keep class androidx.activity.result.** { *; }
-keep class androidx.activity.result.contract.** { *; }

# Don't warn about missing optional classes
-dontwarn android.webkit.**
-dontwarn androidx.webkit.**
