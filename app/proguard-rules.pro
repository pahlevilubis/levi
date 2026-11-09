# Anotasi yang hanya dipakai saat compile — aman diabaikan
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn kotlin.annotations.jvm.**
# Jsoup punya dukungan opsional re2j
-dontwarn com.google.re2j.**
# Data yang disimpan sebagai JSON
-keep class com.pahlevi.levi.data.** { *; }

# JavaMail untuk Android (baca & kirim email)
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-keep class org.apache.harmony.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-dontwarn myjava.awt.datatransfer.**
-dontwarn org.apache.harmony.**
