package com.adbbutton.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adbbutton.app.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nsdManager: NsdManager? = null
    private var discoveryListeners: List<NsdManager.DiscoveryListener>? = null

    private var discoveredPairIp: String? = null
    private var discoveredPairPort: Int? = null
    private var discoveredConnectIp: String? = null
    private var discoveredConnectPort: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        updateConnectionStatus()
    }

    private fun setupUI() {
        binding.btnOverlayPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        binding.btnAutoDiscover.setOnClickListener {
            startMdnsDiscovery()
        }

        binding.btnManualConnect.setOnClickListener {
            showManualConnectDialog()
        }

        binding.btnPairConnect.setOnClickListener {
            showFullPairDialog()
        }

        binding.btnStartFloating.setOnClickListener {
            if (!WifiAdbHelper.isConnected(this)) {
                Toast.makeText(this, "Önce ADB bağlantısı kurun!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Ekran üstü çizim izni gerekli!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            startForegroundService(Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_START
            })
            Toast.makeText(this, "Yüzen buton başlatıldı!", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopFloating.setOnClickListener {
            startService(Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_STOP
            })
        }

        binding.btnDisconnect.setOnClickListener {
            WifiAdbHelper.disconnect(this)
            updateConnectionStatus()
            Toast.makeText(this, "Bağlantı kesildi", Toast.LENGTH_SHORT).show()
        }

        val prefs = getSharedPreferences(FloatingButtonService.PREF_NAME, MODE_PRIVATE)

        binding.seekBarSize.progress = prefs.getInt(FloatingButtonService.PREF_SIZE, 64)
        binding.tvSizeValue.text = "${binding.seekBarSize.progress}dp"
        binding.seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress.coerceIn(32, 128)
                binding.tvSizeValue.text = "${v}dp"
                prefs.edit().putInt(FloatingButtonService.PREF_SIZE, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val intervalMs = prefs.getLong(FloatingButtonService.PREF_TAP_INTERVAL, 100L).toInt()
        binding.seekBarInterval.progress = intervalMs
        binding.tvIntervalValue.text = "${intervalMs}ms"
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress.coerceIn(50, 1000)
                binding.tvIntervalValue.text = "${v}ms"
                prefs.edit().putLong(FloatingButtonService.PREF_TAP_INTERVAL, v.toLong()).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ─── Auto-Discover (mDNS) ─────────────────────────────────────────────────

    private fun startMdnsDiscovery() {
        stopDiscovery()
        discoveredPairIp = null; discoveredPairPort = null
        discoveredConnectIp = null; discoveredConnectPort = null

        binding.tvDiscoveryStatus.text = "🔍 Cihaz aranıyor..."
        binding.tvDiscoveryStatus.visibility = View.VISIBLE

        val result = WifiAdbHelper.startDiscovery(this, object : WifiAdbHelper.DiscoveryCallback {
            override fun onPairingFound(ip: String, port: Int) {
                runOnUiThread {
                    discoveredPairIp = ip
                    discoveredPairPort = port
                    binding.tvDiscoveryStatus.text = "✓ Cihaz bulundu — $ip"
                    // Show simple code-only dialog
                    showCodeOnlyDialog(ip, port)
                }
            }

            override fun onConnectFound(ip: String, port: Int) {
                runOnUiThread {
                    discoveredConnectIp = ip
                    discoveredConnectPort = port
                }
            }

            override fun onPairingLost() {
                runOnUiThread {
                    discoveredPairIp = null; discoveredPairPort = null
                    if (binding.tvDiscoveryStatus.text.startsWith("✓").not())
                        binding.tvDiscoveryStatus.text = "🔍 Cihaz aranıyor..."
                }
            }
        })
        nsdManager = result.first
        discoveryListeners = result.second
    }

    private fun stopDiscovery() {
        val listeners = discoveryListeners ?: return
        val nm = nsdManager ?: return
        listeners.forEach { try { nm.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListeners = null; nsdManager = null
    }

    // ─── Simple Code-Only Dialog (after auto-discover) ───────────────────────

    private fun showCodeOnlyDialog(pairIp: String, pairPort: Int) {
        val etCode = EditText(this).apply {
            hint = "867733"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.3f
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(etCode)
        }

        AlertDialog.Builder(this)
            .setTitle("📱 Eşleştirme Kodu")
            .setMessage("Wireless Debugging → Kod ile eşleştir ekranındaki 6 haneli kodu girin")
            .setView(container)
            .setPositiveButton("Bağlan") { _, _ ->
                val code = etCode.text.toString().trim()
                if (code.length < 6) {
                    Toast.makeText(this, "6 haneli kodu girin!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                doPairAndConnect(pairIp, pairPort, code)
            }
            .setNegativeButton("İptal", null)
            .show()

        // Auto-focus + keyboard
        etCode.requestFocus()
    }

    // ─── Core pair + connect logic ────────────────────────────────────────────

    private fun doPairAndConnect(pairIp: String, pairPort: Int, code: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvDiscoveryStatus.text = "⏳ Eşleştiriliyor..."

        lifecycleScope.launch {
            val pairResult = WifiAdbHelper.pair(this@MainActivity, pairIp, pairPort, code)

            if (!pairResult.success) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.tvDiscoveryStatus.text = "✗ Eşleştirme başarısız"
                    Toast.makeText(
                        this@MainActivity,
                        "Eşleştirme başarısız!\n${pairResult.error ?: "Bilinmeyen hata"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // Pairing succeeded — wait up to 5 s for connect port from mDNS
            runOnUiThread { binding.tvDiscoveryStatus.text = "✓ Eşleştirildi, bağlanıyor..." }

            repeat(10) {
                if (discoveredConnectPort != null) return@repeat
                delay(500)
            }

            val connectIp = discoveredConnectIp ?: pairIp
            val connectPort = discoveredConnectPort

            val connectResult = if (connectPort != null) {
                // Use the port we already discovered
                WifiAdbHelper.connect(this@MainActivity, connectIp, connectPort)
            } else {
                // mDNS didn't give us the connect port — let the library discover it itself
                runOnUiThread { binding.tvDiscoveryStatus.text = "🔍 Bağlantı portu aranıyor..." }
                WifiAdbHelper.connectTls(this@MainActivity, 8000L)
            }

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (connectResult.success) {
                    updateConnectionStatus()
                    binding.tvDiscoveryStatus.text = "✓ Bağlı"
                    stopDiscovery()
                    Toast.makeText(this@MainActivity, "Bağlantı başarılı! ✓", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvDiscoveryStatus.text = "✗ Bağlantı kurulamadı"
                    Toast.makeText(
                        this@MainActivity,
                        "Eşleştirildi ama bağlanamadı:\n${connectResult.error ?: "?"}\n\nManuel IP ile Bağlan'ı deneyin.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ─── Full manual pair dialog ──────────────────────────────────────────────

    private fun showFullPairDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pair, null)
        val etIp = dialogView.findViewById<EditText>(R.id.etPairIp)
        val etPort = dialogView.findViewById<EditText>(R.id.etPairPort)
        val etCode = dialogView.findViewById<EditText>(R.id.etPairCode)
        val etConnectPort = dialogView.findViewById<EditText>(R.id.etConnectPortAfterPair)

        // Pre-fill if we already discovered something
        discoveredPairIp?.let { etIp.setText(it) }
        discoveredPairPort?.let { etPort.setText(it.toString()) }
        discoveredConnectPort?.let { etConnectPort.setText(it.toString()) }

        AlertDialog.Builder(this)
            .setTitle("🔑 Manuel Eşleştirme")
            .setView(dialogView)
            .setPositiveButton("Eşleştir") { _, _ ->
                val ip = etIp.text.toString().trim()
                val pPort = etPort.text.toString().trim().toIntOrNull() ?: 0
                val code = etCode.text.toString().trim()
                val cPort = etConnectPort.text.toString().trim().toIntOrNull()

                if (ip.isEmpty() || pPort == 0 || code.isEmpty()) {
                    Toast.makeText(this, "IP, eşleştirme portu ve kodu doldurun!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (cPort != null) {
                    discoveredConnectIp = ip
                    discoveredConnectPort = cPort
                }
                doPairAndConnect(ip, pPort, code)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ─── Manual connect dialog ────────────────────────────────────────────────

    private fun showManualConnectDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_connect, null)
        val etIp = dialogView.findViewById<EditText>(R.id.etConnectIp)
        val etPort = dialogView.findViewById<EditText>(R.id.etConnectPort)

        // Pre-fill discovered connect info
        discoveredConnectIp?.let { etIp.setText(it) }
        val prefPort = discoveredConnectPort ?: 0
        etPort.setText(if (prefPort > 0) prefPort.toString() else "")

        AlertDialog.Builder(this)
            .setTitle("📡 Manuel Bağlantı")
            .setMessage("Wireless Debugging ana ekranındaki IP ve port")
            .setView(dialogView)
            .setPositiveButton("Bağlan") { _, _ ->
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 0

                if (ip.isEmpty() || port == 0) {
                    Toast.makeText(this, "IP ve port girin!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val result = WifiAdbHelper.connect(this@MainActivity, ip, port)
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (result.success) {
                            updateConnectionStatus()
                            Toast.makeText(this@MainActivity, "Bağlantı başarılı! ✓", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Bağlantı başarısız!\n${result.error ?: "?"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    private fun updateConnectionStatus() {
        val connected = WifiAdbHelper.isConnected(this)
        binding.tvConnectionStatus.text = if (connected) "✓ Bağlı" else "✗ Bağlı Değil"
        binding.tvConnectionStatus.setTextColor(if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        binding.btnStartFloating.isEnabled = connected
        binding.btnDisconnect.isEnabled = connected
        binding.btnStartFloating.alpha = if (connected) 1f else 0.5f
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        binding.btnOverlayPermission.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
