# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.cortex.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.cortex.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
