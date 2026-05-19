# KosherEats Consumer - ProGuard/R8 Rules

# App models (Gson uses reflection)
-keep class com.koshereats.consumer.data.models.** { *; }
# Belt-and-suspenders: keep any field annotated with @SerializedName even if
# R8 full mode would otherwise obfuscate the field identifier.
-keepclassmembers,allowobfuscation,allowshrinking class com.koshereats.consumer.data.models.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Enum constant names must survive R8 so that Gson's valueOf() lookups work
# (e.g. OrderStatus.valueOf("pending")).  Cover the full consumer package, not
# only data.models, so enums in viewmodels / ui packages are also protected.
-keepnames enum com.koshereats.consumer.**
-keepclassmembers enum com.koshereats.consumer.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation interface com.koshereats.consumer.data.api.ApiService

# Retrofit + Kotlin Coroutines — R8 full mode strips the generic
# signatures Retrofit needs to recover Response<T> from a suspend
# function's Continuation parameter. Without these rules, parameterized
# return types collapse to raw Class and Retrofit throws
# "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType".
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod
-keep class * implements com.google.gson.TypeAdapterFactory
-keepclassmembers class * implements com.google.gson.TypeAdapterFactory { <init>(...); }
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Stripe
# PaymentLauncher and PaymentSheet use reflection on result classes and
# @Parcelize-generated CREATOR fields; R8 full mode strips them without
# these explicit rules. Exceptions attribute is required for stripe-android
# 20.45.0's internal try/catch reflection paths.
-keepattributes Exceptions
-keep class com.stripe.** { *; }
-keep class com.stripe.android.paymentsheet.** { *; }
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
    kotlinx.serialization.KSerializer serializer(...);
}
# R8 full mode strips the plugin-generated $$serializer inner class unless
# kept explicitly — Companion alone is not sufficient.
-keep class **$$serializer { *; }

# Compose (R8 full mode)
-dontwarn androidx.compose.**
