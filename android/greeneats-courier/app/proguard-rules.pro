# Add project specific ProGuard rules here.

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclasseswithmembers,allowshrinking,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keep class com.greeneats.courier.data.models.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
