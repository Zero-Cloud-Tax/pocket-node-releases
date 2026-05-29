# ── JNI bridge ─────────────────────────────────────────────────────────────────
-keep class com.pocketnode.app.inference.LlamaInference { *; }
-keepclassmembers class com.pocketnode.app.inference.LlamaInference {
    native <methods>;
}
-keep interface com.pocketnode.app.inference.LlamaCallback { *; }
# All concrete LlamaCallback implementations (anonymous objects in ChatViewModel / ApiServer)
-keep class * implements com.pocketnode.app.inference.LlamaCallback { *; }
-keep class com.pocketnode.app.inference.** { *; }

# ── Licensing ───────────────────────────────────────────────────────────────────
-keep class com.pocketnode.app.licensing.** { *; }

# ── Room entities and DAOs ──────────────────────────────────────────────────────
-keep class com.pocketnode.app.data.model.** { *; }
-keep @androidx.room.Dao interface com.pocketnode.app.data.** { *; }
# Room-generated _Impl classes are referenced reflectively at runtime
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract !static <methods>;
}

# ── Setup / onboarding data classes ────────────────────────────────────────────
-keep class com.pocketnode.app.setup.** { *; }

# ── Download / storage data classes ────────────────────────────────────────────
-keep class com.pocketnode.app.data.ModelDownloadSpec { *; }
-keep class com.pocketnode.app.data.StorageStats { *; }
-keep class com.pocketnode.app.data.VerificationStatus { *; }

# ── Ktor embedded server ────────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Kotlinx serialization ───────────────────────────────────────────────────────
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-dontwarn kotlinx.serialization.**

# ── Coroutines ──────────────────────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Markwon ─────────────────────────────────────────────────────────────────────
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── PdfBox-Android ───────────────────────────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ── Strip verbose debug logs from release builds ────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
