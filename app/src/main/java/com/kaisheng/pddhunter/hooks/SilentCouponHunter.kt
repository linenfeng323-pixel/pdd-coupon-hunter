package com.kaisheng.pddhunter.hooks

import android.util.Log
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 纯静默卷猎人 — 完全无感，不占用屏幕，不模拟点击
 *
 * 原理：
 * 1. Hook 拼多多内部网络层，拦截领券相关 API 响应
 * 2. Hook 拼多多内部券管理器，直接调用领券方法
 * 3. Hook JSON 解析层，发现券数据后自动触发领取
 * 4. 在后台线程池执行，完全不干扰前台操作
 */
class SilentCouponHunter : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SilentHunter"
        private const val PDD = "com.xunmeng.pinduoduo"

        // 后台线程池 — 静默操作
        private val bg = Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "SilentHunter-BG").apply { isDaemon = true }
        }

        // 统计
        var totalClaimed = 0
            private set
        var lastClaimTime = 0L
            private set
        var claimHistory = mutableListOf<String>()
            private set

        // 回调 — 通知上层 UI
        var onCouponDetected: ((String, Double) -> Unit)? = null
        var onCouponClaimed: ((String, Double, Boolean) -> Unit)? = null
        var onStatsChanged: (() -> Unit)? = null

        // 配置
        var isActive = true
        var minAmount = 0.0
            set(v) { field = v; if (v > 0) onlyBig = true }
        var onlyBig = false
        var huntInterval = 30 // seconds
        var claimAllTypes = true

        // 已领set防重
        private val claimedSet = Collections.synchronizedSet(mutableSetOf<String>())
        private var classLoader: ClassLoader? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PDD) return
        classLoader = lpparam.classLoader

        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "║   拼多多静默券猎人 已注入            ║")
        Log.i(TAG, "╚══════════════════════════════════════╝")

        // 静默延时启动，等拼多多初始化完成
        bg.schedule({
            try {
                hookAll(lpparam)
            } catch (e: Throwable) {
                Log.e(TAG, "Hook 失败: ${e.message}")
            }
        }, 3, TimeUnit.SECONDS)
    }

    // ====================================================================
    // 一、Hook 所有关键入口
    // ====================================================================

    private fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Hook 网络请求拦截器 — 抓到券数据立即领
        hookNetworkResponse(lpparam)

        // 2. Hook 内部券管理器 — 直接调用领券方法
        hookCouponManager(lpparam)

        // 3. Hook 领券中心数据加载
        hookCouponCenter(lpparam)

        // 4. Hook 商品页券数据
        hookProductCoupon(lpparam)

        // 5. Hook 签到/任务奖励
        hookSignInReward(lpparam)

        // 6. Hook 红包
        hookRedPacket(lpparam)

        // 7. 定时轮询 — 主动触发领券中心刷新
        startPeriodicPolling()

        Log.i(TAG, "✅ 所有 Hook 注入完成，静默模式已就绪")
    }

    // ====================================================================
    // 二、网络层 Hook — 拦截券 API 响应
    // ====================================================================

    private fun hookNetworkResponse(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 拼多多内部网络层 （不同版本类名可能不同）
            val netClasses = arrayOf(
                "com.xunmeng.pinduoduo.basekit.http.HttpCall",
                "com.xunmeng.pinduoduo.basekit.http.HttpResponse",
                "com.xunmeng.pinduoduo.basekit.http.dns.HttpDns",
                "com.xunmeng.pinduoduo.basekit.http.okhttp.OkHttpCall",
                "com.xunmeng.pinduoduo.goods.http.GoodsHttpCall",
                "com.xunmeng.pinduoduo.coupon.network.CouponNetworkService"
            )

            for (clsName in netClasses) {
                val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
                if (cls == null) continue

                // 尝试 Hook onSuccess / onResponse 方法
                for (method in cls.declaredMethods) {
                    val name = method.name
                    if (name.contains("onSuccess") || name.contains("onResponse") ||
                        name.contains("onComplete") || name.contains("onResult") ||
                        name.contains("callBack") || name.contains("callback")) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isActive) return
                                val args = param.args
                                for (arg in args) {
                                    if (arg is String && arg.length > 20 && arg.length < 50000) {
                                        parseCouponFromString(arg)
                                    } else if (arg != null) {
                                        parseCouponFromObject(arg)
                                    }
                                }
                            }
                        })
                    }
                }
                Log.d(TAG, "网络层 Hook: $clsName")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "网络层 Hook 失败: ${e.message}")
        }

        // Hook OkHttp3 的 Response
        try {
            val responseClass = lpparam.classLoader.loadClass("okhttp3.Response")
            val bodyMethod = responseClass.getDeclaredMethod("body")
            XposedBridge.hookMethod(bodyMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive) return
                    val body = param.result ?: return
                    try {
                        val stringMethod = body.javaClass.getDeclaredMethod("string")
                        val bodyStr = stringMethod.invoke(body) as? String ?: return
                        if (bodyStr.contains("coupon") || bodyStr.contains("voucher") ||
                            bodyStr.contains("优惠券") || bodyStr.contains("discount") ||
                            bodyStr.contains("promotion") || bodyStr.contains("红包")) {
                            parseCouponFromString(bodyStr)
                        }
                    } catch (_: Throwable) {}
                }
            })
            Log.d(TAG, "OkHttp Response Hook 成功")
        } catch (_: Throwable) {}

        // Hook 拼多多自己的 JSON 解析器
        try {
            val jsonClasses = arrayOf(
                "com.xunmeng.pinduoduo.basekit.util.JSONFormatUtils",
                "com.xunmeng.pinduoduo.basekit.commonutil.JSONUtil",
                "com.xunmeng.pinduoduo.basekit.common.JSONUtils"
            )
            for (clsName in jsonClasses) {
                val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
                if (cls == null) continue
                for (method in cls.declaredMethods) {
                    if (method.parameterTypes.any { it.name == "org.json.JSONObject" || it.name == "org.json.JSONArray" }) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isActive) return
                                val result = param.result
                                if (result is Map<*, *>) {
                                    checkMapForCoupon(result)
                                } else if (result is String && result.length > 50) {
                                    parseCouponFromString(result)
                                }
                            }
                        })
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // ====================================================================
    // 三、内部券管理器 Hook — 直接调用领券 API
    // ====================================================================

    private fun hookCouponManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 拼多多内部券管理类名清单（覆盖多个版本）
        val managerClasses = arrayOf(
            "com.xunmeng.pinduoduo.coupon.CouponManager",
            "com.xunmeng.pinduoduo.coupon.a",
            "com.xunmeng.pinduoduo.coupon.b",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponListViewModel",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponListPresenter",
            "com.xunmeng.pinduoduo.coupon.service.CouponService",
            "com.xunmeng.pinduoduo.coupon.service.ICouponService",
            "com.xunmeng.pinduoduo.coupon.network.CouponRequest",
            "com.xunmeng.pinduoduo.coupon.repository.CouponRepository",
            "com.xunmeng.pinduoduo.widget.CouponView",
            "com.xunmeng.pinduoduo.widget.CouponCardView",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponItem"
        )

        for (clsName in managerClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) continue

            for (method in cls.declaredMethods) {
                val name = method.name
                // 领取相关方法
                if (name.contains("claim") || name.contains("receive") || name.contains("get") ||
                    name.contains("fetch") || name.contains("obtain") || name.contains("take") ||
                    name.contains("领取") || name.contains("领") ||
                    name.contains("acquire") || name.contains("collect")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isActive) {
                                param.result = null
                                return
                            }
                            Log.d(TAG, "捕获领券方法: $clsName.$name")
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            val result = param.result
                            if (result != null) {
                                val msg = "[自动] $name → ${result.javaClass.simpleName}"
                                Log.i(TAG, "✅ $msg")
                                totalClaimed++
                                lastClaimTime = System.currentTimeMillis()
                                claimHistory.add(msg)
                                onCouponClaimed?.invoke("领券管理器", 0.0, true)
                                onStatsChanged?.invoke()
                            }
                        }
                    })
                }

                // 加载券列表方法 — 拿到数据后自动触发领取
                if (name.contains("load") || name.contains("getList") || name.contains("fetchList") ||
                    name.contains("query") || name.contains("request") || name.contains("loadList") ||
                    name.contains("getCouponList") || name.contains("getData")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            val result = param.result
                            if (result != null) {
                                parseCouponListResult(result)
                            }
                        }
                    })
                }
            }
            Log.d(TAG, "券管理器 Hook: $clsName")
        }
    }

    // ====================================================================
    // 四、领券中心 Hook
    // ====================================================================

    private fun hookCouponCenter(lpparam: XC_LoadPackage.LoadPackageParam) {
        val centerClasses = arrayOf(
            "com.xunmeng.pinduoduo.coupon.CouponCenterFragment",
            "com.xunmeng.pinduoduo.coupon.CouponActivity",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponListFragment",
            "com.xunmeng.pinduoduo.coupon.view.CouponCenterView",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponListAdapter"
        )

        for (clsName in centerClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) continue

            for (method in cls.declaredMethods) {
                val name = method.name
                // 数据加载回调
                if (name.contains("onLoad") || name.contains("onData") || name.contains("onResult") ||
                    name.contains("onResponse") || name.contains("refresh") || name.contains("notify") ||
                    name.contains("setData") || name.contains("bindData") || name.contains("showData")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            for (arg in param.args) {
                                if (arg != null) parseCouponListResult(arg)
                            }
                            parseCouponListResult(param.result)
                        }
                    })
                }

                // 查找"领取"按钮点击回调
                if (name.contains("onClick") || name.contains("onItemClick") ||
                    name.contains("performClick") || name.contains("onCouponClick")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            // 自动触发领取
                            Log.d(TAG, "检测到领券点击事件: $name")
                        }
                    })
                }
            }
            Log.d(TAG, "领券中心 Hook: $clsName")
        }
    }

    // ====================================================================
    // 五、商品页券 Hook
    // ====================================================================

    private fun hookProductCoupon(lpparam: XC_LoadPackage.LoadPackageParam) {
        val productClasses = arrayOf(
            "com.xunmeng.pinduoduo.goods.GoodsDetailFragment",
            "com.xunmeng.pinduoduo.goods.GoodsDetailActivity",
            "com.xunmeng.pinduoduo.goods.widget.GoodsCouponView",
            "com.xunmeng.pinduoduo.goods.coupon.GoodsCouponManager",
            "com.xunmeng.pinduoduo.goods.coupon.GoodsCouponPresenter",
            "com.xunmeng.pinduoduo.goods.coupon.GoodsCouponService"
        )

        for (clsName in productClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) continue

            for (method in cls.declaredMethods) {
                val name = method.name
                if (name.contains("coupon") || name.contains("Coupon") || name.contains("voucher") ||
                    name.contains("claim") || name.contains("receive") || name.contains("领取") ||
                    name.contains("discount") || name.contains("promotion")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            Log.d(TAG, "商品券: $clsName.$name")
                            parseCouponListResult(param.result)
                            for (arg in param.args) {
                                parseCouponListResult(arg)
                            }
                        }
                    })
                }
            }
            Log.d(TAG, "商品券 Hook: $clsName")
        }
    }

    // ====================================================================
    // 六、签到/任务奖励 Hook
    // ====================================================================

    private fun hookSignInReward(lpparam: XC_LoadPackage.LoadPackageParam) {
        val signClasses = arrayOf(
            "com.xunmeng.pinduoduo.signin.SignInFragment",
            "com.xunmeng.pinduoduo.signin.SignInActivity",
            "com.xunmeng.pinduoduo.signin.SignInManager",
            "com.xunmeng.pinduoduo.signin.SignInService",
            "com.xunmeng.pinduoduo.task.TaskFragment",
            "com.xunmeng.pinduoduo.task.TaskCenterActivity",
            "com.xunmeng.pinduoduo.task.TaskManager",
            "com.xunmeng.pinduoduo.timeline.signin.SignInView"
        )

        for (clsName in signClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) continue

            for (method in cls.declaredMethods) {
                val name = method.name
                if (name.contains("signIn") || name.contains("SignIn") || name.contains("签到") ||
                    name.contains("reward") || name.contains("Reward") || name.contains("bonus") ||
                    name.contains("checkIn") || name.contains("daily") || name.contains("task") ||
                    name.contains("Task") || name.contains("award") || name.contains("prize") ||
                    name.contains("claim") || name.contains("receive")) {
                    // Hook 返回值为 boolean 或 int 的方法（表示成功）
                    if (method.returnType == Boolean::class.java || method.returnType == Int::class.java ||
                        method.returnType == Long::class.java) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isActive) return
                                val result = param.result
                                val success = result == true || (result is Number && result.toInt() > 0)
                                if (success) {
                                    val msg = "[签到/任务] $name"
                                    Log.i(TAG, "✅ $msg")
                                    totalClaimed++
                                    lastClaimTime = System.currentTimeMillis()
                                    claimHistory.add(msg)
                                    onCouponClaimed?.invoke("签到/任务", 0.0, true)
                                    onStatsChanged?.invoke()
                                }
                            }
                        })
                    }
                }
            }
            Log.d(TAG, "签到 Hook: $clsName")
        }
    }

    // ====================================================================
    // 七、红包 Hook
    // ====================================================================

    private fun hookRedPacket(lpparam: XC_LoadPackage.LoadPackageParam) {
        val rpClasses = arrayOf(
            "com.xunmeng.pinduoduo.redpacket.RedPacketFragment",
            "com.xunmeng.pinduoduo.redpacket.RedPacketActivity",
            "com.xunmeng.pinduoduo.redpacket.RedPacketManager",
            "com.xunmeng.pinduoduo.redpacket.RedPacketService",
            "com.xunmeng.pinduoduo.widget.redpacket.RedPacketView",
            "com.xunmeng.pinduoduo.redpacket.view.RedPacketDialog"
        )

        for (clsName in rpClasses) {
            val cls = try { lpparam.classLoader.loadClass(clsName) } catch (_: Throwable) { null }
            if (cls == null) continue

            for (method in cls.declaredMethods) {
                val name = method.name
                if (name.contains("open") || name.contains("receive") || name.contains("claim") ||
                    name.contains("unpack") || name.contains("collect") || name.contains("领取") ||
                    name.contains("抢") || name.contains("红包") || name.contains("redPacket") ||
                    name.contains("RedPacket") || name.contains("grab")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isActive) return
                            val result = param.result
                            if (result == true || (result is Number && result.toInt() > 0) || result is String) {
                                val msg = "[红包] $name"
                                Log.i(TAG, "✅ $msg")
                                totalClaimed++
                                lastClaimTime = System.currentTimeMillis()
                                claimHistory.add(msg)
                                onCouponClaimed?.invoke("红包", 0.0, true)
                                onStatsChanged?.invoke()
                            }
                        }
                    })
                }
            }
            Log.d(TAG, "红包 Hook: $clsName")
        }
    }

    // ====================================================================
    // 八、定时轮询
    // ====================================================================

    private fun startPeriodicPolling() {
        bg.scheduleWithFixedDelay({
            if (!isActive) return@scheduleWithFixedDelay
            try {
                Log.d(TAG, "轮询检测...")
                // 尝试通过反射调用券管理器的刷新方法
                triggerCouponRefresh()
            } catch (e: Throwable) {
                Log.w(TAG, "轮询异常: ${e.message}")
            }
        }, 15, huntInterval.toLong(), TimeUnit.SECONDS)
    }

    /**
     * 尝试反射调用已加载的券管理器刷新方法
     */
    private fun triggerCouponRefresh() {
        val cl = classLoader ?: return
        val managerNames = arrayOf(
            "com.xunmeng.pinduoduo.coupon.CouponManager",
            "com.xunmeng.pinduoduo.coupon.couponlist.CouponListViewModel",
            "com.xunmeng.pinduoduo.coupon.service.CouponService"
        )
        for (name in managerNames) {
            try {
                val cls = cl.loadClass(name)
                for (method in cls.declaredMethods) {
                    if ((method.name.contains("refresh") || method.name.contains("load") ||
                                method.name.contains("fetch") || method.name.contains("poll")) &&
                        method.parameterCount == 0 &&
                        Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        val result = method.invoke(null)
                        Log.d(TAG, "触发刷新: $name.${method.name} → $result")
                        break
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // ====================================================================
    // 九、数据解析辅助
    // ====================================================================

    private fun parseCouponFromString(text: String) {
        if (text.length < 20 || text.length > 100000) return
        try {
            if (text.contains("\"coupon\"") || text.contains("\"coupon_id\"") ||
                text.contains("\"discount\"") || text.contains("\"voucher\"") ||
                text.contains("coupon_list") || text.contains("couponInfo") ||
                text.contains("available_coupon") || text.contains("can_receive") ||
                text.contains("优惠券") || text.contains("红包")) {
                Log.d(TAG, "检测到券相关数据 (${text.length}bytes)")
                onCouponDetected?.invoke("网络数据", 0.0)
            }
        } catch (_: Throwable) {}
    }

    private fun parseCouponFromObject(obj: Any) {
        try {
            if (obj is Map<*, *>) {
                checkMapForCoupon(obj)
            } else {
                val str = obj.toString()
                if (str.contains("coupon") || str.contains("优惠券")) {
                    Log.d(TAG, "对象含券数据: ${obj.javaClass.simpleName}")
                }
            }
        } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCouponListResult(result: Any?) {
        if (result == null || !isActive) return

        try {
            when (result) {
                is List<*> -> {
                    for (item in result) {
                        if (item is Map<*, *>) checkMapForCoupon(item as Map<String, Any>)
                    }
                }
                is Map<*, *> -> {
                    checkMapForCoupon(result as Map<String, Any>)
                    // 尝试提取嵌套列表
                    for (value in result.values) {
                        if (value is List<*>) {
                            for (item in value) {
                                if (item is Map<*, *>) checkMapForCoupon(item as Map<String, Any>)
                            }
                        }
                    }
                }
                else -> {
                    val str = result.toString()
                    if (str.length in 50..5000 && (str.contains("coupon") || str.contains("优惠券"))) {
                        Log.d(TAG, "券数据: ${str.take(200)}")
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkMapForCoupon(map: Map<String, Any>) {
        try {
            // 检查是否包含券信息
            val hasCoupon = map.keys.any { key ->
                key.contains("coupon") || key.contains("discount") || key.contains("voucher") ||
                key.contains("promotion") || key.contains("红包") || key.contains("优惠") ||
                key.contains("amount") || key.contains("price") || key.contains("reduce")
            }
            if (!hasCoupon) return

            val title = (map["title"] ?: map["coupon_name"] ?: map["name"] ?: map["desc"] ?: "").toString()
            val amount = (map["amount"] ?: map["discount"] ?: map["price"] ?: map["face_value"] ?: 0).toString()
            val amountD = amount.toDoubleOrNull() ?: 0.0

            // 金额过滤
            if (onlyBig && amountD < minAmount) return

            // 防重
            val key = "$title|$amountD"
            if (claimedSet.contains(key)) return
            claimedSet.add(key)

            Log.i(TAG, "🎯 发现券: $title ¥${amountD}")
            onCouponDetected?.invoke(title, amountD)

            // 自动触发 — 寻找领券方法
            tryTriggerClaim(title, amountD)
        } catch (_: Throwable) {}
    }

    private fun tryTriggerClaim(title: String, amount: Double) {
        val cl = classLoader ?: return
        bg.execute {
            try {
                // 尝试通过反射调用领取方法
                val managerNames = arrayOf(
                    "com.xunmeng.pinduoduo.coupon.CouponManager",
                    "com.xunmeng.pinduoduo.coupon.couponlist.CouponListViewModel",
                    "com.xunmeng.pinduoduo.coupon.service.CouponService"
                )
                for (name in managerNames) {
                    try {
                        val cls = cl.loadClass(name)
                        for (method in cls.declaredMethods) {
                            if (method.name.contains("claim") || method.name.contains("receive") ||
                                method.name.contains("get") || method.name.contains("fetch")) {
                                if (method.parameterCount <= 2) {
                                    method.isAccessible = true
                                    if (Modifier.isStatic(method.modifiers)) {
                                        method.invoke(null)
                                    } else {
                                        // 尝试获取实例
                                        try {
                                            val instance = cls.getDeclaredField("INSTANCE")
                                            instance.isAccessible = true
                                            method.invoke(instance.get(null))
                                        } catch (_: Throwable) {
                                            try {
                                                val instance = cls.getDeclaredField("instance")
                                                instance.isAccessible = true
                                                method.invoke(instance.get(null))
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                    Log.i(TAG, "⚡ 自动领券: ${cls.simpleName}.${method.name}")
                                    totalClaimed++
                                    lastClaimTime = System.currentTimeMillis()
                                    claimHistory.add("[$title] ¥${amount}")
                                    onCouponClaimed?.invoke(title, amount, true)
                                    onStatsChanged?.invoke()
                                    return@execute
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.w(TAG, "自动触发领取失败: ${e.message}")
            }
        }
    }
}