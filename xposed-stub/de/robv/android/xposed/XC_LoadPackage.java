package de.robv.android.xposed;

public class XC_LoadPackage extends XCallback {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}