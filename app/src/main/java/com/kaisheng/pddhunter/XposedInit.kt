package com.kaisheng.pddhunter

import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.kaisheng.pddhunter.hooks.SilentCouponHunter

/**
 * Xposed 入口 — 由 LSPosed / EdXposed 框架加载
 * 自动注入 SilentCouponHunter，纯静默后台领券
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        SilentCouponHunter().handleLoadPackage(lpparam)
    }
}