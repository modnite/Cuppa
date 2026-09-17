# cups-core ProGuard rules
-keep class com.cuppa.cups.CupsEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
