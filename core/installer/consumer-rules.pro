# R8 rules :core:installer carries to whoever consumes it.
#
# `apksig` encodes and decodes ASN.1 **by reflection**: `Asn1BerParser` and `Asn1DerEncoder` read at
# runtime the @Asn1Class and @Asn1Field annotations on the PKCS#7 and X.509 model classes —
# SignedData, SignerInfo, Certificate, SubjectPublicKeyInfo, and the others.
#
# In full mode R8 keeps the annotations **only** on classes covered by an explicit -keep, and
# `-keepattributes RuntimeVisibleAnnotations` alone is not enough. Without this rule the classes stay
# but their annotations disappear, and every ASN.1 parse dies with
#   Asn1DecodingException: <class> is not annotated with Asn1Class
#
# Measured on this project, minified build of 24/08: installation printed
#   Caught a exception encoding the public key: ... SubjectPublicKeyInfo is not annotated ...
# There the outcome was harmless, because `ApkSigningBlockUtils.encodePublicKey` falls back on
# `KeyFactory`. **Schema v1 (JAR/PKCS#7) verification has no fallback**: it goes through
# `Asn1BerParser` and nothing else, so a v1-only signed APK would come out unverifiable in release
# and perfectly verifiable in debug.
-keep @com.android.apksig.internal.asn1.Asn1Class class * { *; }
-keep class com.android.apksig.internal.asn1.Asn1Class { *; }
-keep class com.android.apksig.internal.asn1.Asn1Field { *; }
-keep class com.android.apksig.internal.asn1.Asn1Tagging { *; }
-keep class com.android.apksig.internal.asn1.Asn1Type { *; }

# Shizuku. `ShizukuShell` starts the privileged process with
#   Shizuku.class.getDeclaredMethod("newProcess", String[], String[], String)
# because that method exists but is `private`: Shizuku 13's public API exposes the binder and the
# permissions, not a way of running a command.
#
# Reflection by name is exactly what R8 does not see. Without the keep, in release the method has
# another name (or is gone), `getDeclaredMethod` throws NoSuchMethodException and the Shizuku channel
# comes out absent **only in the build users install** — i.e. the kind of fault that only a release
# build reveals.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
