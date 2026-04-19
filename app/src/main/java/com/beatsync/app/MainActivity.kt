package com.beatsync.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.location.LocationManager
import android.media.*
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvBass: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvHigh: TextView
    private lateinit var colorPreview: View
    private lateinit var btnConnect: Button
    private lateinit var btnStartStop: Button
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivity: TextView
    private lateinit var btnMenuDots: ImageButton
    private lateinit var logoGroup: View
    private lateinit var statsGroup: View
    private lateinit var seekGroup: View

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val CHAR_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"
    private var isConnected = false
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    // BLE optimization
    private var lastSentR = -1
    private var lastSentG = -1
    private var lastSentB = -1
    private var pendingR = -1
    private var pendingG = -1
    private var pendingB = -1
    private val bleHandler = Handler(Looper.getMainLooper())

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    // Audio
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val SAMPLE_RATE = 44100
    private val BUFFER_SIZE = 2048

    // FFT
    private val fft = FloatFFT_1D(BUFFER_SIZE.toLong())
    private val fftBuffer = FloatArray(BUFFER_SIZE * 2)

    // Beat detection
    private var sensitivity = 5f
    private var bassAvg = 0f
    private var prevBass = 0f
    private var lastBeatTime = 0L
    private val COOLDOWN = 170L
    private var brightness = 0.25f
    private val MIN_BRIGHTNESS = 0.25f
    private val NOISE_GATE = 0.004f

    // Color system
    private val colors = arrayOf(
        intArrayOf(255, 0, 0),
        intArrayOf(0, 255, 100),
        intArrayOf(0, 100, 255),
        intArrayOf(255, 0, 255)
    )
    private var colorIndex = 0
    private var displayR = 255f
    private var displayG = 0f
    private var displayB = 0f

    // Single color mode
    private var singleColorMode = false
    private var lockedColor = intArrayOf(255, 0, 0)

    private val handler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        bindUI()
        setupBLEQueue()
        revealUI()

        btnConnect.setOnClickListener { startScan() }
        btnStartStop.setOnClickListener { toggleSync() }

        findViewById<NavigationView>(R.id.navView)
            .setNavigationItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_advanced -> {
                        drawerLayout.closeDrawer(GravityCompat.END)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Advanced Engine")
                            .setMessage("This feature is currently unavailable.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    else -> {
                        drawerLayout.closeDrawer(GravityCompat.END)
                    }
                }
                true
            }
    }

    // ─── UI Setup ────────────────────────────────────────────────────

    private fun bindUI() {
        drawerLayout = findViewById(R.id.drawerLayout)
        tvStatus = findViewById(R.id.tvStatus)
        tvBass = findViewById(R.id.tvBass)
        tvMid = findViewById(R.id.tvMid)
        tvHigh = findViewById(R.id.tvHigh)
        colorPreview = findViewById(R.id.colorPreview)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStop = findViewById(R.id.btnStartStop)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivity = findViewById(R.id.tvSensitivity)
        btnMenuDots = findViewById(R.id.btnMenuDots)
        logoGroup = findViewById(R.id.logoGroup)
        statsGroup = findViewById(R.id.statsGroup)
        seekGroup = findViewById(R.id.seekGroup)

        seekBarSensitivity.max = 9
        seekBarSensitivity.progress = 4
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, f: Boolean) {
                sensitivity = (p + 1).toFloat()
                tvSensitivity.text = "Sensitivity: ${p + 1}"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun revealUI() {
        val root = drawerLayout

        // Animate background white → black
        ValueAnimator.ofArgb(Color.WHITE, Color.BLACK).apply {
            duration = 800
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }

        // After background fades reveal UI elements
        Handler(Looper.getMainLooper()).postDelayed({

            logoGroup.visibility = View.VISIBLE
            logoGroup.alpha = 0f
            logoGroup.animate().alpha(1f).setDuration(500).start()

            btnMenuDots.visibility = View.VISIBLE
            btnMenuDots.alpha = 0f
            btnMenuDots.animate().alpha(1f).setDuration(500).start()

            tvStatus.visibility = View.VISIBLE
            tvStatus.alpha = 0f
            tvStatus.animate().alpha(1f).setDuration(500).start()

            btnConnect.visibility = View.VISIBLE
            btnConnect.alpha = 0f
            btnConnect.animate().alpha(1f).setDuration(500).start()

        }, 600)

        // Wire up menu button
        btnMenuDots.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    // ─── Service Checks ──────────────────────────────────────────────

    private fun ensureServicesReady(): Boolean {
        val loc = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val btEnabled = bluetoothAdapter?.isEnabled == true
        val locEnabled = loc.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                loc.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!btEnabled) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth Required")
                .setMessage("Please turn on Bluetooth to scan for devices.")
                .setPositiveButton("OK", null)
                .show()
            return false
        }

        if (!locEnabled) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Location Required")
                .setMessage("Android requires Location to be enabled for Bluetooth scanning. Please enable it.")
                .setPositiveButton("OK", null)
                .show()
            return false
        }

        return true
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        }
    }

    // ─── BLE Scan ────────────────────────────────────────────────────

    private fun startScan() {
        if (!checkPermissions()) return
        if (!ensureServicesReady()) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (isScanning) return

        isScanning = true
        btnConnect.text = "Searching..."

        // Show cancel button
        val btnCancel = findViewById<Button>(R.id.btnCancelScan)
        btnCancel.visibility = View.VISIBLE
        btnCancel.setOnClickListener {
            stopScan(scanner)
        }

        val devices = mutableListOf<BluetoothDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val d = result.device
                if (d.name?.contains("ELK", true) == true && !devices.contains(d)) {
                    devices.add(d)
                    runOnUiThread {
                        showDeviceDialog(devices, scanner, this)
                    }
                }
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)

        // Auto stop after 30s
        handler.postDelayed({
            if (isScanning) stopScan(scanner)
        }, 30000)
    }

    private fun stopScan(scanner: BluetoothLeScanner) {
        scanCallback?.let { scanner.stopScan(it) }
        isScanning = false
        btnConnect.text = "Search for Devices"
        findViewById<Button>(R.id.btnCancelScan).visibility = View.GONE
    }

    private fun showDeviceDialog(
        devs: List<BluetoothDevice>,
        scanner: BluetoothLeScanner,
        cb: ScanCallback
    ) {
        val names = devs.map { it.name ?: "Unknown" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Available Devices")
            .setItems(names) { _, i ->
                scanner.stopScan(cb)
                isScanning = false
                btnConnect.text = "Search for Devices"
                findViewById<Button>(R.id.btnCancelScan).visibility = View.GONE
                connect(devs[i])
            }
            .setNegativeButton("Cancel") { _, _ ->
                scanner.stopScan(cb)
                isScanning = false
                btnConnect.text = "Search for Devices"
                findViewById<Button>(R.id.btnCancelScan).visibility = View.GONE
            }
            .show()
    }

    // ─── BLE Connect ─────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        tvStatus.text = "Connecting..."
        bluetoothGatt = device.connectGatt(
            this, false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        g.requestMtu(512)
                        g.discoverServices()
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnected = false
                        runOnUiThread {
                            tvStatus.text = "Disconnected"
                            hideConnectedUI()
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                    writeCharacteristic = g.getService(
                        java.util.UUID.fromString(SERVICE_UUID)
                    )?.getCharacteristic(java.util.UUID.fromString(CHAR_UUID))

                    if (writeCharacteristic != null) {
                        isConnected = true
                        runOnUiThread { showConnectedUI() }
                    }
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun showConnectedUI() {
        tvStatus.text = "Connected ✓"
        btnConnect.visibility = View.GONE
        btnStartStop.visibility = View.VISIBLE
        colorPreview.visibility = View.VISIBLE
        statsGroup.visibility = View.VISIBLE
        seekGroup.visibility = View.VISIBLE

        // Fade them in
        listOf(btnStartStop, colorPreview, statsGroup, seekGroup).forEach {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(500).start()
        }
    }

    private fun hideConnectedUI() {
        btnConnect.visibility = View.VISIBLE
        btnStartStop.visibility = View.GONE
        colorPreview.visibility = View.GONE
        statsGroup.visibility = View.GONE
        seekGroup.visibility = View.GONE
    }

    // ─── Audio Processing ─────────────────────────────────────────────

    private fun processAudio(buf: ShortArray, read: Int) {
        for (i in 0 until read) fftBuffer[i] = buf[i] / 32768f
        fft.realForwardFull(fftBuffer)

        val bass = band(1, 90)
        val mid = band(250, 2000)

        // Noise gate
        if (bass < NOISE_GATE) {
            brightness = (brightness * 0.95f).coerceAtLeast(MIN_BRIGHTNESS)
            val r = (displayR * brightness).toInt().coerceIn(0, 255)
            val g = (displayG * brightness).toInt().coerceIn(0, 255)
            val b = (displayB * brightness).toInt().coerceIn(0, 255)
            queueColor(r, g, b)
            return
        }

        val flux = max(0f, bass - prevBass)
        prevBass = bass
        bassAvg = bassAvg * 0.97f + flux * 0.03f

        val now = System.currentTimeMillis()
        val threshold = bassAvg * (1.35f + (10f - sensitivity) / 4.5f)
        val isBeat = flux > threshold && now - lastBeatTime > COOLDOWN

        if (isBeat) {
            lastBeatTime = now
            if (!singleColorMode) {
                colorIndex = (colorIndex + 1) % colors.size
                displayR = colors[colorIndex][0].toFloat()
                displayG = colors[colorIndex][1].toFloat()
                displayB = colors[colorIndex][2].toFloat()
            } else {
                displayR = lockedColor[0].toFloat()
                displayG = lockedColor[1].toFloat()
                displayB = lockedColor[2].toFloat()
            }
            brightness = 1f
        }

        val decay = exp(-(now - lastBeatTime) / 350f).toFloat()
        val pulse = (mid * 6f).coerceAtMost(0.5f)
        brightness = (decay + pulse).coerceIn(MIN_BRIGHTNESS, 1f)

        val r = (displayR * brightness).toInt().coerceIn(0, 255)
        val g = (displayG * brightness).toInt().coerceIn(0, 255)
        val b = (displayB * brightness).toInt().coerceIn(0, 255)

        queueColor(r, g, b)

        handler.post {
            tvBass.text = "BASS: ${"%.2f".format(flux)}"
            tvMid.text = "MID: ${"%.2f".format(mid)}"
            tvHigh.text = "${(brightness * 100).toInt()}%"
            colorPreview.background?.setColorFilter(Color.rgb(r, g, b), PorterDuff.Mode.SRC_IN)
        }
    }

    private fun band(startHz: Int, endHz: Int): Float {
        val hzPerBin = SAMPLE_RATE.toFloat() / BUFFER_SIZE
        val start = (startHz / hzPerBin).toInt().coerceAtLeast(0)
        val end = (endHz / hzPerBin).toInt().coerceAtMost(BUFFER_SIZE / 2)
        if (end <= start) return 0f
        var sum = 0f
        for (i in start until end) {
            val mag = sqrt(
                fftBuffer[2 * i] * fftBuffer[2 * i] +
                        fftBuffer[2 * i + 1] * fftBuffer[2 * i + 1]
            )
            sum += mag
        }
        return sum / (end - start)
    }

    // ─── BLE Queue ───────────────────────────────────────────────────

    private fun setupBLEQueue() {
        bleHandler.post(object : Runnable {
            override fun run() {
                if (pendingR >= 0 &&
                    (pendingR != lastSentR || pendingG != lastSentG || pendingB != lastSentB)
                ) {
                    send(pendingR, pendingG, pendingB)
                    lastSentR = pendingR
                    lastSentG = pendingG
                    lastSentB = pendingB
                    pendingR = -1
                }
                bleHandler.postDelayed(this, 30)
            }
        })
    }

    private fun queueColor(r: Int, g: Int, b: Int) {
        pendingR = r; pendingG = g; pendingB = b
    }

    private fun send(r: Int, g: Int, b: Int) {
        val c = writeCharacteristic ?: return
        if (!isConnected) return
        c.value = byteArrayOf(
            0x7e, 0x00, 0x05, 0x03,
            r.toByte(), g.toByte(), b.toByte(),
            0x00, 0xef.toByte()
        )
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(c)
    }

    // ─── Sync Control ─────────────────────────────────────────────────

    private fun toggleSync() {
        if (isRunning) stopSync() else startSync()
    }

    private fun startSync() {
        // Start foreground service
        val serviceIntent = Intent(this, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Wake lock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeatSync::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            tvStatus.text = "Mic init failed"
            return
        }

        audioRecord?.startRecording()
        isRunning = true
        btnStartStop.text = "STOP"

        Thread {
            val buf = ShortArray(BUFFER_SIZE)
            while (isRunning) {
                val r = audioRecord?.read(buf, 0, BUFFER_SIZE) ?: 0
                if (r > 0) processAudio(buf, r)
            }
        }.start()
    }

    private fun stopSync() {
        isRunning = false
        stopService(Intent(this, ForegroundService::class.java))
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        btnStartStop.text = "START"
        queueColor(0, 0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSync()
        bluetoothGatt?.close()
    }
}