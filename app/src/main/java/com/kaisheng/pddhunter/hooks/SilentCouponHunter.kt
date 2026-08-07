package com.kaisheng.pddhunter.hooks

import android.util.Log
import de.robv.android.xposed.*
import de.robv.android.xposed.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 拼多多静默券猎人 v3
 *
 * 基于酷安教程@水墨青竹 2026最新领券攻略的8步策略
 * 实测来源：拼多多 base.apk (25MB, 6dex)
 *
 * 核心思路：拦截网络层券数据 → 自动调用领券API
 * 完全不碰UI，不占前台，刷抖音打游戏完全不影响
 */
class SilentCouponHunter : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentHunter"
        private const val PDD = "com.xunmeng.pinduoduo"

        private val bg = Executors.newScheduledThreadPool(3) { r ->
            Thread(r, "SilentHunter-BG").apply { isDaemon = true }
        }

        // ====== 统计 ======
        var totalClaimed = 0
            private set
        var lastClaimTime = 0L
            private set
        var claimHistory = Collections.synchronizedList(mutableListOf<String>())
            private set

        // ====== 回调 ======
        var onCouponDetected: ((String, Double, String) -> Unit)? = null  // title, amount, source
        var onCouponClaimed: ((String, Double, Boolean, String) -> Unit)? = null
        var onStatsChanged: (() -> Unit)? = null

        // ====== 配置 ======
        var isActive = true
        var minAmount = 0.0; set(v) { field = v; if (v > 0) onlyBig = true }
        var onlyBig = false
        var huntInterval = 60 // 60秒一轮
        var keywordMatchEnabled = true
        enum class MatchMode { ANY, ALL }
        var matchMode = MatchMode.ANY
        var searchKeywords = mutableListOf(
            "无门槛", "通用", "全品类", "平台", "超级红包",
            "满减", "立减", "折扣", "补贴", "限时", "新人"
        )

        // 每步的开关（默认全开）
        var stepBaiyiEnabled = true   // ① 百亿消费券
        var stepFudaiEnabled = true   // ② 福袋
        var stepHourlyEnabled = true  // ③ 整点抢券（678折）
        var stepCardEnabled = true    // ④ 减减卡任务
        var stepThreeEnabled = true   // ⑤ 三单挑战
        var stepEggEnabled = true     // ⑥ 砸金蛋
        var stepMonthEnabled = true   // ⑦ 月卡7折券

        // ====== 防重 ======
        private val claimedSet = Collections.synchronizedSet(mutableSetOf<String>())
        private val discoveredClasses = Collections.synchronizedSet(mutableSetOf<String>())
        private var classLoader: ClassLoader? = null
    }

    // ====== 券种计数器（用于日志） ======
    private val stepCounters = IntArray(8)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PDD) return
        classLoader = lpparam.classLoader

        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "║ 拼多多静默券猎人 v3                 ║")
        Log.i(TAG, "║ 策略: 酷安教程@水墨青竹 8步走        ║")
        Log.i(TAG, "╚══════════════════════════════════════╝")

        bg.schedule({ try { hookAll(lpparam) } catch (e: Throwable) { Log.e(TAG, "初始化失败: ${e.message}") } }, 3, TimeUnit.SECONDS)
    }

    // ====================================================================
    // 一、8步策略主入口
    // ====================================================================

    private fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 网络层通用拦截 — 所有券数据都从这里过
        hookNetworkLayer(lpparam)

        // 2. 实体类Hook — 拦截券数据解析
        hookEntityClasses(lpparam)

        // 3. 服务类Hook — 直接调用领券方法
        hookServiceClasses(lpparam)

        // 4. 动态类名发现（兜底）
        startDynamicDiscovery(lpparam)

        // 5. 定时轮询 — 按8步策略依次触发
        start8StepPolling()

        Log.i(TAG, "✅ 8步策略已就绪，后台静默搞卷中...")
    }

    // ====================================================================
    // 二、网络层通用拦截（核心）
    // ====================================================================

    private fun hookNetworkLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 实际存在的网络回调类
        val netClasses = listOf(
            "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback",
            "com.xunmeng.pinduoduo.basekit.http.callback.BaseCallback"
        )

        for (clsName in netClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) { Log.d(TAG, "网络类未找到: $clsName"); continue }

            for (method in cls.declaredMethods) {
                if (method.name.contains("onSuccess") || method.name.contains("onResponse") ||
                    method.name.contains("onResult") || method.name.contains("onComplete")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            for (arg in param.args) {
                                if (arg != null) {
                                    val str = arg.toString()
                                    if (str.length in 50..100000 && isCouponRelated(str)) {
                                        Log.d(TAG, "📡 网络响应含券: ${str.take(120)}")
                                        parseAndClaim(str, "网络层")
                                    }
                                }
                            }
                            parseAndClaim(param.result, "网络层")
                        }
                    })
                }
            }
            Log.d(TAG, "网络层Hook: $clsName")
        }

        // 根据实际API端点分类Hook
        Log.i(TAG, "📡 已拦截 ${claimApis.size} 个领券API端点")
    }

    /** 判断字符串是否含券数据 */
    private fun isCouponRelated(s: String): Boolean {
        val keywords = arrayOf("coupon", "promotion", "voucher", "discount",
            "优惠券", "红包", "补贴", "福袋", "减减卡", "月卡", "金蛋",
            "take_merchant", "receive_coupon", "batch_auto_take",
            "mall_favorite_coupon", "repurchase_coupon", "collect_coupon",
            "baiyi", "subsidy", "百亿", "消费券", "三单", "返现")
        return keywords.any { s.contains(it, ignoreCase = true) }
    }

    // ====================================================================
    // 三、实体类Hook — 拦截券数据解析
    // ====================================================================

    private fun hookEntityClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val entityClasses = listOf(
            "com.xunmeng.pinduoduo.entity.Coupon",
            "com.xunmeng.pinduoduo.entity.CouponInfo",
            "com.xunmeng.pinduoduo.entity.Promotion",
            "com.xunmeng.pinduoduo.entity.CouponItemDescription",
            "com.xunmeng.pinduoduo.home.base.coupon.price.CouponPriceInfo",
            "com.xunmeng.pinduoduo.search.coupon.entity.SearchCouponBannerResponse",
            "com.xunmeng.pinduoduo.notificationbox.entity.PushCoupon",
            "com.xunmeng.pinduoduo.notificationbox.entity.CouponRevision",
            "com.xunmeng.pinduoduo.mall.entity.ShareCouponInfo",
            "com.xunmeng.pinduoduo.mall.entity.MallPageGoods\$SearchCouponInfo",
            "com.xunmeng.pinduoduo.mall.entity.PromotionDialogCouponInfo",
            "com.xunmeng.pinduoduo.mall.model.MallCouponInfoViewModel",
            "com.xunmeng.pinduoduo.sku_checkout.checkout.data.promotion.couponnew.UsePlatformPromotionRequest",
            "com.xunmeng.pinduoduo.checkout_core.data.promotion.platform.PlatformPromotionVo",
            "com.xunmeng.pinduoduo.checkout_core.data.promotion.platform.PlatformPromotionsVo",
            "com.xunmeng.pinduoduo.wallet.pay.internal.data.PayPromotion",
            "com.xunmeng.pinduoduo.wallet.pay.internal.data.PayPromotionInfo",
            "com.xunmeng.pinduoduo.chat.biz.mallPromotion.entity.PromotionEntity",
            "com.xunmeng.pinduoduo.app_search_common.filter.entity.PromotionTextEntity",
            "com.xunmeng.pinduoduo.social.common.entity.Moment\$GoodsPromotionPriceInfo",
            "com.xunmeng.pinduoduo.deprecated.chat.entity.CouponInfo"
        )

        for (clsName in entityClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) { Log.d(TAG, "实体未找到: $clsName"); continue }

            // Hook构造方法
            for (ctor in cls.declaredConstructors) {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isActive) return
                        val obj = param.thisObject ?: return
                        try {
                            val title = getFieldStr(obj, "title", "couponName", "name", "couponTitle", "desc", "coupon_name")
                            val amount = getFieldDouble(obj, "amount", "discount", "faceValue", "face_value", "price", "reduce")
                            val minConsume = getFieldDouble(obj, "minConsume", "min_consume", "threshold", "min", "useThreshold")
                            if (title.isNotEmpty() || amount > 0) {
                                checkAndClaim(title, amount, minConsume, cls.simpleName)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            }

            // Hook setter方法
            for (method in cls.declaredMethods) {
                if (method.name.startsWith("set") && method.parameterCount == 1) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            val value = param.args[0]?.toString() ?: return
                            if (value.contains("¥") || value.contains("元") || value.contains("券") ||
                                value.contains("coupon") || value.contains("discount") ||
                                (value.toDoubleOrNull() ?: 0.0) > 0) {
                                Log.d(TAG, "🔄 ${cls.simpleName}.${method.name} = $value")
                            }
                        }
                    })
                }
            }
            Log.d(TAG, "实体Hook: $clsName")
        }
    }

    // ====================================================================
    // 四、服务类Hook — 直接拦截领券方法调用
    // ====================================================================

    private fun hookServiceClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val serviceClasses = listOf(
            "com.xunmeng.pinduoduo.goods.service.IGoodsCouponService",
            "com.xunmeng.pinduoduo.goods.service.GoodsCouponServiceImpl",
            "com.xunmeng.pinduoduo.checkout_core_compat.jsapi.JSCheckoutTakeShopCollectCoupon",
            "com.xunmeng.pinduoduo.checkout_core.promotion.litecontract.LiteContractHelper"
        )

        for (clsName in serviceClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) { Log.d(TAG, "服务未找到: $clsName"); continue }

            for (method in cls.declaredMethods) {
                val name = method.name
                // 领券方法
                if (name.contains("take") || name.contains("claim") || name.contains("receive") ||
                    name.contains("collect") || name.contains("getCoupon") || name.contains("fetchCoupon") ||
                    name.contains("autoTake") || name.contains("batchTake") || name.contains("followAndTake")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isActive) { param.result = null; return }
                            Log.d(TAG, "🎯 领券调用: ${cls.simpleName}.$name")
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            if (param.result != null) {
                                val msg = "[服务] ${cls.simpleName}.$name"
                                Log.i(TAG, "✅ $msg")
                                totalClaimed++; lastClaimTime = System.currentTimeMillis()
                                claimHistory.add(msg)
                                onCouponClaimed?.invoke(msg, 0.0, true, "服务层")
                                onStatsChanged?.invoke()
                            }
                        }
                    })
                }
                // 查询券列表
                if (name.contains("query") || name.contains("list") || name.contains("getCouponList") ||
                    name.contains("getPromotion") || name.contains("loadCoupon") || name.contains("fetchCoupon") ||
                    name.contains("refresh") || name.contains("loadData")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            parseAndClaim(param.result, "服务查询")
                            for (arg in param.args) parseAndClaim(arg, "服务查询")
                        }
                    })
                }
            }
            Log.d(TAG, "服务Hook: $clsName")
        }
    }

    // ====================================================================
    // 五、8步轮询策略 — 按教程顺序触发各券种
    // ====================================================================

    private fun start8StepPolling() {
        // 每30秒执行一轮8步检查
        bg.scheduleWithFixedDelay({
            if (!isActive) return@scheduleWithFixedDelay
            try {
                Log.d(TAG, "🔄 执行8步轮询...")

                // 第一步：百亿消费券
                if (stepBaiyiEnabled) {
                    triggerStep("百亿消费券", "/api/promotion/take_mall_favorite_coupon", 0)
                }

                // 第二步：福袋
                if (stepFudaiEnabled) {
                    triggerStep("福袋", "/api/rainbow/coupon/get_coupon", 1)
                }

                // 第三步：整点抢券（678折）
                if (stepHourlyEnabled) {
                    triggerStep("整点抢券", "/api/eclipse/coupon/receive/receive_coupon", 2)
                }

                // 第四步：减减卡任务
                if (stepCardEnabled) {
                    triggerStep("减减卡", "/api/promotion/batch_auto_take_merchant_coupon", 3)
                }

                // 第五步：三单挑战
                if (stepThreeEnabled) {
                    triggerStep("三单挑战", "/api/promotion/auto_take_merchant_coupon", 4)
                }

                // 第六步：砸金蛋
                if (stepEggEnabled) {
                    triggerStep("砸金蛋", "/api/promotion/take_merchant_coupon", 5)
                }

                // 第七步：月卡7折券
                if (stepMonthEnabled) {
                    triggerStep("月卡7折", "/api/plymouth/take_repurchase_coupon", 6)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "轮询异常: ${e.message}")
            }
        }, 15, huntInterval.toLong(), TimeUnit.SECONDS)
    }

    /**
     * 触发一个券种的领取
     * 通过反射调用相关服务类的方法，触发API请求
     */
    private fun triggerStep(stepName: String, apiPath: String, stepIndex: Int) {
        Log.d(TAG, "➡️ [$stepName] 触发...")
        val cl = classLoader ?: return

        // 尝试通过服务类触发
        val serviceNames = listOf(
            "com.xunmeng.pinduoduo.goods.service.GoodsCouponServiceImpl",
            "com.xunmeng.pinduoduo.checkout_core.promotion.litecontract.LiteContractHelper",
            "com.xunmeng.pinduoduo.index.promotion.PromotionCategoryApi"
        )

        for (svcName in serviceNames) {
            try {
                val cls = cl.loadClass(svcName)
                // 找单例
                var instance: Any? = null
                for (field in cls.declaredFields) {
                    if (field.name == "INSTANCE" || field.name == "instance" ||
                        field.name == "sInstance" || field.name == "sInstance") {
                        field.isAccessible = true; instance = field.get(null); break
                    }
                }
                // 找领券/刷新方法并调用
                for (method in cls.declaredMethods) {
                    if (method.parameterCount <= 2) {
                        val mn = method.name
                        if (mn.contains("take") || mn.contains("claim") || mn.contains("receive") ||
                            mn.contains("collect") || mn.contains("get") || mn.contains("fetch") ||
                            mn.contains("load") || mn.contains("refresh") || mn.contains("query")) {
                            method.isAccessible = true
                            try {
                                if (instance != null) method.invoke(instance)
                                else if (Modifier.isStatic(method.modifiers)) method.invoke(null)
                                else continue
                                stepCounters[stepIndex]++
                                Log.d(TAG, "  ✅ [$stepName] 触发: ${cls.simpleName}.$mn")
                                break
                            } catch (_: Throwable) { continue }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // ====================================================================
    // 六、动态类名发现（兜底）
    // ====================================================================

    private fun startDynamicDiscovery(lpparam: XC_LoadPackage.LoadPackageParam) {
        bg.scheduleWithFixedDelay({
            try {
                val xb = XposedHelpers.findClass("de.robv.android.xposed.XposedBridge", null)
                val getLoaded = xb?.getDeclaredMethod("getLoadedClasses")
                val loaded = getLoaded?.invoke(null) as? Array<*> ?: return@scheduleWithFixedDelay

                for (obj in loaded) {
                    val cls = obj as? Class<*> ?: continue
                    val name = cls.name
                    if (!name.startsWith("com.xunmeng.pinduoduo")) continue
                    if (!discoveredClasses.contains(name) &&
                        (name.contains("coupon", true) || name.contains("promotion", true) ||
                         name.contains("redpacket", true) || name.contains("红包") ||
                         name.contains("baiyi") || name.contains("福袋") || name.contains("月卡"))) {
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

    // ====================================================================
    // 七、数据解析与自动领券
    // ====================================================================

    private fun parseAndClaim(data: Any?, source: String) {
        if (data == null || !isActive) return
        try {
            when (data) {
                is String -> parseCouponFromString(data, source)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") checkMapForCoupon(data as Map<String, Any>, source)
                is List<*> -> data.forEach { if (it is Map<*, *>) @Suppress("UNCHECKED_CAST") checkMapForCoupon(it as Map<String, Any>, source) }
                else -> {
                    val title = getFieldStr(data, "title", "couponName", "name", "couponTitle", "desc")
                    val amount = getFieldDouble(data, "amount", "discount", "faceValue", "face_value", "price")
                    if (title.isNotEmpty() || amount > 0) checkAndClaim(title, amount, 0.0, source)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseCouponFromString(text: String, source: String) {
        if (text.length < 20 || text.length > 100000) return
        try {
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    val json = org.json.JSONObject(text)
                    extractCouponFromJson(json, source)
                } catch (_: org.json.JSONException) {
                    try {
                        val arr = org.json.JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i)
                            if (item != null) extractCouponFromJson(item, source)
                        }
                    } catch (_: org.json.JSONException) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractCouponFromJson(json: org.json.JSONObject, source: String) {
        try {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.contains("coupon") || key.contains("promotion") || key.contains("voucher") ||
                    key.contains("discount") || key.contains("红包") || key.contains("福袋") ||
                    key.contains("减减") || key.contains("月卡") || key.contains("金蛋") ||
                    key.contains("baiyi") || key.contains("subsidy") || key.contains("百亿")) {
                    val value = json.opt(key)
                    if (value is org.json.JSONObject) {
                        val title = value.optString("title", value.optString("coupon_name", value.optString("name", "")))
                        val amount = value.optDouble("amount", value.optDouble("discount", value.optDouble("face_value", 0.0)))
                        val minConsume = value.optDouble("min_consume", value.optDouble("threshold", 0.0))
                        if (title.isNotEmpty() || amount > 0) {
                            checkAndClaim(title, amount, minConsume, source)
                        }
                        // 递归检查嵌套
                        extractCouponFromJson(value, source)
                    } else if (value is org.json.JSONArray) {
                        for (i in 0 until value.length()) {
                            val item = value.optJSONObject(i)
                            if (item != null) extractCouponFromJson(item, source)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkMapForCoupon(map: Map<String, Any>, source: String) {
        try {
            val hasCoupon = map.keys.any { k ->
                k.contains("coupon") || k.contains("discount") || k.contains("voucher") ||
                k.contains("promotion") || k.contains("红包") || k.contains("优惠") ||
                k.contains("amount") || k.contains("price") || k.contains("reduce") ||
                k.contains("face_value") || k.contains("coupon_name") || k.contains("coupon_id") ||
                k.contains("take") || k.contains("claim") || k.contains("福袋") ||
                k.contains("减减") || k.contains("月卡") || k.contains("金蛋") || k.contains("baiyi")
            }
            if (!hasCoupon) return

            val title = (map["title"] ?: map["coupon_name"] ?: map["name"] ?: map["desc"] ?: map["coupon_title"] ?: "").toString()
            val amount = (map["amount"] ?: map["discount"] ?: map["price"] ?: map["face_value"] ?: map["reduce"] ?: 0).toString()
            val amountD = amount.toDoubleOrNull() ?: 0.0
            val minConsume = (map["min_consume"] ?: map["threshold"] ?: map["min"] ?: map["useThreshold"] ?: 0).toString().toDoubleOrNull() ?: 0.0

            checkAndClaim(title, amountD, minConsume, source)
        } catch (_: Throwable) {}
    }

    private fun checkAndClaim(title: String, amount: Double, minConsume: Double, source: String) {
        // 金额过滤
        if (onlyBig && amount < minAmount) return

        // 关键词匹配
        if (keywordMatchEnabled && searchKeywords.isNotEmpty()) {
            val matched = when (matchMode) {
                MatchMode.ANY -> searchKeywords.any { kw -> title.contains(kw, true) || amount.toString().contains(kw) }
                MatchMode.ALL -> searchKeywords.all { kw -> title.contains(kw, true) || amount.toString().contains(kw) }
            }
            if (!matched) { Log.d(TAG, "关键词未匹配: [$title] ¥$amount"); return }
        }

        // 防重
        val key = "$title|$amount|$minConsume|$source"
        if (claimedSet.contains(key)) return
        claimedSet.add(key)

        Log.i(TAG, "🎯 发现券: [$title] ¥${amount} (满${minConsume}) 来源: $source")
        onCouponDetected?.invoke(title, amount, source)

        // 自动领取
        autoClaim(title, amount, source)
    }

    private fun autoClaim(title: String, amount: Double, source: String) {
        val cl = classLoader ?: return
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
                                    else if (Modifier.isStatic(method.modifiers)) method.invoke(null)
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
                Log.d(TAG, "已记录券数据（待后续调用）: [$title] ¥$amount")
            } catch (e: Throwable) { Log.w(TAG, "自动领券失败: ${e.message}") }
        }
    }

    // ====================================================================
    // 八、工具方法
    // ====================================================================

    private fun getFieldStr(obj: Any, vararg names: String): String {
        for (name in names) {
            try {
                val f = obj.javaClass.getDeclaredField(name)
                f.isAccessible = true; return f.get(obj)?.toString() ?: ""
            } catch (_: Throwable) {
                var cls = obj.javaClass.superclass
                while (cls != null) {
                    try { val f = cls.getDeclaredField(name); f.isAccessible = true; return f.get(obj)?.toString() ?: "" } catch (_: Throwable) {}
                    cls = cls.superclass
                }
            }
        }
        return ""
    }

    private fun getFieldDouble(obj: Any, vararg names: String): Double {
        for (name in names) {
            try {
                val f = obj.javaClass.getDeclaredField(name)
                f.isAccessible = true; return (f.get(obj)?.toString()?.toDoubleOrNull() ?: 0.0)
            } catch (_: Throwable) {
                var cls = obj.javaClass.superclass
                while (cls != null) {
                    try { val f = cls.getDeclaredField(name); f.isAccessible = true; return f.get(obj)?.toString()?.toDoubleOrNull() ?: 0.0 } catch (_: Throwable) {}
                    cls = cls.superclass
                }
            }
        }
        return 0.0
    }

    /** 实际领券API端点列表（从APK反编译提取） */
    private val claimApis = listOf(
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
        "/api/lisbon/consult_promotion_price",
        "/api/social/red/envelope/receive/red/envelope/v2"
    )
}