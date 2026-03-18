# In Your Face — Adaptive Interface Overlay System
# Authors: Sunni (Sir) Morningstar and Cael Devo
#
# proguard-rules.pro
# Release build minification and obfuscation rules.

# ─── Keep application classes ────────────────────────────────────────────────

# Application entry point
-keep class com.inyourface.app.InYourFaceApp { *; }

# Both runtimes must survive — they're referenced by name in the manifest
-keep class com.inyourface.app.diplomat.DiplomatRuntime { *; }
-keep class com.inyourface.app.representative.RepresentativeRuntime { *; }
-keep class com.inyourface.app.representative.RepresentativeRuntime$RepresentativeBinder { *; }
-keep class com.inyourface.app.BootReceiver { *; }

# UI Activities
-keep class com.inyourface.app.ui.** { *; }

# ─── Data models — must survive JSON serialization ────────────────────────────

# TranslationKey and all nested model classes are serialized via Gson
-keep class com.inyourface.app.model.** { *; }

# Overlay markers — used in ConcurrentHashMap and serialized by type name
-keep class com.inyourface.app.overlay.** { *; }

# Grid system — referenced by Diplomat at runtime
-keep class com.inyourface.app.grid.** { *; }

# ─── Enums ────────────────────────────────────────────────────────────────────

# Kotlin enums used in when() expressions — must keep name() and values()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public final java.lang.String name();
    public final int ordinal();
}

# ─── Gson ─────────────────────────────────────────────────────────────────────

-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ─── Kotlin ───────────────────────────────────────────────────────────────────

-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { <fields>; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ─── Android ──────────────────────────────────────────────────────────────────

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.view.View

# View constructors (required for XML inflation)
-keepclasseswithmembers class * {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ─── Suppress warnings ────────────────────────────────────────────────────────

-dontwarn java.lang.invoke.**
-dontwarn **$$Lambda$*
-dontwarn androidx.**
