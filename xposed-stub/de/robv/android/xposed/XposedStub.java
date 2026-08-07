package de.robv.android.xposed;

public interface IXposedMod {
}

public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;

    class XC_LoadPackage extends XCallback {
        public static class LoadPackageParam {
            public String packageName;
            public String processName;
            public ClassLoader classLoader;
            public boolean isFirstApplication;
        }
    }
}

class XCallback {
    // Stub
}

class XposedBridge {
    public static void hookMethod(Member method, XC_MethodHook callback) {}
    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {}
    public static void log(String text) {}
}

class XC_MethodHook extends XCallback {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}

class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object newInstance(Class<?> clazz, Object... args) { return null; }
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {}
    public static void findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {}
}