package com.beatsync.app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvBass: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvHigh: TextView
    private lateinit var colorPreview: android.view.View
    private lateinit var btnConnect: Button
    private lateinit var btnStartStop: Button

    // BLE
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val TARGET_DEVICE_NAME = "ELK-BLEDOB"
    private val WRITE_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val WRITE_CHAR_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"
    private var isConnected = false
    private var isScanning = false

    // Audio
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val SAMPLE_RATE = 44100
    private val BUFFER_SIZE = 4096

    // Handler for UI updates
    private val handler = Handler(Looper.getMainLooper())

    // Permission request code
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init UI
        tvStatus = findViewById(R.id.tvStatus)
        tvBass = findViewById(R.id.tvBass)
        tvMid = findViewById(R.id.tvMid)
        tvHigh = findViewById(R.id.tvHigh)
        colorPreview = findViewById(R.id.colorPreview)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStop = findViewById(R.id.btnStartStop)

        btnConnect.setOnClickListener { checkPermissionsAndScan() }
        btnStartStop.setOnClickListener { toggleSync() }
    }

    // ─── Permissions ────────────────────────────────────────────────

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBleScan()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else {
            toast("Permissions denied — app needs all permissions to work")
        }
    }

    // ─── BLE Scan ───────────────────────────────────────────────────

    private fun startBleScan() {
        if (isScanning) return
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter.bluetoothLeScanner
        updateStatus("Scanning... (30s)")
        isScanning = true
        btnConnect.isEnabled = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = if (ActivityCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) result.device.name else null

                if (name != null && name.trim().startsWith("ELK-")) {
                    scanner.stopScan(this)
                    isScanning = false
                    updateStatus("Found! Connecting...")
                    connectToDevice(result.device)
                }
            }
        }
        scanner.startScan(callback)

        // Stop scan after 10 seconds if not found
        handler.postDelayed({
            if (isScanning) {
                scanner.stopScan(object : ScanCallback() {})
                isScanning = false
                btnConnect.isEnabled = true
                updateStatus("Device not found. Try again.")
            }
        }, 30000)
    }

    // ─── BLE Connect ────────────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false
                    handler.post {
                        updateStatus("Disconnected")
                        btnStartStop.isEnabled = false
                        btnConnect.isEnabled = true
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(
                    java.util.UUID.fromString(WRITE_SERVICE_UUID)
                )
                writeCharacteristic = service?.getCharacteristic(
                    java.util.UUID.fromString(WRITE_CHAR_UUID)
                )
                if (writeCharacteristic != null) {
                    isConnected = true
                    handler.post {
                        updateStatus("Connected to ELK-BLEDOM ✓")
                        btnStartStop.isEnabled = true
                        btnConnect.isEnabled = false
                    }
                } else {
                    handler.post { updateStatus("Connected but service not found") }
                }
            }
        })
    }

    // ─── Send Color to Strip ─────────────────────────────────────────

    private fun sendColor(r: Int, g: Int, b: Int) {
        val characteristic = writeCharacteristic ?: return
        if (!isConnected) return
        val command = byteArrayOf(
            0x7e.toByte(), 0x00.toByte(), 0x05.toByte(), 0x03.toByte(),
            r.toByte(), g.toByte(), b.toByte(),
            0x00.toByte(), 0xef.toByte()
        )
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        characteristic.value = command
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        bluetoothGatt?.writeCharacteristic(characteristic)
    }

    // ─── Audio Sync ──────────────────────────────────────────────────

    private fun toggleSync() {
        if (isRunning) {
            stopSync()
        } else {
            startSync()
        }
    }

    private fun startSync() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE * 2
        )
        audioRecord?.startRecording()
        isRunning = true
        btnStartStop.text = "Stop Sync"

        Thread {
            val buffer = ShortArray(BUFFER_SIZE)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read > 0) {
                    processAudio(buffer, read)
                }
            }
        }.start()
    }

    private fun stopSync() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        btnStartStop.text = "Start Sync"
        sendColor(0, 0, 0)
        handler.post {
            tvBass.text = "BASS: 0"
            tvMid.text = "MID: 0"
            tvHigh.text = "HIGH: 0"
            colorPreview.setBackgroundResource(R.drawable.circle_shape)
        }
    }

    // ─── FFT & Color Mapping ─────────────────────────────────────────

    private fun processAudio(buffer: ShortArray, read: Int) {
        // Convert to float
        val samples = FloatArray(read) { buffer[it] / 32768f }

        // Simple energy calculation per frequency band
        // We split the buffer into thirds as a lightweight band approximation
        val third = read / 3

        val bassEnergy = rms(samples, 0, third)
        val midEnergy = rms(samples, third, third * 2)
        val highEnergy = rms(samples, third * 2, read)

        // Scale to 0-255
        val r = (bassEnergy * 800f).coerceIn(0f, 255f).toInt()
        val g = (midEnergy * 800f).coerceIn(0f, 255f).toInt()
        val b = (highEnergy * 800f).coerceIn(0f, 255f).toInt()

        // Send to strip
        sendColor(r, g, b)

        // Update UI
        handler.post {
            tvBass.text = "BASS: $r"
            tvMid.text = "MID: $g"
            tvHigh.text = "HIGH: $b"
            colorPreview.setBackgroundColor(Color.rgb(r, g, b))
        }
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) sum += samples[i] * samples[i]
        return sqrt(sum / (end - start))
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun updateStatus(msg: String) {
        handler.post { tvStatus.text = msg }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSync()
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.close()
        }
    }
}