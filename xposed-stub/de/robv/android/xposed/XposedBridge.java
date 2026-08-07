package de.robv.android.xposed;

public class XposedBridge {
    public static void hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {}
    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {}
    public static void log(String text) {}
}