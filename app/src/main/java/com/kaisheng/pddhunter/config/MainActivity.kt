package com.kaisheng.pddhunter.config

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.kaisheng.pddhunter.R
import com.kaisheng.pddhunter.floating.FloatingPanelService

/**
 * 主界面 — 配置 + 统计
 * 只依赖 StatsStore，完全不引用 Xposed hook 类，杜绝闪退
 */
class MainActivity : AppCompatActivity() {
    private lateinit var todayClaimed: TextView
    private lateinit var statusText: TextView
    private lateinit var autoHuntSwitch: SwitchCompat
    private lateinit var floatingSwitch: SwitchCompat
    private lateinit var onlyBigSwitch: SwitchCompat
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var intervalValue: TextView
    private lateinit var minAmountSeekBar: SeekBar
    private lateinit var minAmountValue: TextView
    private lateinit var historyList: TextView
    private lateinit var lsposedGuide: TextView

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() { refreshStats(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_simple)
        StatsStore.init(this)
        bindViews()
        setupListeners()
        refreshStats()
    }

    override fun onResume() { super.onResume(); handler.post(refreshRunnable); refreshStats() }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refreshRunnable) }

    private fun bindViews() {
        todayClaimed = findViewById(R.id.todayClaimed)
        statusText = findViewById(R.id.statusText)
        autoHuntSwitch = findViewById(R.id.autoHuntSwitch)
        floatingSwitch = findViewById(R.id.floatingSwitch)
        onlyBigSwitch = findViewById(R.id.onlyBigSwitch)
        intervalSeekBar = findViewById(R.id.intervalSeekBar)
        intervalValue = findViewById(R.id.intervalValue)
        minAmountSeekBar = findViewById(R.id.minAmountSeekBar)
        minAmountValue = findViewById(R.id.minAmountValue)
        historyList = findViewById(R.id.historyList)
        lsposedGuide = findViewById(R.id.lsposedGuide)
    }

    private fun setupListeners() {
        autoHuntSwitch.isChecked = StatsStore.isActive
        autoHuntSwitch.setOnCheckedChangeListener { _, isChecked ->
            StatsStore.isActive = isChecked; refreshStats()
        }
        floatingSwitch.isChecked = ConfigManager.floatingEnabled
        floatingSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.floatingEnabled = isChecked
            if (isChecked) { if (checkOverlayPermission()) startFloating(); else requestOverlayPermission() }
            else { stopService(Intent(this, FloatingPanelService::class.java)) }
        }
        onlyBigSwitch.isChecked = ConfigManager.onlyBigCoupon
        onlyBigSwitch.setOnCheckedChangeListener { _, isChecked -> ConfigManager.onlyBigCoupon = isChecked }
        intervalSeekBar.progress = (StatsStore.huntInterval / 10).coerceIn(1, 30) - 1
        intervalValue.text = "${StatsStore.huntInterval}秒"
        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                StatsStore.huntInterval = (p + 1) * 10; intervalValue.text = "${StatsStore.huntInterval}秒"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        minAmountSeekBar.progress = (ConfigManager.minCouponAmount * 2).toInt().coerceIn(0, 100)
        minAmountValue.text = "${String.format("%.1f", ConfigManager.minCouponAmount)}元"
        minAmountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                ConfigManager.minCouponAmount = p / 2.0; minAmountValue.text = "${String.format("%.1f", p / 2.0)}元"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        lsposedGuide.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) } catch (_: Throwable) {}
        }
    }

    private fun refreshStats() {
        todayClaimed.text = "${StatsStore.totalClaimed}"
        statusText.text = if (StatsStore.isActive) "🟢 运行中 (${StatsStore.huntInterval}s轮询)" else "🔴 已暂停"
        val history = StatsStore.history.takeLast(20)
        historyList.text = if (history.isEmpty()) "暂无领取记录\n\n模块注入后，打开拼多多即可自动后台静默领取" else history.joinToString("\n")
    }

    private fun checkOverlayPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    private fun requestOverlayPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    private fun startFloating() {
        if (checkOverlayPermission()) {
            val i = Intent(this, FloatingPanelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }
    }
}