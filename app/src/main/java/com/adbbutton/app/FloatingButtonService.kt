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

        private const val DEFAULT_SIZE_DP         = 64
        private const val DEFAULT_TAP_INTERVAL_MS = 100L
        private const val LONG_PRESS_THRESHOLD_MS = 150L

        // Tapping başlarken buton bu konuma taşınır (stop butonu olur)
        private const val STOP_X_DP = 8
        private const val STOP_Y_DP = 8

        private const val COLOR_IDLE  = "#CC2196F3"
        private const val COLOR_RED   = "#CCF44336"
        private const val COLOR_GREEN = "#CC4CAF50"
        private const val COLOR_STOP  = "#CC9C27B0"  // mor = stop modu

        // Temel flag: overlay yeni dokunuşlara kapalı değil başlangıçta
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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
    private var isTapping   = false
    private var flashToggle = false

    // Tapping öncesindeki gerçek buton konumu (stop sonrası buraya döner)
    private var savedX = 100
    private var savedY = 300

    // Gerçek tap hedefi (piksel) — buton oyun alanındayken hesaplanır
    private var tapTargetX = 0
    private var tapTargetY = 0

    private var isLocked    = true
    private var initialX    = 0
    private var initialY    = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging  = false

    private lateinit var params: WindowManager.LayoutParams

    // ─── Lifecycle ────────────────────────────────────────────────────────────

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

        floatingView = buildView(sizePx)

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 100)
            y = prefs.getInt(PREF_Y, 300)
        }

        floatingView.setOnTouchListener { _, event -> handleTouch(event) }
        runCatching { windowManager.addView(floatingView, params) }
    }

    // ─── Touch işleme ────────────────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                initialX    = params.x
                initialY    = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging  = false

                if (isLocked) {
                    if (isTapping) {
                        // Tapping aktifken butona basıldı = DURDUR
                        stopTapping()
                    } else {
                        // 150ms basılı tut → tapping başlar
                        handler.postDelayed({ if (!isDragging) startTapping() }, LONG_PRESS_THRESHOLD_MS)
                    }
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY

                if (!isLocked) {
                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(floatingView, params) }
                    }
                } else if (!isTapping) {
                    if (!isDragging && (Math.abs(dx) > 15 || Math.abs(dy) > 15)) {
                        isDragging = true
                        handler.removeCallbacksAndMessages(null)
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isTapping) {
                    handler.removeCallbacksAndMessages(null)
                }
                if (!isLocked && isDragging && event.action == MotionEvent.ACTION_UP) {
                    prefs.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                }
                true
            }

            else -> false
        }
    }

    // ─── View oluşturma ──────────────────────────────────────────────────────

    private fun buildView(sizePx: Int): FrameLayout {
        val frame = FrameLayout(this)

        mainButton = TextView(this).apply {
            text     = "TAP"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            background = circleDrawable(COLOR_IDLE)
        }
        frame.addView(mainButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val lockPx = dpToPx(22)
        lockBtn = TextView(this).apply {
            text        = lockIcon(isLocked)
            textSize    = 10f
            setTextColor(Color.WHITE)
            gravity     = Gravity.CENTER
            background  = lockDrawable(isLocked)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleLock() }
        }
        frame.addView(lockBtn, FrameLayout.LayoutParams(lockPx, lockPx).apply {
            gravity     = Gravity.TOP or Gravity.END
            topMargin   = dpToPx(2)
            rightMargin = dpToPx(2)
        })

        return frame
    }

    // ─── Kilit toggle ─────────────────────────────────────────────────────────

    private fun toggleLock() {
        if (isTapping) stopTapping()
        isLocked = !isLocked
        prefs.edit().putBoolean(PREF_LOCKED, isLocked).apply()
        lockBtn.text       = lockIcon(isLocked)
        lockBtn.background = lockDrawable(isLocked)
        mainButton.background = circleDrawable(COLOR_IDLE)
    }

    private fun lockIcon(locked: Boolean) = if (locked) "🔒" else "🔓"

    // ─── Tapping ──────────────────────────────────────────────────────────────

    private fun startTapping() {
        if (isTapping) return
        isTapping   = true
        flashToggle = false
        WifiAdbHelper.tapping = true

        // Tap hedefini şu anki buton konumunun merkezinden hesapla
        val loc = IntArray(2)
        floatingView.getLocationOnScreen(loc)
        tapTargetX = loc[0] + (params.width / 2)
        tapTargetY = loc[1] + (params.height / 2)

        // Butonun şu anki konumunu kaydet (durdurulunca buraya dönecek)
        savedX = params.x
        savedY = params.y

        // Butonu köşeye taşı → oyun alanı tamamen serbest kalır
        // ADB taplar hâlâ tapTargetX/Y hedefine gider (butonun eski yerine)
        params.x = dpToPx(STOP_X_DP)
        params.y = dpToPx(STOP_Y_DP)
        runCatching { windowManager.updateViewLayout(floatingView, params) }

        mainButton.text = "⏹"
        mainButton.background = circleDrawable(COLOR_STOP)

        val interval = prefs.getLong(PREF_TAP_INTERVAL, DEFAULT_TAP_INTERVAL_MS)
        tapRunnable = object : Runnable {
            override fun run() {
                if (!isTapping) return
                flashToggle = !flashToggle
                mainButton.background = circleDrawable(if (flashToggle) COLOR_GREEN else COLOR_RED)
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

        // Butonu eski yerine geri taşı
        params.x = savedX
        params.y = savedY
        runCatching { windowManager.updateViewLayout(floatingView, params) }

        if (::mainButton.isInitialized) {
            mainButton.text = "TAP"
            mainButton.background = circleDrawable(COLOR_IDLE)
        }
    }

    private fun sendTap() {
        // tapTargetX/Y = tapping başlamadan önceki buton merkezi (oyundaki asıl hedef)
        Thread { WifiAdbHelper.sendTap(applicationContext, tapTargetX, tapTargetY) }.start()
    }

    // ─── Drawable'lar ─────────────────────────────────────────────────────────

    private fun circleDrawable(colorHex: String) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
            setStroke(4, Color.WHITE)
        }

    private fun lockDrawable(locked: Boolean) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(if (locked) "#DD795548" else "#DD2E7D32"))
            setStroke(2, Color.parseColor("#88FFFFFF"))
        }

    // ─── Bildirim ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopPI = PendingIntent.getService(this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
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
