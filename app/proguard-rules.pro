# Privvy ProGuard rules

# Virtualization engine uses heavy reflection on hidden framework APIs and on guest APK classes.
# Everything reached reflectively must be kept.

# Keep all engine/hook/io classes (reflectively accessed, JNI-registered)
-keep class com.privvy.app.hook.** { *; }
-keep class com.privvy.app.engine.** { *; }
-keep class com.privvy.app.io.** { *; }

# JNI bridge symbols
-keepclasseswithmembernames class * {
    native <methods>;
}

# Hidden API bypass library
-keep class org.lsposed.hiddenapibypass.** { *; }

# Guest APK classes are loaded via DexClassLoader at runtime — never obfuscate our stub entry points.
-keep class com.privvy.app.core.stub.** { *; }

# Binder proxies use java.lang.reflect.Proxy on framework interfaces
-keep interface android.app.IActivityTaskManager { *; }
-dontwarn android.app.**
-dontwarn android.content.pm.**
