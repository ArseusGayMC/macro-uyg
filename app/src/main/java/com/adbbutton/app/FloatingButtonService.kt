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

/**
 * İki overlay mimarisi:
 *
 * 1. HEDEF NOKTA (targetView) — kırmızı ⊕, oyun butonunun üzerine sürüklenir.
 *    Tapping sırasında FLAG_NOT_TOUCHABLE → ADB taplar oyuna geçer.
 *    Tapping dışında sürüklenebilir (🔓 modunda).
 *
 * 2. TUT BUTONU (holdView) — mavi ●, kullanıcının parmağını koyduğu yer.
 *    HER ZAMAN touchable → ACTION_DOWN = başla, ACTION_UP = dur.
 *    FLAG_NOT_TOUCHABLE ASLA eklenmez.
 */
class FloatingButtonService : Service() {

    companion object {
        const val ACTION_STOP = "com.adbbutton.app.ACTION_STOP"
        const val PREF_NAME      = "FloatingButtonPrefs"
        const val PREF_HOLD_X   = "hold_x"
        const val PREF_HOLD_Y   = "hold_y"
        const val PREF_TARGET_X = "target_x"
        const val PREF_TARGET_Y = "target_y"
        const val PREF_SIZE          = "button_size"
        const val PREF_TAP_INTERVAL  = "tap_interval"
        const val CHANNEL_ID = "floating_button_channel"
        const val NOTIF_ID   = 1001

        private const val DEFAULT_SIZE_DP         = 64
        private const val DEFAULT_TAP_INTERVAL_MS = 100L
        private const val LONG_PRESS_MS           = 150L

        // Renkler
        private const val COL_HOLD_IDLE   = "#CC2196F3"  // mavi
        private const val COL_HOLD_ACTIVE = "#CC4CAF50"  // yeşil (tapping)
        private const val COL_TARGET_IDLE = "#CCF44336"  // kırmızı
        private const val COL_TARGET_TAP  = "#CCFF9800"  // turuncu flash (tapping)
        private const val COL_LOCK_ON     = "#DD795548"  // kahve (kilitli)
        private const val COL_LOCK_OFF    = "#DD1565C0"  // koyu mavi (serbest)

        // Her iki overlay için ortak window flags (FLAG_NOT_TOUCHABLE YOK)
        private val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        @Volatile var isRunning = false; private set
    }

    // ─── Sistem servisleri ────────────────────────────────────────────────────
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // ─── TUT butonu ───────────────────────────────────────────────────────────
    private lateinit var holdView: FrameLayout
    private lateinit var holdBtn: TextView
    private lateinit var holdLockBtn: TextView
    private lateinit var holdParams: WindowManager.LayoutParams
    private var holdLocked = true          // true = sürükleme kapalı (hold modu)
    private var holdInitX = 0; private var holdInitY = 0
    private var holdTouchX = 0f; private var holdTouchY = 0f
    private var holdDragging = false

    // ─── HEDEF nokta ─────────────────────────────────────────────────────────
    private lateinit var targetView: TextView
    private lateinit var targetParams: WindowManager.LayoutParams
    private var targetLocked = true        // true = sürükleme kapalı (ADB pass-through)
    private var tgtInitX = 0; private var tgtInitY = 0
    private var tgtTouchX = 0f; private var tgtTouchY = 0f
    private var tgtDragging = false
    private var flashToggle = false

