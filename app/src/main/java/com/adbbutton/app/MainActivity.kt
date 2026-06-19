package com.adbbutton.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adbbutton.app.databinding.ActivityMainBinding
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
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnAutoDiscover.setOnClickListener {
            startMdnsDiscovery()
        }

        binding.btnManualConnect.setOnClickListener {
            showManualConnectDialog()
        }

        binding.btnPairConnect.setOnClickListener {
            showPairDialog()
        }

        binding.btnStartFloating.setOnClickListener {
            if (!WifiAdbHelper.isConnected(this)) {
                Toast.makeText(this, "Önce ADB bağlantısı kurun!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Ekran üstü çizim izni gerekli!", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
                return@setOnClickListener
            }
            val intent = Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_START
            }
            startForegroundService(intent)
            Toast.makeText(this, "Yüzen buton başlatıldı!", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopFloating.setOnClickListener {
            val intent = Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_STOP
            }
            startService(intent)
        }

        binding.btnDisconnect.setOnClickListener {
            WifiAdbHelper.disconnect(this)
            updateConnectionStatus()
            Toast.makeText(this, "Bağlantı kesildi", Toast.LENGTH_SHORT).show()
        }

        val prefs = getSharedPreferences(FloatingButtonService.PREF_NAME, MODE_PRIVATE)

        val sizeDp = prefs.getInt(FloatingButtonService.PREF_SIZE, 64)
        binding.seekBarSize.progress = sizeDp
        binding.tvSizeValue.text = "${sizeDp}dp"
        binding.seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(32, 128)
                binding.tvSizeValue.text = "${clamped}dp"
                prefs.edit().putInt(FloatingButtonService.PREF_SIZE, clamped).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val intervalMs = prefs.getLong(FloatingButtonService.PREF_TAP_INTERVAL, 100L).toInt()
        binding.seekBarInterval.progress = intervalMs
        binding.tvIntervalValue.text = "${intervalMs}ms"
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(50, 1000)
                binding.tvIntervalValue.text = "${clamped}ms"
                prefs.edit().putLong(FloatingButtonService.PREF_TAP_INTERVAL, clamped.toLong()).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startMdnsDiscovery() {
        stopDiscovery()
        binding.tvDiscoveryStatus.text = "Cihaz aranıyor..."
        binding.tvDiscoveryStatus.visibility = View.VISIBLE

        val pair = WifiAdbHelper.startDiscovery(this, object : WifiAdbHelper.DiscoveryCallback {
            override fun onPairingFound(ip: String, port: Int) {
                runOnUiThread {
                    discoveredPairIp = ip
                    discoveredPairPort = port
                    binding.tvDiscoveryStatus.text = "Eşleştirme servisi bulundu: $ip:$port"
                    showPairDialog(ip, port)
                }
            }

            override fun onConnectFound(ip: String, port: Int) {
                runOnUiThread {
                    discoveredConnectIp = ip
                    discoveredConnectPort = port
                    binding.tvDiscoveryStatus.text = "Bağlantı servisi bulundu: $ip:$port"
                }
            }

            override fun onPairingLost() {
                runOnUiThread {
                    discoveredPairIp = null
                    discoveredPairPort = null
                    if (binding.tvDiscoveryStatus.text.toString().startsWith("Eşleştirme")) {
                        binding.tvDiscoveryStatus.text = "Cihaz aranıyor..."
                    }
                }
            }
        })

        nsdManager = pair.first
        discoveryListeners = pair.second
    }

    private fun stopDiscovery() {
        val listeners = discoveryListeners ?: return
        val nm = nsdManager ?: return
        listeners.forEach { listener ->
            try { nm.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        discoveryListeners = null
        nsdManager = null
    }

    private fun showPairDialog(ip: String = "", port: Int = 0) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pair, null)
        val etIp = dialogView.findViewById<android.widget.EditText>(R.id.etPairIp)
        val etPort = dialogView.findViewById<android.widget.EditText>(R.id.etPairPort)
        val etCode = dialogView.findViewById<android.widget.EditText>(R.id.etPairCode)

        if (ip.isNotEmpty()) etIp.setText(ip)
        if (port > 0) etPort.setText(port.toString())

        AlertDialog.Builder(this)
            .setTitle("ADB Eşleştirme")
            .setView(dialogView)
            .setPositiveButton("Eşleştir") { _, _ ->
                val pairIp = etIp.text.toString().trim()
                val pairPort = etPort.text.toString().trim().toIntOrNull() ?: 0
                val code = etCode.text.toString().trim()

                if (pairIp.isEmpty() || pairPort == 0 || code.isEmpty()) {
                    Toast.makeText(this, "Tüm alanları doldurun!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val pairOk = WifiAdbHelper.pair(this@MainActivity, pairIp, pairPort, code)
                    if (pairOk) {
                        val connectIp = discoveredConnectIp ?: pairIp
                        val connectPort = discoveredConnectPort ?: 5555
                        val connectOk = WifiAdbHelper.connect(this@MainActivity, connectIp, connectPort)
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            if (connectOk) {
                                updateConnectionStatus()
                                Toast.makeText(this@MainActivity, "Bağlantı başarılı! ✓", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Eşleştirme OK ama bağlantı başarısız", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "Eşleştirme başarısız! Kodu kontrol edin.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showManualConnectDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_connect, null)
        val etIp = dialogView.findViewById<android.widget.EditText>(R.id.etConnectIp)
        val etPort = dialogView.findViewById<android.widget.EditText>(R.id.etConnectPort)
        etPort.setText("5555")

        AlertDialog.Builder(this)
            .setTitle("Manuel Bağlantı")
            .setView(dialogView)
            .setPositiveButton("Bağlan") { _, _ ->
                val connectIp = etIp.text.toString().trim()
                val connectPort = etPort.text.toString().trim().toIntOrNull() ?: 5555

                if (connectIp.isEmpty()) {
                    Toast.makeText(this, "IP adresini girin!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                binding.progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val ok = WifiAdbHelper.connect(this@MainActivity, connectIp, connectPort)
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (ok) {
                            updateConnectionStatus()
                            Toast.makeText(this@MainActivity, "Bağlantı başarılı! ✓", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Bağlantı başarısız!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun updateConnectionStatus() {
        val connected = WifiAdbHelper.isConnected(this)
        binding.tvConnectionStatus.text = if (connected) "✓ Bağlı" else "✗ Bağlı Değil"
        binding.tvConnectionStatus.setTextColor(
            if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )
        binding.btnStartFloating.isEnabled = connected
        binding.btnDisconnect.isEnabled = connected
        binding.btnStartFloating.alpha = if (connected) 1f else 0.5f
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.btnOverlayPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
