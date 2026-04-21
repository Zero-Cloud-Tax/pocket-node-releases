# Add project specific ProGuard rules here.
-keep class com.privatellm.app.inference.** { *; }
-keepclassmembers class com.privatellm.app.inference.LlamaInference {
    native <methods>;
}
-keep class com.privatellm.app.inference.LlamaInference$GenerationCallback {
    *;
}
