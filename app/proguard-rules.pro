# Keep inference classes and JNI bridge
-keep class com.pocketnode.app.inference.** { *; }
-keepclassmembers class com.pocketnode.app.inference.LlamaInference {
    native <methods>;
}
-keep class com.pocketnode.app.inference.LlamaCallback { *; }
-keep class com.pocketnode.app.licensing.** { *; }

# Ktor embedded server
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-dontwarn kotlinx.serialization.**

# Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
