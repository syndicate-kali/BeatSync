package com.beatsync.app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvBass: TextView
    private lateinit var colorPreview: View
    private lateinit var btnConnect: Button
    private lateinit var btnStartStop: Button
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivity: TextView

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val WRITE_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val WRITE_CHAR_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"
    private var isConnected = false
    private var isScanning = false

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    // Audio
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val SAMPLE_RATE = 44100
    private val BUFFER_SIZE = 2048

    // FFT
    private val fft = FloatFFT_1D(BUFFER_SIZE.toLong())

    // Beat & Melody Detection
    private var sensitivity = 5f
    private var bassAverage = 0f
    private var prevBassEnergy = 0f
    private var currentBrightness = 0.25f
    private val MIN_BRIGHTNESS = 0.25f
    private val MELODY_MAX_BRIGHTNESS = 0.65f // Vocals won't outshine the kick
    private var lastBeatTime = 0L
    private val BEAT_COOLDOWN = 170L
    private val NOISE_GATE = 0.004f

    // Color palette
    private val powerColors = arrayOf(
        intArrayOf(255, 0, 0),
        intArrayOf(0, 255, 120),
        intArrayOf(0, 50, 255),
        intArrayOf(255, 0, 255),
        intArrayOf(255, 120, 0),
        intArrayOf(0, 255, 0)
    )
    private var colorIndex = 0
    private var displayR = 255f
    private var displayG = 255f
    private var displayB = 255f

    // BLE write queue
    private val bleWriteHandler = Handler(Looper.getMainLooper())
    private var pendingR = -1
    private var pendingG = -1
    private var pendingB = -1

    private val handler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBass = findViewById(R.id.tvBass)
        colorPreview = findViewById(R.id.colorPreview)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStop = findViewById(R.id.btnStartStop)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivity = findViewById(R.id.tvSensitivity)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener { startCleanScan() }
        btnStartStop.setOnClickListener { toggleSync() }

        seekBarSensitivity.max = 9
        seekBarSensitivity.progress = 4
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, f: Boolean) {
                sensitivity = (p + 1).toFloat()
                tvSensitivity.text = "Sensitivity: ${(p + 1)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        bleWriteHandler.post(object : Runnable {
            override fun run() {
                if (pendingR >= 0) {
                    sendColorDirect(pendingR, pendingG, pendingB)
                    pendingR = -1
                }
                bleWriteHandler.postDelayed(this, 30)
            }
        })
    }

    // ─── Audio Processing ────────────────────────────────────────────

    private fun processAudio(buffer: ShortArray, read: Int) {
        val fftBuffer = FloatArray(BUFFER_SIZE * 2)
        for (i in 0 until read) fftBuffer[i] = buffer[i] / 32768f
        fft.realForwardFull(fftBuffer)

        val hzPerBin = SAMPLE_RATE.toFloat() / BUFFER_SIZE

        // Define our two bands
        val bassEnd = (95f / hzPerBin).toInt().coerceAtLeast(1)
        val melodyStart = (250f / hzPerBin).toInt()
        val melodyEnd = (2000f / hzPerBin).toInt()

        val bassEnergy = calculateBandEnergy(fftBuffer, 1, bassEnd)
        val melodyEnergy = calculateBandEnergy(fftBuffer, melodyStart, melodyEnd)

        val flux = max(0f, bassEnergy - prevBassEnergy)
        prevBassEnergy = bassEnergy

        bassAverage = (bassAverage * 0.97f) + (flux * 0.03f)
        val currentTime = System.currentTimeMillis()
        val threshold = bassAverage * (1.35f + (10f - sensitivity) / 4.5f)

        // LOGIC STREAM 1: THE BEAT (Bass Kick)
        if (flux > threshold && currentTime - lastBeatTime > BEAT_COOLDOWN && bassEnergy > NOISE_GATE) {
            colorIndex = (colorIndex + 1) % powerColors.size
            displayR = powerColors[colorIndex][0].toFloat()
            displayG = powerColors[colorIndex][1].toFloat()
            displayB = powerColors[colorIndex][2].toFloat()
            currentBrightness = 1.0f
            lastBeatTime = currentTime
        }
        // LOGIC STREAM 2: THE ATMOSPHERE (Vocals/Melody)
        else if (melodyEnergy > NOISE_GATE) {
            // Brightness "breathes" with the melody energy
            // We map the melody energy to a range between MIN and MELODY_MAX
            val targetMelodyBrightness = (melodyEnergy * 15f * (sensitivity / 5f)).coerceIn(MIN_BRIGHTNESS, MELODY_MAX_BRIGHTNESS)

            // Smoothly move toward the melody brightness
            currentBrightness += (targetMelodyBrightness - currentBrightness) * 0.15f

            // SLOW DRIFT: Slowly nudge the colors toward the next palette color without jumping
            val nextIndex = (colorIndex + 1) % powerColors.size
            val driftSpeed = 0.01f // Much slower than a beat jump
            displayR += (powerColors[nextIndex][0] - displayR) * driftSpeed
            displayG += (powerColors[nextIndex][1] - displayG) * driftSpeed
            displayB += (powerColors[nextIndex][2] - displayB) * driftSpeed
        }
        // LOGIC STREAM 3: SILENCE
        else {
            currentBrightness *= 0.92f
            if (currentBrightness < MIN_BRIGHTNESS) currentBrightness = MIN_BRIGHTNESS
        }

        val r = (displayR * currentBrightness).toInt().coerceIn(0, 255)
        val g = (displayG * currentBrightness).toInt().coerceIn(0, 255)
        val b = (displayB * currentBrightness).toInt().coerceIn(0, 255)

        queueColor(r, g, b)

        handler.post {
            tvBass.text = "FLUX: ${"%.2f".format(flux)} | MELODY: ${"%.2f".format(melodyEnergy)}"
            colorPreview.background?.setColorFilter(Color.rgb(r, g, b), PorterDuff.Mode.SRC_IN)
        }
    }

    private fun calculateBandEnergy(fftBuffer: FloatArray, start: Int, end: Int): Float {
        var sum = 0f
        val actualEnd = if (end > BUFFER_SIZE / 2) BUFFER_SIZE / 2 else end
        for (i in start until actualEnd) {
            val real = fftBuffer[2 * i]
            val imag = fftBuffer[2 * i + 1]
            sum += sqrt(real * real + imag * imag)
        }
        return sum / (actualEnd - start)
    }

    // ─── BLE Write Queue ─────────────────────────────────────────────

    private fun queueColor(r: Int, g: Int, b: Int) {
        pendingR = r; pendingG = g; pendingB = b
    }

    private fun sendColorDirect(r: Int, g: Int, b: Int) {
        val char = writeCharacteristic ?: return
        if (!isConnected) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val command = byteArrayOf(0x7e.toByte(), 0x00.toByte(), 0x05.toByte(), 0x03.toByte(), r.toByte(), g.toByte(), b.toByte(), 0x00.toByte(), 0xef.toByte())
        char.value = command
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(char)
    }

    // ─── Sync Control ────────────────────────────────────────────────

    private fun toggleSync() { if (isRunning) stopSync() else startSync() }

    private fun startSync() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val serviceIntent = Intent(this, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeatSync::WakeLock")
        wakeLock?.acquire()

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, BUFFER_SIZE * 2)
        audioRecord?.startRecording()
        isRunning = true
        btnStartStop.text = "STOP SYNC"

        Thread {
            val buffer = ShortArray(BUFFER_SIZE)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read > 0) processAudio(buffer, read)
            }
        }.start()
    }

    private fun stopSync() {
        isRunning = false
        stopService(Intent(this, ForegroundService::class.java))
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        btnStartStop.text = "START SYNC"
        queueColor(0, 0, 0)
        handler.post { colorPreview.background?.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN) }
    }

    // ─── BLE Scan & Connect ──────────────────────────────────────────

    private fun startCleanScan() {
        val permsList = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permsList.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = permsList.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }

        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1)
            return
        }
        if (isScanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        isScanning = true; btnConnect.isEnabled = false; updateStatus("Scanning...")

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(ct: Int, res: ScanResult) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                if (res.device.name?.contains("ELK-", true) == true) {
                    scanner.stopScan(this); isScanning = false
                    updateStatus("Found! Connecting...")
                    connectToDevice(res.device)
                }
            }
        }
        scanner.startScan(null, settings, callback)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, s: Int, ns: Int) {
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.requestMtu(512); gatt.discoverServices()
                    }
                } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false; handler.post { btnStartStop.isEnabled = false; btnConnect.isEnabled = true; updateStatus("Disconnected") }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, s: Int) {
                writeCharacteristic = gatt.getService(java.util.UUID.fromString(WRITE_SERVICE_UUID))?.getCharacteristic(java.util.UUID.fromString(WRITE_CHAR_UUID))
                if (writeCharacteristic != null) { isConnected = true; handler.post { updateStatus("Connected ✓"); btnStartStop.isEnabled = true } }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun updateStatus(msg: String) { handler.post { tvStatus.text = msg } }
    override fun onDestroy() { super.onDestroy(); stopSync(); bluetoothGatt?.close() }
}