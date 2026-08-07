package com.kaisheng.pddhunter.floating

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaisheng.pddhunter.R
import com.kaisheng.pddhunter.config.StatsStore
import java.text.SimpleDateFormat
import java.util.*

/**
 * 悬浮窗面板 — 纯展示，不操作界面
 *
 * 显示内容：
 * 1. 今日已领 / 累计搞券 统计
 * 2. 最近领取记录列表
 * 3. 当前状态（运行中/暂停）
 * 4. 快捷开关（暂停/恢复）
 *
 * 完全不涉及无障碍操作，你刷抖音完全不受影响
 */
class FloatingPanelService : Service() {

    companion object {
        private const val TAG = "FloatingPanel"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pdd_hunter_panel"

        var isShowing = false
            private set
        var instance: FloatingPanelService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var panelView: ViewGroup? = null
    private var isExpanded = false
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.kaisheng.pddhunter.config.StatsStore.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        Log.i(TAG, "悬浮面板服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (panelView == null) createPanel()
        if (panelView != null && !isShowing) showPanel()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        isShowing = false
        try { panelView?.let { windowManager?.removeView(it) } } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ========== 创建悬浮面板 ==========

    private fun createPanel() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        panelView = inflater.inflate(R.layout.floating_panel, null) as ViewGroup

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        val dm = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(dm)
        params.x = dm.widthPixels - 220
        params.y = dm.heightPixels / 3
        panelView?.tag = params

        setupEvents()
        refreshDisplay()
    }

    private fun setupEvents() {
        // 拖动 / 点击展开收起
        panelView?.setOnTouchListener { _, event ->
            val p = panelView?.tag as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = p.x; initialY = p.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                        p.x = initialX + dx; p.y = initialY + dy
                        windowManager?.updateViewLayout(panelView, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) togglePanel()
                    true
                }
                else -> false
            }
        }

        // 关闭按钮
        panelView?.findViewById<View>(R.id.closeBtn)?.setOnClickListener {
            collapsePanel()
        }

        // 暂停/恢复按钮
        panelView?.findViewById<View>(R.id.toggleBtn)?.setOnClickListener {
            StatsStore.isActive = !StatsStore.isActive
            refreshDisplay()
            Toast.makeText(this,
                if (StatsStore.isActive) "✅ 自动搞卷已恢复" else "⏸ 自动搞卷已暂停",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPanel() {
        try {
            val p = panelView?.tag as? WindowManager.LayoutParams ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            windowManager?.addView(panelView, p)
            isShowing = true
            Log.i(TAG, "悬浮面板已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示面板失败: ${e.message}")
        }
    }

    // ========== 面板展开/收起 ==========

    private fun togglePanel() {
        val content = panelView?.findViewById<View>(R.id.panelContent)
        if (content == null) return
        isExpanded = !isExpanded
        content.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) refreshDisplay()
    }

    private fun collapsePanel() {
        panelView?.findViewById<View>(R.id.panelContent)?.visibility = View.GONE
        isExpanded = false
    }

    // ========== 刷新显示 ==========

    fun refreshDisplay() {
        if (!isExpanded) return
        try {
            val stats = com.kaisheng.pddhunter.config.StatsStore
            // 统计
            panelView?.findViewById<TextView>(R.id.todayCount)?.text =
                "${stats.totalClaimed}"
            panelView?.findViewById<TextView>(R.id.totalCount)?.text =
                "${stats.totalClaimed}"

            // 状态
            val statusView = panelView?.findViewById<TextView>(R.id.statusText)
            val toggleBtn = panelView?.findViewById<TextView>(R.id.toggleBtn)
            if (stats.isActive) {
                statusView?.text = "🟢 运行中 (${stats.huntInterval}s轮询)"
                statusView?.setTextColor(Color.parseColor("#4CAF50"))
                toggleBtn?.text = "⏸ 暂停"
                toggleBtn?.setBackgroundColor(Color.parseColor("#FF5722"))
            } else {
                statusView?.text = "🔴 已暂停"
                statusView?.setTextColor(Color.parseColor("#F44336"))
                toggleBtn?.text = "▶ 恢复"
                toggleBtn?.setBackgroundColor(Color.parseColor("#4CAF50"))
            }

            // 最近领取记录
            val listView = panelView?.findViewById<RecyclerView>(R.id.historyList)
            val history = stats.history.takeLast(50).reversed()
            listView?.layoutManager = LinearLayoutManager(this)
            listView?.adapter = HistoryAdapter(history)
        } catch (_: Throwable) {}
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "券猎人面板",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "悬浮面板后台服务"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage("com.kaisheng.pddhunter"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("拼多多券猎人")
            .setContentText("已自动领 ${StatsStore.totalClaimed} 张券")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

/**
 * 历史记录适配器
 */
class HistoryAdapter(private val items: List<String>) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {
    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_1, p, false)
    )
    override fun onBindViewHolder(h: VH, i: Int) {
        h.tv.text = items[i]
        h.tv.setTextSize(12f)
        h.tv.setPadding(8, 4, 8, 4)
    }
    override fun getItemCount() = items.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(android.R.id.text1)
    }
}