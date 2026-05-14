# Add project specific ProGuard rules here.

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclasseswithmembers,allowshrinking,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}

# Retrofit + Kotlin Coroutines — R8 full mode strips the generic
# signatures Retrofit needs to recover Response<T> from a suspend
# function's Continuation parameter. Without these rules, Retrofit
# throws "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType".
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Gson
-keep class com.greeneats.courier.data.models.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
