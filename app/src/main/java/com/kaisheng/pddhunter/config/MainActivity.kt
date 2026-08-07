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
import com.kaisheng.pddhunter.hooks.SilentCouponHunter

/**
 * 主界面 — 配置 + 统计
 *
 * 纯静默模式，不需要无障碍服务！
 * 只需要在 LSPosed 中激活模块并勾选拼多多即可
 */
class MainActivity : AppCompatActivity() {

    private lateinit var todayClaimed: TextView
    private lateinit var totalClaimed: TextView
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
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_simple)
        ConfigManager.init(this)

        bindViews()
        setupListeners()
        refreshStats()

        // 监听静默猎人的统计变化
        SilentCouponHunter.onStatsChanged = { runOnUiThread { refreshStats() } }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        refreshStats()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun bindViews() {
        todayClaimed = findViewById(R.id.todayClaimed)
        totalClaimed = findViewById(R.id.totalClaimed)
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
        // 自动搞卷开关
        autoHuntSwitch.isChecked = SilentCouponHunter.isActive
        autoHuntSwitch.setOnCheckedChangeListener { _, isChecked ->
            SilentCouponHunter.isActive = isChecked
            refreshStats()
        }

        // 悬浮窗开关
        floatingSwitch.isChecked = ConfigManager.floatingEnabled
        floatingSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.floatingEnabled = isChecked
            if (isChecked) {
                if (checkOverlayPermission()) startFloating()
                else requestOverlayPermission()
            } else {
                stopService(Intent(this, FloatingPanelService::class.java))
            }
        }

        // 仅大额券
        onlyBigSwitch.isChecked = SilentCouponHunter.onlyBig
        onlyBigSwitch.setOnCheckedChangeListener { _, isChecked ->
            SilentCouponHunter.onlyBig = isChecked
        }

        // 检测间隔
        val interval = SilentCouponHunter.huntInterval / 10
        intervalSeekBar.progress = interval.coerceIn(1, 30) - 1
        intervalValue.text = "${SilentCouponHunter.huntInterval}秒"
        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val sec = (p + 1) * 10
                SilentCouponHunter.huntInterval = sec
                intervalValue.text = "${sec}秒"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 最小金额
        minAmountSeekBar.progress = (SilentCouponHunter.minAmount * 2).toInt().coerceIn(0, 100)
        minAmountValue.text = "${String.format("%.1f", SilentCouponHunter.minAmount)}元"
        minAmountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val amt = p / 2.0
                SilentCouponHunter.minAmount = amt
                minAmountValue.text = "${String.format("%.1f", amt)}元"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // LSPosed 指引
        lsposedGuide.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Throwable) {}
        }
    }

    private fun refreshStats() {
        todayClaimed.text = "${SilentCouponHunter.totalClaimed}"
        totalClaimed.text = "${SilentCouponHunter.totalClaimed}"

        statusText.text = if (SilentCouponHunter.isActive)
            "🟢 运行中 (${SilentCouponHunter.huntInterval}s轮询)"
        else
            "🔴 已暂停"

        // 最近记录
        val history = SilentCouponHunter.claimHistory.takeLast(20)
        historyList.text = if (history.isEmpty()) "暂无领取记录\n\n模块注入后，打开拼多多即可自动后台静默领取"
        else history.joinToString("\n")
    }

    private fun checkOverlayPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun startFloating() {
        if (checkOverlayPermission()) {
            val i = Intent(this, FloatingPanelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }
    }
}