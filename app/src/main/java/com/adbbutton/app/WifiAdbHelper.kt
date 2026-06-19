package com.adbbutton.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object WifiAdbHelper {

    private const val TAG = "WifiAdbHelper"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val TLS_CONNECT = "_adb-tls-connect._tcp"

    interface DiscoveryCallback {
        fun onPairingFound(ip: String, port: Int)
        fun onConnectFound(ip: String, port: Int)
        fun onPairingLost()
    }

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
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        val port = resolved.port
                        callback.onPairingFound(ip, port)
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                callback.onPairingLost()
            }
        }

        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) {}
            override fun onStartDiscoveryFailed(s: String, e: Int) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStopDiscoveryFailed(s: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        val port = resolved.port
                        callback.onConnectFound(ip, port)
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        nsdManager.discoverServices(TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        nsdManager.discoverServices(TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)

        return Pair(nsdManager, listOf(pairingListener, connectListener))
    }

    suspend fun pair(context: Context, ip: String, port: Int, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                manager.pair(ip, port, code)
                Log.d(TAG, "Pairing successful")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed: ${e.message}")
                false
            }
        }

    suspend fun connect(context: Context, ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                val result = manager.connect(ip, port)
                Log.d(TAG, "Connect result: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                false
            }
        }

    suspend fun sendShellCommand(context: Context, command: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val manager = AdbConnectionManager.getInstance(context)
                if (!manager.isConnected) return@withContext null
                val stream = manager.openStream("shell:$command")
                val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
                val result = reader.readLine()
                reader.close()
                stream.close()
                result
            } catch (e: Exception) {
                Log.e(TAG, "Shell command failed: ${e.message}")
                null
            }
        }

    fun sendTap(context: Context, x: Int, y: Int) {
        try {
            val manager = AdbConnectionManager.getInstance(context)
            if (!manager.isConnected) return
            val stream = manager.openStream("shell:input tap $x $y")
            stream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed: ${e.message}")
        }
    }

    fun isConnected(context: Context): Boolean {
        return try {
            AdbConnectionManager.getInstance(context).isConnected
        } catch (e: Exception) {
            false
        }
    }

    fun disconnect(context: Context) {
        AdbConnectionManager.resetInstance()
    }
}
