# Every persisted format in this app (profiles, target faces, rule sets,
# sessions, backups) is Gson reflection over FIELD NAMES. Renaming a field
# changes a stored JSON key silently: no crash, no build error, just data
# that stops loading. So the whole app package is kept verbatim.
-keep class com.rfsat.sts.** { *; }
-keepclassmembers class com.rfsat.sts.** { *; }

# Gson's own requirements
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Kotlin metadata used by data-class copy()/component1() reflection
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
#  androidx.security:security-crypto -> Google Tink
#
#  Tink is compiled against JSR-305 annotations (javax.annotation.Nullable,
#  javax.annotation.concurrent.GuardedBy) that Android does not ship and that
#  nothing needs at run time — they are source and class retention hints for
#  static analysers. R8 refuses to shrink while it cannot resolve them, which
#  fails the RELEASE build only: the debug build does not run R8, so this was
#  invisible until assembleRelease.
#
#  Warning them away rather than adding a compile-only dependency on JSR-305:
#  nothing in this app reads those annotations, and pulling in a library to
#  satisfy a reference that is never dereferenced adds a dependency for no
#  behaviour.
# ---------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# Tink loads its key managers and protobuf message classes by name, so R8
# cannot see the references and would strip them.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
