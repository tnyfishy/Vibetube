-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class vibe.tube.**$$serializer { *; }
-keepclassmembers class vibe.tube.** {
    *** Companion;
}
-keepclasseswithmembers class vibe.tube.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
