# Add project specific ProGuard rules here.
# Retrofit, OkHttp, Moshi (codegen), Room and Compose ship consumer rules;
# broad -keep class ** rules defeat R8 shrinking and inflate the APK.

# Preserve Line Numbers for Debugging
-keepattributes SourceFile,LineNumberTable

# Kotlin Reflection (used by nothing at runtime after codegen; keep minimal)
-dontwarn kotlin.reflect.**

# Moshi: keep @JsonClass-generated adapters (consumer rules handle the rest)
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <fields>;
}

# Retrofit interface methods are looked up reflectively via generics
-keepattributes Signature,Exceptions
