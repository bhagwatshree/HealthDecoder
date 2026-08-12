# Proguard rules for Medical Assist (MA)

# Keep room classes and annotations
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Keep SQLCipher classes
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep JavaMail classes
-keep class javax.mail.** { *; }
-dontwarn javax.mail.**
-keep class com.sun.mail.** { *; }
-dontwarn com.sun.mail.**
-keep class java.beans.** { *; }
-dontwarn java.beans.**

# Gson reflects on field names/generics to (de)serialize — without these, R8's release-only
# obfuscation silently renames/strips fields and every API/local-cache JSON round-trip breaks,
# even though debug builds (unminified) never show the bug.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.stream.** { *; }
-keep class com.healthdecoder.app.model.** { *; }
-keep class com.healthdecoder.app.network.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit inspects interface methods/annotations via reflection at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn retrofit2.**
