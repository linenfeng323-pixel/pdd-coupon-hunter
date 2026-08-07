# ProGuard rules for PDD Coupon Hunter - Silent Mode
-keep class com.kaisheng.pddhunter.hooks.** { *; }
-keep class com.kaisheng.pddhunter.XposedInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * {
    @de.robv.android.xposed.* <fields>;
    @de.robv.android.xposed.* <methods>;
}
# 保留所有 Hook 相关类，防止混淆导致找不到方法
-keep class com.kaisheng.pddhunter.** { *; }