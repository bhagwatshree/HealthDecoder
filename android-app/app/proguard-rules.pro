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
