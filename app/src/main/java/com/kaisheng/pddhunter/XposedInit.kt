package com.kaisheng.pddhunter

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_LoadPackage.LoadPackageParam
import com.kaisheng.pddhunter.hooks.SilentCouponHunter

/**
 * Xposed 入口 — 由 LSPosed / EdXposed 框架加载
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        SilentCouponHunter().handleLoadPackage(lpparam)
    }
}