    // ─── Tapping durumu ───────────────────────────────────────────────────────
    @Volatile private var isTapping = false
    private var tapRunnable: Runnable? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())
        showOverlays()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopTapping()
        runCatching { wm.removeView(holdView) }
        runCatching { wm.removeView(targetView) }
        super.onDestroy()
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ─── Overlays ─────────────────────────────────────────────────────────────

    private fun showOverlays() {
        val sz = px(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        buildHoldView(sz)
        buildTargetView(sz)
    }

    // ── TUT Butonu ────────────────────────────────────────────────────────────

    private fun buildHoldView(sz: Int) {
        holdView = FrameLayout(this)

        holdBtn = TextView(this).apply {
            text     = "TUT"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity  = android.view.Gravity.CENTER
            background = circle(COL_HOLD_IDLE)
        }
        holdView.addView(holdBtn, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val lsz = px(22)
        holdLockBtn = TextView(this).apply {
            text        = "🔒"
            textSize    = 10f
            setTextColor(Color.WHITE)
            gravity     = android.view.Gravity.CENTER
            background  = lockBg(true)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleHoldLock() }
        }
        holdView.addView(holdLockBtn, FrameLayout.LayoutParams(lsz, lsz).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        holdParams = WindowManager.LayoutParams(sz, sz,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,   // ASLA FLAG_NOT_TOUCHABLE eklenmez
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_HOLD_X, 16)
            y = prefs.getInt(PREF_HOLD_Y, 400)
        }

        holdView.setOnTouchListener { _, ev -> onHoldTouch(ev) }
        runCatching { wm.addView(holdView, holdParams) }
    }

    private fun onHoldTouch(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                holdInitX = holdParams.x; holdInitY = holdParams.y
                holdTouchX = ev.rawX; holdTouchY = ev.rawY
                holdDragging = false
                if (holdLocked && !isTapping) {
                    // 150ms basılı tut → tapping başlar
                    handler.postDelayed({
                        if (!holdDragging && !isTapping) startTapping()
                    }, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - holdTouchX; val dy = ev.rawY - holdTouchY
                if (!holdLocked) {
                    if (!holdDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) holdDragging = true
                    if (holdDragging) {
                        holdParams.x = holdInitX + dx.toInt()
                        holdParams.y = holdInitY + dy.toInt()
                        runCatching { wm.updateViewLayout(holdView, holdParams) }
                    }
                } else if (!isTapping && !holdDragging && (Math.abs(dx) > 15 || Math.abs(dy) > 15)) {
                    holdDragging = true
                    handler.removeCallbacksAndMessages(null)
                }
            }
            MotionEvent.ACTION_UP -> {
                // ★ ANA ÇALIŞMA PRENSİBİ:
                // holdView HER ZAMAN touchable → ACTION_UP burada alınır.
                // Tapping aktifken parmak kalktı = DUR.
                if (isTapping) stopTapping()
                else handler.removeCallbacksAndMessages(null)
                if (!holdLocked && holdDragging) {
                    prefs.edit().putInt(PREF_HOLD_X, holdParams.x).putInt(PREF_HOLD_Y, holdParams.y).apply()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // Sistem iptal → tapping aktifse devam et (telefon titreşimi vb.)
                if (!isTapping) handler.removeCallbacksAndMessages(null)
            }
        }
        return true
    }

    private fun toggleHoldLock() {
        if (isTapping) stopTapping()
        holdLocked = !holdLocked
        holdLockBtn.text = if (holdLocked) "🔒" else "🔓"
        holdLockBtn.background = lockBg(holdLocked)
    }

    // ── Hedef Nokta ───────────────────────────────────────────────────────────

    private fun buildTargetView(sz: Int) {
        val tsz = px(52)   // hedef biraz küçük
        targetView = TextView(this).apply {
            text     = "⊕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity  = android.view.Gravity.CENTER
            background = circle(COL_TARGET_IDLE)
        }

        targetParams = WindowManager.LayoutParams(tsz, tsz,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Başta sürüklenebilir (kilitsiz)
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_TARGET_X, 400)
            y = prefs.getInt(PREF_TARGET_Y, 600)
        }

        targetView.setOnTouchListener { _, ev -> onTargetTouch(ev) }
        runCatching { wm.addView(targetView, targetParams) }
    }

    private fun onTargetTouch(ev: MotionEvent): Boolean {
        // Tapping sırasında FLAG_NOT_TOUCHABLE → bu touch listener çalışmaz.
        // Sadece idle modda sürükleme için çalışır.
        if (isTapping) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                tgtInitX = targetParams.x; tgtInitY = targetParams.y
                tgtTouchX = ev.rawX; tgtTouchY = ev.rawY
                tgtDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - tgtTouchX; val dy = ev.rawY - tgtTouchY
                if (!tgtDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) tgtDragging = true
                if (tgtDragging) {
                    targetParams.x = tgtInitX + dx.toInt()
                    targetParams.y = tgtInitY + dy.toInt()
                    runCatching { wm.updateViewLayout(targetView, targetParams) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tgtDragging) {
                    prefs.edit().putInt(PREF_TARGET_X, targetParams.x).putInt(PREF_TARGET_Y, targetParams.y).apply()
                }
            }
        }
        return true
    }

    // ─── Tapping ─────────────────────────────────────────────────────────────

    private fun startTapping() {
        if (isTapping) return
        isTapping = true
        flashToggle = false
        WifiAdbHelper.tapping = true

        holdBtn.text = "●"
        holdBtn.background = circle(COL_HOLD_ACTIVE)

        // ★ Hedef noktayı FLAG_NOT_TOUCHABLE yap → ADB taplar oyuna geçer
        targetParams.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        targetView.background = circle(COL_TARGET_TAP)
        runCatching { wm.updateViewLayout(targetView, targetParams) }

        val interval = prefs.getLong(PREF_TAP_INTERVAL, DEFAULT_TAP_INTERVAL_MS)
        tapRunnable = object : Runnable {
            override fun run() {
                if (!isTapping) return
                flashToggle = !flashToggle
                targetView.background = circle(if (flashToggle) COL_TARGET_TAP else COL_TARGET_IDLE)
                sendTap()
                handler.postDelayed(this, interval)
            }
        }
        handler.post(tapRunnable!!)
    }

    private fun stopTapping() {
        if (!isTapping) return
        isTapping = false
        WifiAdbHelper.tapping = false
        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = null

        holdBtn.text = "TUT"
        holdBtn.background = circle(COL_HOLD_IDLE)

        // Hedef noktayı tekrar sürüklenebilir yap
        targetParams.flags = BASE_FLAGS
        targetView.background = circle(COL_TARGET_IDLE)
        runCatching { wm.updateViewLayout(targetView, targetParams) }
    }

    private fun sendTap() {
        // Hedef noktanın ekran konumu
        val loc = IntArray(2)
        targetView.getLocationOnScreen(loc)
        val x = loc[0] + (targetParams.width / 2)
        val y = loc[1] + (targetParams.height / 2)
        Thread { WifiAdbHelper.sendTap(applicationContext, x, y) }.start()
    }

    // ─── Drawable ─────────────────────────────────────────────────────────────

    private fun circle(hex: String) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(hex))
            setStroke(4, Color.WHITE)
        }

    private fun lockBg(locked: Boolean) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(if (locked) COL_LOCK_ON else COL_LOCK_OFF))
            setStroke(2, Color.parseColor("#88FFFFFF"))
        }

    // ─── Bildirim ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopPI = PendingIntent.getService(this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB Tap Button")
            .setContentText("🔵 TUT = basılı tut/çek  •  🔴 ⊕ = hedef konuma sürükle")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPI)
            .setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ADB Tap Button", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
