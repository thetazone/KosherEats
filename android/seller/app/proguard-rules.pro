# KosherEats Seller - ProGuard/R8 Rules

# App models (Moshi uses codegen + reflection fallback)
-keep class com.koshereats.seller.data.models.** { *; }

# Moshi
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
# Keep all kapt-generated JsonAdapters regardless of which sub-package they live in.
# @JsonClass keeps the annotated DTO but NOT the generated *JsonAdapter class — Moshi
# looks it up by name (ClassName + "JsonAdapter") so R8 renaming breaks the lookup.
# Covers SocialLoginRequest in data.api and any future @JsonClass outside data.models.
-keep class com.koshereats.seller.**JsonAdapter { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Retrofit + Kotlin Coroutines — R8 full mode strips the generic
# signatures Retrofit needs to recover Response<T> from a suspend
# function's Continuation parameter. Without these rules, parameterized
# return types collapse to raw Class and Retrofit throws
# "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType".
# Diagnosed via the in-UI diagnostic patch on Play Store seller install,
# 2026-05-14 — this is the root cause of the seller Google Sign-In bug.
# Official Retrofit R8 full-mode rule: preserves ApiService interface signatures
# so generic return types survive shrinking without losing obfuscation elsewhere.
-keep,allowobfuscation interface com.koshereats.seller.data.api.ApiService
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation,allowshrinking interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

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
