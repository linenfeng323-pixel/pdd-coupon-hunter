package com.kaisheng.pddhunter

import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.kaisheng.pddhunter.hooks.SilentCouponHunter

/**
 * Xposed 入口 — 由 LSPosed / EdXposed 框架加载
 * 拼多多静默券猎人 v3 — 8步策略
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        SilentCouponHunter().handleLoadPackage(lpparam)
    }
}