# GreenEats Seller - ProGuard/R8 Rules

# App models (Moshi uses codegen + reflection fallback)
-keep class com.greeneats.seller.data.models.** { *; }

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

# Preserve Throwable subclass names so surfaced exception class names
# (UnknownHostException, SSLException, JsonDataException, etc.) remain readable
# in obfuscated release builds for in-UI error diagnostics.
-keepnames class * extends java.lang.Throwable

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
