# MultiStore — R8 rules.
# The strategy is to keep as few as possible: every keep is unoptimised surface.

# kotlinx.serialization: the serializers are generated and referenced reflectively from the
# companion; R8 full mode would lose them.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# protobuf-lite does not use classic reflection: it builds the schema from RawMessageInfo, a
# string that names the fields one by one. R8 renames the fields but not that string, and the
# result is a crash on the FIRST DataStore read — only in release, therefore invisible throughout
# development.
#
# Verified on this project: without this rule mapping.txt contains
#   int themeMode_ -> e / boolean dynamicColor_ -> f / java.lang.String languageTag_ -> g
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# Shizuku: the rule lives in `core/installer/consumer-rules.pro`, that is in the module that
# depends on Shizuku, like apksig's. From there it arrives here on its own.

# Jsoup uses reflection on its own nodes.
-dontwarn org.jsoup.**

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
