package com.adbbutton.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingButtonService : Service() {

    companion object {
        const val ACTION_START = "com.adbbutton.app.ACTION_START"
        const val ACTION_STOP = "com.adbbutton.app.ACTION_STOP"
        const val PREF_NAME = "FloatingButtonPrefs"
        const val PREF_X = "button_x"
        const val PREF_Y = "button_y"
        const val PREF_SIZE = "button_size"
        const val PREF_TAP_INTERVAL = "tap_interval"
        const val CHANNEL_ID = "floating_button_channel"
        const val NOTIF_ID = 1001

        private const val DEFAULT_SIZE_DP = 64
        private const val DEFAULT_TAP_INTERVAL_MS = 100L

        // FIX 3: 300ms → 150ms — butona basıldığı an hızlıca tıklamaya başlar
        private const val LONG_PRESS_THRESHOLD_MS = 150L

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var tapRunnable: Runnable? = null
    private var isTapping = false

    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isDragging = false
    private var longPressTriggered = false

    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        showFloatingButton()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopTapping()
        if (::floatingView.isInitialized) {
            runCatching { windowManager.removeView(floatingView) }
        }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showFloatingButton() {
        val sizeDp = prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP)
        val sizePx = dpToPx(sizeDp)
        val savedX = prefs.getInt(PREF_X, 100)
        val savedY = prefs.getInt(PREF_Y, 300)

        floatingView = createButtonView(sizePx)

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false
                    longPressTriggered = false

                    handler.postDelayed({
                        if (!isDragging) {
                            longPressTriggered = true
                            startTapping()
                        }
                    }, LONG_PRESS_THRESHOLD_MS)

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY

                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacksAndMessages(null)
                        if (isTapping) stopTapping()
                    }

                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(floatingView, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // FIX 1 (parmak kaldırınca tap): WifiAdbHelper.tapping'i ÖNCE false yap
                    // böylece executor'daki bekleyen taplar anında iptal olur
                    WifiAdbHelper.tapping = false

                    handler.removeCallbacksAndMessages(null)

                    if (isTapping) {
                        stopTapping()
                    }

                    if (isDragging) {
                        prefs.edit()
                            .putInt(PREF_X, params.x)
                            .putInt(PREF_Y, params.y)
                            .apply()
                    }

                    true
                }

                else -> false
            }
        }

        runCatching { windowManager.addView(floatingView, params) }
    }

    private fun createButtonView(sizePx: Int): View {
        val frame = FrameLayout(this)

        val button = TextView(this).apply {
            text = "TAP"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = createButtonBackground()
        }

        frame.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return frame
    }

    private fun createButtonBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#CC2196F3"))
            setStroke(4, Color.WHITE)
        }
    }

    private fun startTapping() {
        if (isTapping) return
        isTapping = true

        // FIX 2 (ana hata — tap hiç gitmiyordu): WifiAdbHelper.tapping'i true yap
        // Olmadan sendTap executor'ı her tap'ı anında "discard" ediyordu
        WifiAdbHelper.tapping = true

        updateButtonColor(true)

        val interval = prefs.getLong(PREF_TAP_INTERVAL, DEFAULT_TAP_INTERVAL_MS)

        tapRunnable = object : Runnable {
            override fun run() {
                if (!isTapping) return
                sendTap()
                handler.postDelayed(this, interval)
            }
        }
        handler.post(tapRunnable!!)
    }

    private fun stopTapping() {
        isTapping = false

        // FIX 1 (devam): executor'daki son kuyruktaki tapları da durdur
        WifiAdbHelper.tapping = false

        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = null
        updateButtonColor(false)
    }

    private fun sendTap() {
        // FIX 4 (koordinat hatası): getLocationOnScreen() ile gerçek ekran
        // koordinatını al — params.x/y bazı cihazlarda status bar offsetini
        // yanlış hesaplıyor, getLocationOnScreen her zaman doğru değeri verir.
        // params.width kullan (floatingView.width ilk çağrıda 0 dönebilir).
        val loc = IntArray(2)
        floatingView.getLocationOnScreen(loc)
        val x = loc[0] + (params.width / 2)
        val y = loc[1] + (params.height / 2)

        Thread {
            WifiAdbHelper.sendTap(applicationContext, x, y)
        }.start()
    }

    private fun updateButtonColor(active: Boolean) {
        (floatingView as? FrameLayout)?.let { frame ->
            val tv = frame.getChildAt(0) as? TextView
            val bg = tv?.background as? android.graphics.drawable.GradientDrawable
            bg?.setColor(
                Color.parseColor(if (active) "#CCF44336" else "#CC2196F3")
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB Tap Button")
            .setContentText("Yüzen buton aktif — basılı tutarak tap gönderin")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB Tap Button",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Yüzen buton servisi bildirimi"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
