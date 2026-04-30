# KosherEats Seller - ProGuard/R8 Rules

# App models (Moshi uses codegen + reflection fallback)
-keep class com.koshereats.seller.data.models.** { *; }

# Moshi
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

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

# Kotlin metadata
-keep class kotlin.Metadata { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Google Sign-In / Credential Manager
-keep class com.google.android.libraries.identity.** { *; }
-keep class androidx.credentials.** { *; }

# Hilt
-dontwarn dagger.hilt.**

# Compose (R8 full mode)
-dontwarn androidx.compose.**
