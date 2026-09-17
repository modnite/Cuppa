# Cuppa ProGuard Rules

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep CupsEngine (JNI bridge)
-keep class com.cuppa.cups.CupsEngine { *; }

# Compose
-dontwarn androidx.compose.**
