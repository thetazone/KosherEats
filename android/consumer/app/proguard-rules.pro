# KosherEats Consumer - ProGuard/R8 Rules

# App models (Gson uses reflection)
-keep class com.koshereats.consumer.data.models.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Stripe
-keep class com.stripe.** { *; }
-dontwarn com.stripe.**

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.**

# Google Sign-In / Credential Manager
-keep class com.google.android.libraries.identity.** { *; }
-keep class androidx.credentials.** { *; }

# Hilt
-dontwarn dagger.hilt.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}

# Compose (R8 full mode)
-dontwarn androidx.compose.**
