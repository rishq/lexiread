# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Preserve Line Numbers for Debugging
-keepattributes SourceFile,LineNumberTable

# Kotlin Reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlin.reflect.**
-keep class kotlin.reflect.** { *; }

# Moshi & JSON Parsing
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class com.example.data.remote.dto.** { *; }
-keepclassmembers class com.example.data.remote.dto.** { *; }

# Domain Models & Local Entities
-keep class com.example.domain.model.** { *; }
-keepclassmembers class com.example.domain.model.** { *; }
-keep class com.example.data.local.entity.** { *; }
-keepclassmembers class com.example.data.local.entity.** { *; }
-keep class com.example.data.local.dao.** { *; }
-keepclassmembers class com.example.data.local.dao.** { *; }

# Retrofit & OkHttp
-dontnote retrofit2.Platform
-dontwarn retrofit2.Platform$Java8
-keepattributes Signature,Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

