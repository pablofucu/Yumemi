# Yumemi release rules (minify is currently disabled; kept ready).
-keepattributes *Annotation*, InnerClasses
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pablo.yumemi.**$$serializer { *; }
-keepclassmembers class com.pablo.yumemi.** { *** Companion; }
-keepclasseswithmembers class com.pablo.yumemi.** { kotlinx.serialization.KSerializer serializer(...); }
