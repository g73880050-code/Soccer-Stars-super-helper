-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

-keepattributes *Annotation*
-keepattributes Signature
