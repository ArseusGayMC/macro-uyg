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
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * İki bağımsız yüzen buton:
 *
 *  ① HOLD butonu (mavi)
 *     – Oyunun dışında rahat tutulabilecek bir yere koy.
 *     – Basılı tut → tapping başlar; parmak kalk → tapping durur.
 *     – Kilitsizken sürüklenebilir.
 *
 *  ② HEDEF butonu (yeşil/kırmızı)
 *     – Oyun butonunun tam üzerine sürükle.
 *     – ADB taplar bu butonun konumuna gider.
 *     – Tapping sırasında FLAG_NOT_TOUCHABLE → ADB taplar oyuna geçer.
 *     – Kilitsizken sürüklenebilir (tapping aktif değilken).
 */
class FloatingButtonService : Service() {

    companion object {
        const val ACTION_START = "com.adbbutton.app.ACTION_START"
        const val ACTION_STOP  = "com.adbbutton.app.ACTION_STOP"

        const val PREF_NAME         = "FloatingButtonPrefs"
        const val PREF_HOLD_X       = "hold_x"
        const val PREF_HOLD_Y       = "hold_y"
        const val PREF_HOLD_LOCKED  = "hold_locked"
        const val PREF_TGT_X        = "target_x"
        const val PREF_TGT_Y        = "target_y"
        const val PREF_TGT_LOCKED   = "target_locked"
        const val PREF_SIZE         = "button_size"
        const val PREF_TAP_INTERVAL = "tap_interval"

        const val CHANNEL_ID = "floating_button_channel"
        const val NOTIF_ID   = 1001

        private const val DEFAULT_SIZE_DP    = 64
        private const val DEFAULT_INTERVAL   = 100L
        private const val LONG_PRESS_MS      = 150L

        // Renkler
        private const val COL_HOLD_IDLE   = "#CC1565C0"   // koyu mavi  (TUT – bekliyor)
        private const val COL_HOLD_ACTIVE = "#CC2E7D32"   // koyu yeşil (TUT – tapping)
        private const val COL_TGT_IDLE    = "#CCE65100"   // turuncu-kırmızı (HEDEF – bekliyor)
        private const val COL_TGT_FLASH_A = "#CCF44336"   // kırmızı flash A
        private const val COL_TGT_FLASH_B = "#CCFF9800"   // turuncu flash B
        private const val COL_LOCK_ON     = "#DD4A148C"   // mor  (kilitli)
        private const val COL_LOCK_OFF    = "#DD006064"   // teal (serbest)

        // Her overlay'in temel flagleri (FLAG_NOT_TOUCHABLE YOK)
        private val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        @Volatile var isRunning = false; private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // ── HOLD butonu ──────────────────────────────────────────────────────────
    private lateinit var holdFrame: FrameLayout
    private lateinit var holdLabel: TextView
    private lateinit var holdLock: TextView
    private lateinit var holdParams: WindowManager.LayoutParams
    private var holdLocked = true
    private var holdIX = 0; private var holdIY = 0
    private var holdTX = 0f; private var holdTY = 0f
    private var holdDrag = false

    // ── HEDEF butonu ─────────────────────────────────────────────────────────
    private lateinit var tgtFrame: FrameLayout
    private lateinit var tgtLabel: TextView
    private lateinit var tgtLock: TextView
    private lateinit var tgtParams: WindowManager.LayoutParams
    private var tgtLocked = true
    private var tgtIX = 0; private var tgtIY = 0
    private var tgtTX = 0f; private var tgtTY = 0f
    private var tgtDrag = false

    // ── Tapping ──────────────────────────────────────────────────────────────
    @Volatile private var isTapping = false
    private var tapRunnable: Runnable? = null
    private var flashState = false

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        wm    = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())
        buildHoldButton()
        buildTargetButton()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopTapping()
        runCatching { wm.removeView(holdFrame) }
        runCatching { wm.removeView(tgtFrame) }
        super.onDestroy()
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────────────────
    //  HOLD Butonu
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHoldButton() {
        val sz = px(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        holdLocked = prefs.getBoolean(PREF_HOLD_LOCKED, true)

        holdFrame = FrameLayout(this)

        // Ana etiket
        holdLabel = TextView(this).apply {
            text     = "TUT"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity  = android.view.Gravity.CENTER
            background = oval(COL_HOLD_IDLE)
        }
        holdFrame.addView(
            holdLabel,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Kilit rozeti
        val lsz = px(22)
        holdLock = TextView(this).apply {
            text        = lockIcon(holdLocked)
            textSize    = 10f
            setTextColor(Color.WHITE)
            gravity     = android.view.Gravity.CENTER
            background  = lockOval(holdLocked)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleHoldLock() }
        }
        holdFrame.addView(holdLock, FrameLayout.LayoutParams(lsz, lsz).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        holdParams = wlp(sz).apply {
            x = prefs.getInt(PREF_HOLD_X, 16)
            y = prefs.getInt(PREF_HOLD_Y, 500)
        }

        holdFrame.setOnTouchListener { _, ev -> onHoldTouch(ev) }
        runCatching { wm.addView(holdFrame, holdParams) }
    }

    private fun onHoldTouch(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                holdIX = holdParams.x; holdIY = holdParams.y
                holdTX = ev.rawX;      holdTY = ev.rawY
                holdDrag = false
                if (holdLocked && !isTapping) {
                    handler.postDelayed({
                        if (!holdDrag) startTapping()
                    }, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - holdTX; val dy = ev.rawY - holdTY
                if (!holdLocked) {
                    if (!holdDrag && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) holdDrag = true
                    if (holdDrag) {
                        holdParams.x = holdIX + dx.toInt()
                        holdParams.y = holdIY + dy.toInt()
                        runCatching { wm.updateViewLayout(holdFrame, holdParams) }
                    }
                } else if (!isTapping && !holdDrag &&
                    (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15)) {
                    holdDrag = true
                    handler.removeCallbacksAndMessages(null)
                }
            }
            MotionEvent.ACTION_UP -> {
                // ★ HER ZAMAN touchable → ACTION_UP her zaman gelir → tapping durur
                if (isTapping) stopTapping()
                else handler.removeCallbacksAndMessages(null)
                if (!holdLocked && holdDrag)
                    prefs.edit().putInt(PREF_HOLD_X, holdParams.x).putInt(PREF_HOLD_Y, holdParams.y).apply()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!isTapping) handler.removeCallbacksAndMessages(null)
                // tapping aktifse CANCEL'ı yok say (sistem olayı, durdurmayız)
            }
        }
        return true
    }

    private fun toggleHoldLock() {
        if (isTapping) stopTapping()
        holdLocked = !holdLocked
        prefs.edit().putBoolean(PREF_HOLD_LOCKED, holdLocked).apply()
        holdLock.text       = lockIcon(holdLocked)
        holdLock.background = lockOval(holdLocked)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HEDEF Butonu
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildTargetButton() {
        val sz = px(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        tgtLocked = prefs.getBoolean(PREF_TGT_LOCKED, false)  // başta sürüklenebilir

        tgtFrame = FrameLayout(this)

        tgtLabel = TextView(this).apply {
            text     = "HEDEF"
            textSize = 9f
            setTextColor(Color.WHITE)
            gravity  = android.view.Gravity.CENTER
            background = oval(COL_TGT_IDLE)
        }
        tgtFrame.addView(
            tgtLabel,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val lsz = px(22)
        tgtLock = TextView(this).apply {
            text        = lockIcon(tgtLocked)
            textSize    = 10f
            setTextColor(Color.WHITE)
            gravity     = android.view.Gravity.CENTER
            background  = lockOval(tgtLocked)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!isTapping) toggleTgtLock() }
        }
        tgtFrame.addView(tgtLock, FrameLayout.LayoutParams(lsz, lsz).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        tgtParams = wlp(sz).apply {
            x = prefs.getInt(PREF_TGT_X, 400)
            y = prefs.getInt(PREF_TGT_Y, 600)
        }

        tgtFrame.setOnTouchListener { _, ev -> onTargetTouch(ev) }
        runCatching { wm.addView(tgtFrame, tgtParams) }
    }

    private fun onTargetTouch(ev: MotionEvent): Boolean {
        // Tapping aktifken FLAG_NOT_TOUCHABLE → bu listener çağrılmaz.
        if (isTapping) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                tgtIX = tgtParams.x; tgtIY = tgtParams.y
                tgtTX = ev.rawX;     tgtTY = ev.rawY
                tgtDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - tgtTX; val dy = ev.rawY - tgtTY
                if (!tgtLocked) {
                    if (!tgtDrag && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) tgtDrag = true
                    if (tgtDrag) {
                        tgtParams.x = tgtIX + dx.toInt()
                        tgtParams.y = tgtIY + dy.toInt()
                        runCatching { wm.updateViewLayout(tgtFrame, tgtParams) }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tgtLocked && tgtDrag)
                    prefs.edit().putInt(PREF_TGT_X, tgtParams.x).putInt(PREF_TGT_Y, tgtParams.y).apply()
            }
        }
        return true
    }

    private fun toggleTgtLock() {
        tgtLocked = !tgtLocked
        prefs.edit().putBoolean(PREF_TGT_LOCKED, tgtLocked).apply()
        tgtLock.text       = lockIcon(tgtLocked)
        tgtLock.background = lockOval(tgtLocked)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Tapping
    // ─────────────────────────────────────────────────────────────────────────

    private fun startTapping() {
        if (isTapping) return
        isTapping = true
        flashState = false
        WifiAdbHelper.tapping = true

        // TUT butonu: yeşile dön (HER ZAMAN touchable, değişmez)
        holdLabel.text = "●"
        holdLabel.background = oval(COL_HOLD_ACTIVE)

        // HEDEF butonu: FLAG_NOT_TOUCHABLE → ADB taplar oyuna geçer
        tgtParams.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        tgtLabel.text   = "◉"
        tgtLabel.background = oval(COL_TGT_FLASH_A)
        runCatching { wm.updateViewLayout(tgtFrame, tgtParams) }

        val interval = prefs.getLong(PREF_TAP_INTERVAL, DEFAULT_INTERVAL)
        tapRunnable = object : Runnable {
            override fun run() {
                if (!isTapping) return
                flashState = !flashState
                tgtLabel.background = oval(if (flashState) COL_TGT_FLASH_A else COL_TGT_FLASH_B)
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

        holdLabel.text = "TUT"
        holdLabel.background = oval(COL_HOLD_IDLE)

        // HEDEF butonu: FLAG_NOT_TOUCHABLE kaldır → tekrar sürüklenebilir
        tgtParams.flags = BASE_FLAGS
        tgtLabel.text   = "HEDEF"
        tgtLabel.background = oval(COL_TGT_IDLE)
        runCatching { wm.updateViewLayout(tgtFrame, tgtParams) }
    }

    private fun sendTap() {
        val loc = IntArray(2)
        tgtFrame.getLocationOnScreen(loc)
        val x = loc[0] + (tgtParams.width / 2)
        val y = loc[1] + (tgtParams.height / 2)
        Thread { WifiAdbHelper.sendTap(applicationContext, x, y) }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Yardımcılar
    // ─────────────────────────────────────────────────────────────────────────

    /** Temel WindowManager.LayoutParams oluşturur (touchable, focusable değil). */
    private fun wlp(sz: Int) = WindowManager.LayoutParams(
        sz, sz,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun oval(hex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(hex))
        setStroke(4, Color.WHITE)
    }

    private fun lockOval(locked: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(if (locked) COL_LOCK_ON else COL_LOCK_OFF))
        setStroke(2, Color.parseColor("#88FFFFFF"))
    }

    private fun lockIcon(locked: Boolean) = if (locked) "🔒" else "🔓"

    // ─────────────────────────────────────────────────────────────────────────
    //  Bildirim
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopPI = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB Tap Button")
            .setContentText("🔵 TUT = basılı tut/çek  •  🔴 HEDEF = oyun butonuna sürükle")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPI)
            .setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "ADB Tap Button", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
