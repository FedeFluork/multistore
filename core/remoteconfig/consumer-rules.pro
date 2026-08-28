# R8 rules :core:remoteconfig carries to whoever consumes it.
#
# ### BouncyCastle
#
# Of `bcprov` only the lightweight API is used here — `Ed25519Signer` and
# `Ed25519PublicKeyParameters` — and those two classes are referenced by name from the code, so R8
# keeps them by itself. What is needed instead is silencing the **absent** references the rest of the
# library carries with it: BouncyCastle's JCE provider names classes from `javax.naming`, from Java
# SE and from old security APIs that do not exist on Android. Without this, R8 does not fail but
# prints hundreds of warnings, and in a build without `-ignorewarnings` the risk is that it stops on
# a reference we will never use.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# We neither register nor use the JCE provider: if R8 can strip it, it must be allowed to. No -keep
# on org.bouncycastle.jce.** nor on org.bouncycastle.jcajce.**, and that is deliberate — a keep there
# would hold up half the library for nothing.
