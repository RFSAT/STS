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
