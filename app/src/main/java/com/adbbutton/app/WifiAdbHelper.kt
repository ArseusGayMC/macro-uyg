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
    private val tapExecutor = Executors.newSingleThreadExecutor()
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val TLS_CONNECT = "_adb-tls-connect._tcp"

    interface DiscoveryCallback {
        fun onPairingFound(ip: String, port: Int)
        fun onConnectFound(ip: String, port: Int)
        fun onPairingLost()
    }

    /** Result from pair/connect — carries success flag + human-readable error. */
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

    /** Pair with a device. Returns AdbResult — check .error for failure details. */
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

    /** Connect to a known ip:port. Returns AdbResult. */
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

    /** Connect via mDNS auto-discovery — use when connect port is unknown after pairing. */
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

    fun sendTap(context: Context, x: Int, y: Int) {
        tapExecutor.submit {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                if (!manager.isConnected) return@submit
                val stream = manager.openStream("shell:input tap $x $y")
                stream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Tap failed: ${e.message}")
            }
        }
    }

    fun isConnected(context: Context): Boolean = try {
        AdbConnectionManager.getInstance(context).isConnected
    } catch (_: Exception) { false }

    fun disconnect(context: Context) {
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
