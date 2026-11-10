-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepclassmembers class local.fnswitch.manager.ConsoleBridge { @android.webkit.JavascriptInterface <methods>; }
