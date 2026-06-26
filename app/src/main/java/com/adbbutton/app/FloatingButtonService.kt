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
        const val ACTION_STOP  = "com.adbbutton.app.ACTION_STOP"
        const val PREF_NAME         = "FloatingButtonPrefs"
        const val PREF_X            = "button_x"
        const val PREF_Y            = "button_y"
        const val PREF_SIZE         = "button_size"
        const val PREF_TAP_INTERVAL = "tap_interval"
        const val PREF_LOCKED       = "movement_locked"
        const val CHANNEL_ID = "floating_button_channel"
        const val NOTIF_ID   = 1001

        private const val DEFAULT_SIZE_DP        = 64
        private const val DEFAULT_TAP_INTERVAL_MS = 100L
        // 150ms = parmak tutunca hemen tıklamaya başlar, yeterince hızlı
        private const val LONG_PRESS_THRESHOLD_MS = 150L

        @Volatile var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var mainButton: TextView
    private lateinit var lockBtn: TextView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var tapRunnable: Runnable? = null
    private var isTapping = false

    // Hareket kilidi: true = kilitli (tap modu), false = açık (sürükleme modu)
    private var isLocked = true

    private var initialX   = 0
    private var initialY   = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging  = false

    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        isLocked = prefs.getBoolean(PREF_LOCKED, true)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())
        showFloatingButton()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopTapping()
        if (::floatingView.isInitialized)
            runCatching { windowManager.removeView(floatingView) }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ─── Floating button ──────────────────────────────────────────────────────

    private fun showFloatingButton() {
        val sizeDp = prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP)
        val sizePx = dpToPx(sizeDp)
        val savedX  = prefs.getInt(PREF_X, 100)
        val savedY  = prefs.getInt(PREF_Y, 300)

        floatingView = buildView(sizePx)

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX; y = savedY
        }

        // Touch listener — ana daireye uygulanan (lock butonu kendi listener'ını tüketir)
        floatingView.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        runCatching { windowManager.addView(floatingView, params) }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                initialX    = params.x
                initialY    = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging  = false

                if (isLocked) {
                    // KİLİTLİ: basılı tut → oto-tap başlar
                    handler.postDelayed({
                        if (!isDragging) startTapping()
                    }, LONG_PRESS_THRESHOLD_MS)
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY

                if (!isLocked) {
                    // SERBEST: sürükleme aktif
                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(floatingView, params) }
                    }
                } else {
                    // KİLİTLİ: küçük kayma varsa long-press iptal et
                    if (!isDragging && (Math.abs(dx) > 15 || Math.abs(dy) > 15)) {
                        isDragging = true
                        handler.removeCallbacksAndMessages(null)
                        if (isTapping) stopTapping()
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Parmak kalkınca anında durdur — executor'daki bekleyenler da iptal
                WifiAdbHelper.tapping = false
                handler.removeCallbacksAndMessages(null)
                if (isTapping) stopTapping()

                if (!isLocked && isDragging) {
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

    // ─── View oluşturma ──────────────────────────────────────────────────────

    private fun buildView(sizePx: Int): FrameLayout {
        val frame = FrameLayout(this)

        // Ana TAP dairesi
        mainButton = TextView(this).apply {
            text   = "TAP"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = tapButtonBackground(false)
        }
        frame.addView(mainButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Küçük kilit toggle — sağ üst köşede
        val lockPx = dpToPx(22)
        lockBtn = TextView(this).apply {
            text     = lockIcon(isLocked)
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            background = lockBackground(isLocked)
            isClickable  = true
            isFocusable  = true
            setOnClickListener { toggleLock() }
        }
        val lp = FrameLayout.LayoutParams(lockPx, lockPx).apply {
            gravity = Gravity.TOP or Gravity.END
            // üst ve sağdan biraz içeri
            topMargin  = dpToPx(2)
            rightMargin = dpToPx(2)
        }
        frame.addView(lockBtn, lp)

        return frame
    }

    // ─── Kilit toggle ────────────────────────────────────────────────────────

    private fun toggleLock() {
        isLocked = !isLocked
        prefs.edit().putBoolean(PREF_LOCKED, isLocked).apply()
        lockBtn.text       = lockIcon(isLocked)
        lockBtn.background = lockBackground(isLocked)

        if (!isLocked && isTapping) stopTapping()
        // Renk sıfırla
        mainButton.background = tapButtonBackground(isTapping)
    }

    private fun lockIcon(locked: Boolean) = if (locked) "🔒" else "🔓"

    private fun lockBackground(locked: Boolean) =
        android.graphics.drawable.GradientDrawable().apply {
            shape    = android.graphics.drawable.GradientDrawable.OVAL
            // kilitli=turuncu, serbest=yeşil
            setColor(Color.parseColor(if (locked) "#DD795548" else "#DD2E7D32"))
            setStroke(2, Color.parseColor("#88FFFFFF"))
        }

    // ─── Tapping ─────────────────────────────────────────────────────────────

    private fun startTapping() {
        if (isTapping) return
        isTapping = true
        WifiAdbHelper.tapping = true
        mainButton.background = tapButtonBackground(true)

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
        WifiAdbHelper.tapping = false
        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = null
        if (::mainButton.isInitialized) mainButton.background = tapButtonBackground(false)
    }

    private fun sendTap() {
        // getLocationOnScreen → gerçek piksel koordinatları (status bar dahil)
        // params.width/height → görünür boyut (floatingView.width ilk çağrıda 0 olabilir)
        val loc = IntArray(2)
        floatingView.getLocationOnScreen(loc)
        val x = loc[0] + (params.width / 2)
        val y = loc[1] + (params.height / 2)
        Thread { WifiAdbHelper.sendTap(applicationContext, x, y) }.start()
    }

    // ─── Drawable yardımcıları ────────────────────────────────────────────────

    private fun tapButtonBackground(active: Boolean) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(if (active) "#CCF44336" else "#CC2196F3"))
            setStroke(4, Color.WHITE)
        }

    // ─── Bildirim ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openPI = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB Tap Button")
            .setContentText("🔒=tap modu  🔓=sürükleme modu")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPI)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ADB Tap Button", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Yüzen buton servisi" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
