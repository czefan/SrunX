# SrunX ProGuard / R8 Rules
# Remove verbose debug logs in release builds while keeping stack traces readable.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
