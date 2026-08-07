package com.kaisheng.pddhunter.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 统计存储 — 用 SharedPreferences 解耦 UI 和 Xposed hook
 *
 * 关键：UI 层（MainActivity/FloatingPanelService）只访问这个类，
 * 完全不依赖 SilentCouponHunter（Xposed hook 类）。
 * 这样主界面即使在没有 LSPosed 的环境下也能正常打开，不会闪退。
 */
object StatsStore {
    private const val PREFS = "pdd_hunter_stats"
    private const val KEY_TOTAL = "total_claimed"
    private const val KEY_LAST_TIME = "last_claim_time"
    private const val KEY_HISTORY = "claim_history"
    private const val KEY_ACTIVE = "is_active"
    private const val KEY_INTERVAL = "hunt_interval"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun get(): SharedPreferences =
        prefs ?: throw IllegalStateException("StatsStore not initialized")

    // ===== 统计 =====
    var totalClaimed: Int
        get() = get().getInt(KEY_TOTAL, 0)
        set(v) = get().edit().putInt(KEY_TOTAL, v).apply()

    var lastClaimTime: Long
        get() = get().getLong(KEY_LAST_TIME, 0L)
        set(v) = get().edit().putLong(KEY_LAST_TIME, v).apply()

    /** 历史记录（逗号分隔，最多保存50条） */
    var history: List<String>
        get() = get().getString(KEY_HISTORY, "")?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) {
            val joined = v.takeLast(50).joinToString("\n")
            get().edit().putString(KEY_HISTORY, joined).apply()
        }

    // ===== 配置镜像（供UI读取，hook实际运行时用内存） =====
    var isActive: Boolean
        get() = get().getBoolean(KEY_ACTIVE, true)
        set(v) = get().edit().putBoolean(KEY_ACTIVE, v).apply()

    var huntInterval: Int
        get() = get().getInt(KEY_INTERVAL, 60)
        set(v) = get().edit().putInt(KEY_INTERVAL, v).apply()

    // ===== 便捷方法 =====
    fun addHistory(entry: String) {
        val list = history.toMutableList()
        list.add(entry)
        history = list
    }

    fun incrementClaimed() {
        totalClaimed = totalClaimed + 1
        lastClaimTime = System.currentTimeMillis()
    }
}