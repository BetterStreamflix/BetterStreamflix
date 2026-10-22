# BetterStreamflix ProGuard / R8 rules (used when minifyEnabled is true).

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exception

# Sentry
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# dnsjava (pulled transitively) references JDK-internal SPI removed from Android
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor

# Retrofit / OkHttp / Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Conscrypt / Cronet
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# Rhino (provider extractors)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Providers / models used via reflection / serialization
-keep class com.betterstreamflix.app.providers.** { *; }
-keep class com.betterstreamflix.app.models.** { *; }
-keep class com.betterstreamflix.app.extractors.** { *; }
-keep class com.betterstreamflix.app.sync.** { *; }
-keep class com.betterstreamflix.app.profiles.** { *; }

# Gson DTOs in utils without @SerializedName (R8-H1)
-keep class com.betterstreamflix.app.utils.AesHelper$AesData { *; }
-keep class com.betterstreamflix.app.utils.UserDataCache$* { *; }
-keep class com.betterstreamflix.app.utils.HomeCacheStore$* { *; }
-keep class com.betterstreamflix.app.utils.SubtitleFileCache$* { *; }

# DnsResolver used from workers/providers (BETTERSTREAMFLIX-1Z NCDFE hardening)
-keep class com.betterstreamflix.app.utils.DnsResolver { *; }
-keepclassmembers class com.betterstreamflix.app.utils.DnsResolver { *; }

# Plugin reflection API (R8-H2)
-keep class com.betterstreamflix.app.platform.plugins.** { *; }

# Cast OptionsProvider FQCN in manifests (R8-M2)
-keep class com.betterstreamflix.app.cast.CastOptionsProvider { *; }

# WorkManager workers outside kept packages (R8-M3)
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# Room DAOs (R8-M5 belt-and-suspenders)
-keep @androidx.room.Dao interface * { *; }

# Kotlinx serialization app types (R8-H4 official-style keep)
-keep,includedescriptorclasses class com.betterstreamflix.app.**$$serializer { *; }
-if @kotlinx.serialization.Serializable class com.betterstreamflix.app.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Gson TypeToken anonymous subclasses (HomeCacheStore / providers / workers)
# Keep without allowshrinking so Signature + R8 full mode stay safe (R8-M1).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
