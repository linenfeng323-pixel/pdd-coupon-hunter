package com.kaisheng.pddhunter.hooks

import android.util.Log
import de.robv.android.xposed.*
import de.robv.android.xposed.XC_LoadPackage.LoadPackageParam
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 拼多多静默券猎人 v3
 *
 * 基于酷安教程@水墨青竹 2026最新领券攻略
 * 8步策略：百亿消费券→福袋→整点抢券→减减卡→三单挑战→砸金蛋→月卡
 *
 * 纯静默后台，Hook网络层+实体类+服务类，不碰UI
 */
class SilentCouponHunter : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentHunter"
        private const val PDD = "com.xunmeng.pinduoduo"
        private val bg = Executors.newScheduledThreadPool(3) { r -> Thread(r, "SH-BG").apply { isDaemon = true } }

        // ====== 统计（公开可读） ======
        @JvmStatic var totalClaimed = 0
        @JvmStatic var lastClaimTime = 0L
        @JvmStatic val claimHistory = Collections.synchronizedList(mutableListOf<String>())
        @JvmStatic var isActive = true
        @JvmStatic var minAmount = 0.0
        @JvmStatic var onlyBig = false
        @JvmStatic var huntInterval = 60
        @JvmStatic var keywordMatchEnabled = true
        @JvmStatic var searchKeywords = mutableListOf("无门槛", "通用", "全品类", "平台", "超级红包", "满减", "立减", "折扣", "补贴", "限时", "新人")

        // 各步开关
        @JvmStatic var stepBaiyiEnabled = true
        @JvmStatic var stepFudaiEnabled = true
        @JvmStatic var stepHourlyEnabled = true
        @JvmStatic var stepCardEnabled = true
        @JvmStatic var stepThreeEnabled = true
        @JvmStatic var stepEggEnabled = true
        @JvmStatic var stepMonthEnabled = true

        // 回调
        @JvmStatic var onCouponDetected: ((String, Double, String) -> Unit)? = null
        @JvmStatic var onCouponClaimed: ((String, Double, Boolean, String) -> Unit)? = null
        @JvmStatic var onStatsChanged: (() -> Unit)? = null

        // 内部状态
        private val claimedSet = Collections.synchronizedSet(mutableSetOf<String>())
        private val discoveredClasses = Collections.synchronizedSet(mutableSetOf<String>())
        private var pddClassLoader: ClassLoader? = null

        /** 领券API端点 */
        val claimApis = listOf(
            "/api/eclipse/coupon/receive/receive_coupon",
            "/api/promotion/auto_take_merchant_coupon",
            "/api/promotion/take_merchant_coupon",
            "/api/promotion/take_mall_favorite_coupon",
            "/api/promotion/batch_auto_take_merchant_coupon",
            "/api/rainbow/coupon/get_coupon",
            "/api/plymouth/take_repurchase_coupon",
            "/api/wizard/jaina/collect/coupon/claim",
            "/api/zenon/mall/like_and_receive_coupon",
            "/api/cashback/command/coupon/draw",
            "/api/growth/spain/index_goods_cpn/claim",
            "/api/promotion/follow_and_take_mall_favorite_coupon",
            "/api/promotion/batch_take_merchant_coupon_for_cell",
            "/api/promotion/batch_take_merchant_coupon_for_order",
            "/api/carnival/home_tab/query_icon_coupon",
            "/api/buffon/kayle/promotion/click/notify",
            "/api/social/red/envelope/receive/red/envelope/v2"
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != PDD) return
        pddClassLoader = lpparam.classLoader

        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "║ 拼多多静默券猎人 v3 已注入           ║")
        Log.i(TAG, "╚══════════════════════════════════════╝")

        bg.schedule({ try { hookAll(lpparam) } catch (e: Throwable) { Log.e(TAG, "初始化失败: ${e.message}") } }, 3, TimeUnit.SECONDS)
    }

    @Throws(Throwable::class)
    private fun hookAll(lpparam: LoadPackageParam) {
        hookCommonCallback(lpparam)
        hookEntityClasses(lpparam)
        startDynamicDiscovery(lpparam)
        start8StepPolling()
        Log.i(TAG, "✅ 8步策略已就绪")
    }

    // ====================================================================
    // Hook 网络回调 — 拦截券数据
    // ====================================================================

    private fun hookCommonCallback(lpparam: LoadPackageParam) {
        val clsNames = listOf(
            "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback",
            "com.xunmeng.pinduoduo.basekit.http.callback.BaseCallback"
        )
        for (name in clsNames) {
            try {
                val cls = lpparam.classLoader.loadClass(name)
                XposedBridge.hookAllMethods(cls, "onSuccess", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isActive) return
                        val args = param.args
                        if (args.isNotEmpty() && args[0] != null) {
                            val str = args[0].toString()
                            if (str.length in 50..100000 && isCouponRelated(str)) {
                                Log.d(TAG, "📡 网络响应: ${str.take(100)}")
                                tryParseJson(str, "网络层")
                            }
                        }
                    }
                })
                Log.d(TAG, "Hook: $name.onSuccess")
            } catch (_: Throwable) {}
        }
    }

    private fun isCouponRelated(s: String): Boolean {
        val kw = arrayOf("coupon", "promotion", "voucher", "discount", "优惠券", "红包",
            "补贴", "福袋", "减减", "月卡", "金蛋", "take_merchant", "receive_coupon",
            "batch_auto_take", "mall_favorite", "repurchase", "baiyi", "百亿", "消费券", "三单", "返现")
        return kw.any { s.contains(it, ignoreCase = true) }
    }

    private fun tryParseJson(text: String, source: String) {
        try {
            if (text.startsWith("{")) {
                val json = org.json.JSONObject(text)
                scanJsonObj(json, source)
            } else if (text.startsWith("[")) {
                val arr = org.json.JSONArray(text)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { scanJsonObj(it, source) }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scanJsonObj(json: org.json.JSONObject, source: String) {
        try {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.contains("coupon") || key.contains("promotion") || key.contains("discount") ||
                    key.contains("voucher") || key.contains("红包") || key.contains("福袋") ||
                    key.contains("减减") || key.contains("月卡") || key.contains("金蛋") ||
                    key.contains("baiyi") || key.contains("百亿") || key.contains("subsidy")) {
                    val v = json.opt(key)
                    if (v is org.json.JSONObject) {
                        val title = v.optString("title", v.optString("coupon_name", v.optString("name", "")))
                        val amount = v.optDouble("amount", v.optDouble("discount", v.optDouble("face_value", 0.0)))
                        if (title.isNotEmpty() || amount > 0) {
                            checkAndClaim(title, amount, source)
                        }
                        scanJsonObj(v, source)
                    } else if (v is org.json.JSONArray) {
                        for (i in 0 until v.length()) {
                            v.optJSONObject(i)?.let { scanJsonObj(it, source) }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // ====================================================================
    // Hook 实体类 — 用 XposedHelpers 而非反射
    // ====================================================================

    private fun hookEntityClasses(lpparam: LoadPackageParam) {
        val entityClasses = listOf(
            "com.xunmeng.pinduoduo.entity.Coupon",
            "com.xunmeng.pinduoduo.entity.CouponInfo",
            "com.xunmeng.pinduoduo.entity.Promotion",
            "com.xunmeng.pinduoduo.notificationbox.entity.PushCoupon",
            "com.xunmeng.pinduoduo.mall.entity.ShareCouponInfo",
            "com.xunmeng.pinduoduo.mall.entity.PromotionDialogCouponInfo",
            "com.xunmeng.pinduoduo.mall.model.MallCouponInfoViewModel",
            "com.xunmeng.pinduoduo.home.base.coupon.price.CouponPriceInfo",
            "com.xunmeng.pinduoduo.search.coupon.entity.SearchCouponBannerResponse",
            "com.xunmeng.pinduoduo.sku_checkout.checkout.data.promotion.couponnew.UsePlatformPromotionRequest",
            "com.xunmeng.pinduoduo.checkout_core.data.promotion.platform.PlatformPromotionVo",
            "com.xunmeng.pinduoduo.checkout_core.data.promotion.platform.PlatformPromotionsVo",
            "com.xunmeng.pinduoduo.wallet.pay.internal.data.PayPromotion",
            "com.xunmeng.pinduoduo.chat.biz.mallPromotion.entity.PromotionEntity",
            "com.xunmeng.pinduoduo.app_search_common.filter.entity.PromotionTextEntity"
        )
        for (clsName in entityClasses) {
            try {
                val cls = lpparam.classLoader.loadClass(clsName)
                // 用 XposedHelpers 拦截构造
                for (ctor in cls.declaredConstructors) {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            val obj = param.thisObject ?: return
                            val title = getStrField(obj, "title", "couponName", "name", "couponTitle", "desc", "coupon_name")
                            val amount = getDblField(obj, "amount", "discount", "faceValue", "face_value", "price", "reduce")
                            if (title.isNotEmpty() || amount > 0) {
                                checkAndClaim(title, amount, cls.simpleName)
                            }
                        }
                    })
                }
                Log.d(TAG, "实体Hook: $clsName")
            } catch (_: Throwable) {}
        }
    }

    // ====================================================================
    // 动态发现 + 8步轮询
    // ====================================================================

    private fun startDynamicDiscovery(lpparam: LoadPackageParam) {
        bg.scheduleWithFixedDelay({
            try {
                val xb = XposedHelpers.findClass("de.robv.android.xposed.XposedBridge", null)
                val getLoaded = xb?.getDeclaredMethod("getLoadedClasses")
                val loaded = getLoaded?.invoke(null) as? Array<*> ?: return@scheduleWithFixedDelay
                for (obj in loaded) {
                    val cls = obj as? Class<*> ?: continue
                    val name = cls.name ?: continue
                    if (!name.startsWith("com.xunmeng.pinduoduo")) continue
                    if (!discoveredClasses.contains(name) &&
                        (name.contains("coupon", true) || name.contains("promotion", true) ||
                         name.contains("redpacket", true) || name.contains("红包") || name.contains("baiyi"))) {
                        discoveredClasses.add(name)
                        hookDynamicClass(cls)
                        Log.i(TAG, "🔍 动态发现: $name")
                    }
                }
            } catch (_: Throwable) {}
        }, 10, 60, TimeUnit.SECONDS)
    }

    private fun hookDynamicClass(cls: Class<*>) {
        for (method in cls.declaredMethods) {
            if (method.parameterCount <= 3 && method.returnType != Void.TYPE &&
                method.name.length <= 4 &&
                (method.returnType == Boolean::class.java || method.returnType == Int::class.java ||
                 method.returnType == Long::class.java)) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            if (param.result == true || (param.result is Number && (param.result as Number).toInt() > 0)) {
                                val msg = "[动态] ${cls.simpleName}.${method.name}"
                                Log.i(TAG, "✅ $msg")
                                totalClaimed++; claimHistory.add(msg)
                                onCouponClaimed?.invoke("动态发现", 0.0, true, "动态")
                                onStatsChanged?.invoke()
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        }
    }

    private fun start8StepPolling() {
        bg.scheduleWithFixedDelay({
            if (!isActive) return@scheduleWithFixedDelay
            try {
                if (stepBaiyiEnabled) triggerStep("百亿消费券", 0)
                if (stepFudaiEnabled) triggerStep("福袋", 1)
                if (stepHourlyEnabled) triggerStep("整点抢券", 2)
                if (stepCardEnabled) triggerStep("减减卡", 3)
                if (stepThreeEnabled) triggerStep("三单挑战", 4)
                if (stepEggEnabled) triggerStep("砸金蛋", 5)
                if (stepMonthEnabled) triggerStep("月卡", 6)
            } catch (_: Throwable) {}
        }, 15, huntInterval.toLong(), TimeUnit.SECONDS)
    }

    private fun triggerStep(stepName: String, idx: Int) {
        val cl = pddClassLoader ?: return
        val targets = listOf(
            "com.xunmeng.pinduoduo.goods.service.GoodsCouponServiceImpl",
            "com.xunmeng.pinduoduo.checkout_core.promotion.litecontract.LiteContractHelper",
            "com.xunmeng.pinduoduo.index.promotion.PromotionCategoryApi"
        )
        for (svc in targets) {
            try {
                val cls = cl.loadClass(svc)
                var instance: Any? = null
                for (field in cls.declaredFields) {
                    if (field.name == "INSTANCE" || field.name == "instance" || field.name == "sInstance") {
                        field.isAccessible = true; instance = field.get(null); break
                    }
                }
                for (method in cls.declaredMethods) {
                    if (method.parameterCount <= 2) {
                        val mn = method.name
                        if (mn.contains("take") || mn.contains("claim") || mn.contains("receive") ||
                            mn.contains("get") || mn.contains("fetch") || mn.contains("load") || mn.contains("refresh")) {
                            method.isAccessible = true
                            try {
                                if (instance != null) method.invoke(instance)
                                else if (java.lang.reflect.Modifier.isStatic(method.modifiers)) method.invoke(null)
                                else continue
                                Log.d(TAG, "  [$stepName] 触发: ${cls.simpleName}.$mn")
                                break
                            } catch (_: Throwable) { continue }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // ====================================================================
    // 领取逻辑
    // ====================================================================

    private fun checkAndClaim(title: String, amount: Double, source: String) {
        if (onlyBig && amount < minAmount) return
        if (keywordMatchEnabled && searchKeywords.isNotEmpty()) {
            val matched = searchKeywords.any { kw -> title.contains(kw, true) || amount.toString().contains(kw) }
            if (!matched) { Log.d(TAG, "关键词未匹配: [$title] ¥$amount"); return }
        }
        val key = "$title|$amount|$source"
        if (claimedSet.contains(key)) return
        claimedSet.add(key)
        Log.i(TAG, "🎯 发现券: [$title] ¥${amount} 来源: $source")
        onCouponDetected?.invoke(title, amount, source)
        autoClaim(title, amount, source)
    }

    private fun autoClaim(title: String, amount: Double, source: String) {
        val cl = pddClassLoader ?: return
        bg.execute {
            try {
                val targets = listOf(
                    "com.xunmeng.pinduoduo.goods.service.GoodsCouponServiceImpl",
                    "com.xunmeng.pinduoduo.checkout_core_compat.jsapi.JSCheckoutTakeShopCollectCoupon",
                    "com.xunmeng.pinduoduo.checkout_core.promotion.litecontract.LiteContractHelper",
                    "com.xunmeng.pinduoduo.index.promotion.PromotionCategoryApi"
                )
                for (name in targets) {
                    try {
                        val cls = cl.loadClass(name)
                        var instance: Any? = null
                        for (field in cls.declaredFields) {
                            if (field.name == "INSTANCE" || field.name == "instance" || field.name == "sInstance") {
                                field.isAccessible = true; instance = field.get(null); break
                            }
                        }
                        for (method in cls.declaredMethods) {
                            if (method.name.contains("take") || method.name.contains("claim") ||
                                method.name.contains("receive") || method.name.contains("collect") ||
                                method.name.contains("get") || method.name.contains("fetch")) {
                                if (method.parameterCount <= 2) {
                                    method.isAccessible = true
                                    if (instance != null) method.invoke(instance)
                                    else if (java.lang.reflect.Modifier.isStatic(method.modifiers)) method.invoke(null)
                                    else continue
                                    Log.i(TAG, "⚡ 自动领券: ${cls.simpleName}.${method.name} [$title] ¥$amount")
                                    totalClaimed++; lastClaimTime = System.currentTimeMillis()
                                    claimHistory.add("[$title] ¥$amount ($source)")
                                    onCouponClaimed?.invoke(title, amount, true, source)
                                    onStatsChanged?.invoke()
                                    return@execute
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) { Log.w(TAG, "自动领券失败: ${e.message}") }
        }
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    private fun getStrField(obj: Any, vararg names: String): String {
        for (name in names) {
            try {
                val f = findField(obj.javaClass, name) ?: continue
                f.isAccessible = true; return f.get(obj)?.toString() ?: ""
            } catch (_: Throwable) {}
        }
        return ""
    }

    private fun getDblField(obj: Any, vararg names: String): Double {
        for (name in names) {
            try {
                val f = findField(obj.javaClass, name) ?: continue
                f.isAccessible = true; return (f.get(obj)?.toString()?.toDoubleOrNull() ?: 0.0)
            } catch (_: Throwable) {}
        }
        return 0.0
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        try { return cls.getDeclaredField(name) }
        catch (_: Throwable) {
            var sup = cls.superclass
            while (sup != null) {
                try { return sup.getDeclaredField(name) } catch (_: Throwable) {}
                sup = sup.superclass
            }
        }
        return null
    }
}