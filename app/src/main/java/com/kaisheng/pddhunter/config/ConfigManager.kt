package com.kaisheng.pddhunter.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * 配置管理
 */
object ConfigManager {
    private const val PREFS_NAME = "pdd_hunter_config"
    private const val KEY_AUTO_HUNT_ENABLED = "auto_hunt_enabled"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_HUNT_INTERVAL = "hunt_interval"
    private const val KEY_MIN_COUPON_AMOUNT = "min_coupon_amount"
    private const val KEY_ONLY_BIG_COUPON = "only_big_coupon"
    private const val KEY_CLAIM_ALL_TYPES = "claim_all_types"
    private const val KEY_TODAY_CLAIMED_COUNT = "today_claimed_count"
    private const val KEY_TODAY_CLAIMED_DATE = "today_claimed_date"
    private const val KEY_TOTAL_CLAIMED = "total_claimed"
    private const val KEY_NOTIFY_ON_CLAIM = "notify_on_claim"
    private const val KEY_AUTO_OPEN_PDD = "auto_open_pdd"
    private const val KEY_LAST_VERSION = "last_version"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun get(): SharedPreferences =
        prefs ?: throw IllegalStateException("ConfigManager not initialized")

    // 自动搞卷开关
    var autoHuntEnabled: Boolean
        get() = get().getBoolean(KEY_AUTO_HUNT_ENABLED, true)
        set(v) = get().edit().putBoolean(KEY_AUTO_HUNT_ENABLED, v).apply()

    // 悬浮窗开关
    var floatingEnabled: Boolean
        get() = get().getBoolean(KEY_FLOATING_ENABLED, true)
        set(v) = get().edit().putBoolean(KEY_FLOATING_ENABLED, v).apply()

    // 检测间隔（秒）
    var huntInterval: Int
        get() = get().getInt(KEY_HUNT_INTERVAL, 30)
        set(v) = get().edit().putInt(KEY_HUNT_INTERVAL, v.coerceIn(10, 300)).apply()

    // 最小券面额
    var minCouponAmount: Double
        get() = get().getString(KEY_MIN_COUPON_AMOUNT, "0.0")?.toDoubleOrNull() ?: 0.0
        set(v) = get().edit().putString(KEY_MIN_COUPON_AMOUNT, v.toString()).apply()

    // 仅领取大额券
    var onlyBigCoupon: Boolean
        get() = get().getBoolean(KEY_ONLY_BIG_COUPON, false)
        set(v) = get().edit().putBoolean(KEY_ONLY_BIG_COUPON, v).apply()

    // 领所有类型
    var claimAllTypes: Boolean
        get() = get().getBoolean(KEY_CLAIM_ALL_TYPES, true)
        set(v) = get().edit().putBoolean(KEY_CLAIM_ALL_TYPES, v).apply()

    // 今日已领数
    var todayClaimedCount: Int
        get() {
            resetDailyIfNeeded()
            return get().getInt(KEY_TODAY_CLAIMED_COUNT, 0)
        }
        set(v) = get().edit().putInt(KEY_TODAY_CLAIMED_COUNT, v).apply()

    // 累计领取数
    var totalClaimed: Int
        get() = get().getInt(KEY_TOTAL_CLAIMED, 0)
        set(v) = get().edit().putInt(KEY_TOTAL_CLAIMED, v).apply()

    // 通知开关
    var notifyOnClaim: Boolean
        get() = get().getBoolean(KEY_NOTIFY_ON_CLAIM, true)
        set(v) = get().edit().putBoolean(KEY_NOTIFY_ON_CLAIM, v).apply()

    // 自动打开拼多多
    var autoOpenPdd: Boolean
        get() = get().getBoolean(KEY_AUTO_OPEN_PDD, true)
        set(v) = get().edit().putBoolean(KEY_AUTO_OPEN_PDD, v).apply()

    // 增加领取计数
    fun incrementClaimed() {
        todayClaimedCount = todayClaimedCount + 1
        totalClaimed = totalClaimed + 1
    }

    // 重置每日计数
    private fun resetDailyIfNeeded() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val savedDate = get().getString(KEY_TODAY_CLAIMED_DATE, "")
        if (savedDate != today) {
            get().edit()
                .putInt(KEY_TODAY_CLAIMED_COUNT, 0)
                .putString(KEY_TODAY_CLAIMED_DATE, today)
                .apply()
        }
    }

    // 检查是否首次运行
    fun isFirstRun(): Boolean {
        val currentVersion = BuildConfig.VERSION_CODE
        val lastVersion = get().getInt(KEY_LAST_VERSION, 0)
        return lastVersion < currentVersion
    }

    fun markNotFirstRun() {
        get().edit().putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE).apply()
    }
}

// 占位，实际由BuildConfig提供
object BuildConfig {
    const val VERSION_CODE = 1
}