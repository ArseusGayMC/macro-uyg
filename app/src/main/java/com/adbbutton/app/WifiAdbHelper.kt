package com.adbbutton.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

object WifiAdbHelper {

    private const val TAG = "WifiAdbHelper"

    /**
     * Single-thread executor for tap commands.
     * Each tap drains the ADB stream (waits for `input tap` to actually finish)
     * before returning, so taps are sequential and never cut short.
     */
    private val tapExecutor = Executors.newSingleThreadExecutor()

    /**
     * Set true while the button is held, false the instant the finger lifts.
     * Checked inside the executor so queued-but-stale taps are discarded.
     */
    @Volatile
    var tapping = false

    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val TLS_CONNECT = "_adb-tls-connect._tcp"

    interface DiscoveryCallback {
        fun onPairingFound(ip: String, port: Int)
        fun onConnectFound(ip: String, port: Int)
        fun onPairingLost()
    }

    data class AdbResult(val success: Boolean, val error: String? = null)

    fun startDiscovery(context: Context, callback: DiscoveryCallback): Pair<NsdManager, List<NsdManager.DiscoveryListener>> {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) {}
            override fun onStartDiscoveryFailed(s: String, e: Int) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStopDiscoveryFailed(s: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                        Log.w(TAG, "Pair resolve failed: $e")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        Log.d(TAG, "Pairing service found: $ip:${resolved.port}")
                        callback.onPairingFound(ip, resolved.port)
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) { callback.onPairingLost() }
        }

        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) {}
            override fun onStartDiscoveryFailed(s: String, e: Int) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStopDiscoveryFailed(s: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                        Log.w(TAG, "Connect resolve failed: $e")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        Log.d(TAG, "Connect service found: $ip:${resolved.port}")
                        callback.onConnectFound(ip, resolved.port)
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        nsdManager.discoverServices(TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        nsdManager.discoverServices(TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
        return Pair(nsdManager, listOf(pairingListener, connectListener))
    }

    suspend fun pair(context: Context, ip: String, port: Int, code: String): AdbResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "pair() → $ip:$port code=$code")
                val manager = AdbConnectionManager.getInstance(context)
                manager.pair(ip, port, code)
                Log.d(TAG, "pair() SUCCESS")
                AdbResult(true)
            } catch (e: Exception) {
                val msg = buildErrorMessage(e)
                Log.e(TAG, "pair() FAILED: $msg", e)
                AdbResult(false, msg)
            }
        }

    suspend fun connect(context: Context, ip: String, port: Int): AdbResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "connect() → $ip:$port")
                val manager = AdbConnectionManager.getInstance(context)
                val ok = manager.connect(ip, port)
                Log.d(TAG, "connect() result=$ok")
                if (ok) AdbResult(true) else AdbResult(false, "Sunucu bağlantıyı reddetti")
            } catch (e: Exception) {
                val msg = buildErrorMessage(e)
                Log.e(TAG, "connect() FAILED: $msg", e)
                AdbResult(false, msg)
            }
        }

    suspend fun connectTls(context: Context, timeoutMs: Long = 8000L): AdbResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "connectTls() auto-discover timeout=${timeoutMs}ms")
                val manager = AdbConnectionManager.getInstance(context)
                val ok = manager.connectTls(context, timeoutMs)
                Log.d(TAG, "connectTls() result=$ok")
                if (ok) AdbResult(true) else AdbResult(false, "Otomatik bağlantı başarısız")
            } catch (e: Exception) {
                val msg = buildErrorMessage(e)
                Log.e(TAG, "connectTls() FAILED: $msg", e)
                AdbResult(false, msg)
            }
        }

    /**
     * Send an ADB tap at the given screen coordinates.
     *
     * FIX 1: Check [tapping] before submitting — if the finger was already lifted,
     *         discard this tap immediately without touching the ADB connection.
     *
     * FIX 2: Drain the AdbInputStream until the daemon closes it.
     *         Without this, stream.close() sends CLSE and the daemon kills
     *         `input tap` before it actually executes on the remote screen.
     */
    fun sendTap(context: Context, x: Int, y: Int) {
        if (!tapping) return
        tapExecutor.submit {
            if (!tapping) return@submit
            try {
                val manager = AdbConnectionManager.getInstance(context)
                if (!manager.isConnected) {
                    Log.w(TAG, "sendTap: not connected")
                    return@submit
                }
                val stream = manager.openStream("shell:input tap $x $y")
                val input = stream.openInputStream()
                try {
                    val buf = ByteArray(256)
                    @Suppress("ControlFlowWithEmptyBody")
                    while (input.read(buf) != -1) {}
                } catch (_: Exception) {
                    // "Stream closed" = input tap finished normally
                }
                runCatching { stream.close() }
                Log.v(TAG, "tap($x,$y) sent")
            } catch (e: Exception) {
                Log.e(TAG, "sendTap failed: ${e.message}")
            }
        }
    }

    fun isConnected(context: Context): Boolean = try {
        AdbConnectionManager.getInstance(context).isConnected
    } catch (_: Exception) { false }

    fun disconnect(context: Context) {
        tapping = false
        AdbConnectionManager.resetInstance()
    }

    private fun buildErrorMessage(e: Exception): String {
        val base = e.message ?: e.javaClass.simpleName
        return when {
            base.contains("ECONNREFUSED", ignoreCase = true) -> "Bağlantı reddedildi — port kapalı mı?"
            base.contains("timeout", ignoreCase = true) -> "Bağlantı zaman aşımı"
            base.contains("handshake", ignoreCase = true) -> "TLS el sıkışması başarısız"
            base.contains("PairingAuthCtx", ignoreCase = true) -> "SPAKE2 hatası — kod yanlış olabilir"
            base.contains("peer info", ignoreCase = true) -> "Eşleştirme başarısız — kodu kontrol edin"
            base.contains("Exchanging", ignoreCase = true) -> "Hatalı eşleştirme kodu"
            else -> base
        }
    }
}
