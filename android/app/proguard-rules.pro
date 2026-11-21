# Keep libbox JNI / GoMobile generated classes and PlatformInterface
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# Keep JSch reflection-loaded crypto implementations
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**

# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.buildmyvpn.**$$serializer { *; }
-keepclassmembers class com.buildmyvpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.buildmyvpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
