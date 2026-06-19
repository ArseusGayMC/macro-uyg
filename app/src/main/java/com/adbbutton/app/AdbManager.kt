package com.adbbutton.app

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AdbManager {

    private var connection: Dadb? = null
    private var keyPair: AdbKeyPair? = null

    var isConnected = false
        private set

    var connectedHost = ""
        private set

    var connectedPort = 0
        private set

    fun init(filesDir: File) {
        val keyFile = File(filesDir, "adb_key")
        keyPair = if (keyFile.exists()) {
            AdbKeyPair.read(keyFile, File(filesDir, "adb_key.pub"))
        } else {
            AdbKeyPair.generate().also {
                it.writePrivate(keyFile)
                it.writePublic(File(filesDir, "adb_key.pub"))
            }
        }
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val kp = keyPair ?: error("AdbManager not initialized")
                Dadb.pair(host, port, pairingCode, kp)
            }
        }

    suspend fun connect(host: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val kp = keyPair ?: error("AdbManager not initialized")
                val dadb = Dadb.create(host, port, kp)
                connection?.close()
                connection = dadb
                isConnected = true
                connectedHost = host
                connectedPort = port
            }
        }

    suspend fun sendTap(x: Int, y: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dadb = connection ?: error("Not connected")
                dadb.shell("input tap $x $y")
            }
        }

    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
        isConnected = false
        connectedHost = ""
        connectedPort = 0
    }
}
