# Keep Kotlinx Serialization models & polymorphic schemas
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.dmedit.core.model.** { *; }
-keep class com.dmedit.core.nativebridge.** { *; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Sora Editor & libsu
-keep class io.github.rosemoe.sora.** { *; }
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn io.github.rosemoe.sora.**
-dontwarn com.topjohnwu.superuser.**
