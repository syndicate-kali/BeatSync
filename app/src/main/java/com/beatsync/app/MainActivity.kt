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
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.jtransforms.fft.FloatFFT_1D
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
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivity: TextView

    // BLE
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val WRITE_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val WRITE_CHAR_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"
    private var isConnected = false
    private var isScanning = false

    // Audio
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val SAMPLE_RATE = 44100
    private val BUFFER_SIZE = 4096

    // FFT
    private val fft = FloatFFT_1D(BUFFER_SIZE.toLong())

    // Sensitivity (1-10, default 5)
    private var sensitivity = 5f

    // Handler for UI updates
    private val handler = Handler(Looper.getMainLooper())

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBass = findViewById(R.id.tvBass)
        tvMid = findViewById(R.id.tvMid)
        tvHigh = findViewById(R.id.tvHigh)
        colorPreview = findViewById(R.id.colorPreview)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStop = findViewById(R.id.btnStartStop)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivity = findViewById(R.id.tvSensitivity)

        btnConnect.setOnClickListener { checkPermissionsAndScan() }
        btnStartStop.setOnClickListener { toggleSync() }

        seekBarSensitivity.max = 9
        seekBarSensitivity.progress = 4
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                sensitivity = (progress + 1).toFloat()
                tvSensitivity.text = "Sensitivity: ${(progress + 1)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
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
                val service = gatt.getService(java.util.UUID.fromString(WRITE_SERVICE_UUID))
                writeCharacteristic = service?.getCharacteristic(
                    java.util.UUID.fromString(WRITE_CHAR_UUID)
                )
                if (writeCharacteristic != null) {
                    isConnected = true
                    handler.post {
                        updateStatus("Connected ✓")
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
        if (isRunning) stopSync() else startSync()
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
                if (read > 0) processAudio(buffer, read)
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

    // ─── True FFT Analysis ───────────────────────────────────────────

    private fun processAudio(buffer: ShortArray, read: Int) {
        // Convert shorts to float array for FFT
        val fftBuffer = FloatArray(BUFFER_SIZE * 2)
        for (i in 0 until read) {
            fftBuffer[i] = buffer[i] / 32768f
        }

        // Run FFT
        fft.realForwardFull(fftBuffer)

        // Each FFT bin = SAMPLE_RATE / BUFFER_SIZE = ~10.8 Hz per bin
        val hzPerBin = SAMPLE_RATE.toFloat() / BUFFER_SIZE

        // Define frequency band ranges in bins
        val bassEnd = (250f / hzPerBin).toInt()        // 0 - 250 Hz
        val midEnd = (4000f / hzPerBin).toInt()        // 250 - 4000 Hz
        val highEnd = (BUFFER_SIZE / 2)                // 4000 - 20000 Hz

        // Calculate energy in each band
        val bassEnergy = bandEnergy(fftBuffer, 1, bassEnd)
        val midEnergy = bandEnergy(fftBuffer, bassEnd, midEnd)
        val highEnergy = bandEnergy(fftBuffer, midEnd, highEnd)

        // Apply sensitivity and scale to 0-255
        val scale = sensitivity * 150f
        val r = (bassEnergy * scale).coerceIn(0f, 255f).toInt()
        val g = (midEnergy * scale).coerceIn(0f, 255f).toInt()
        val b = (highEnergy * scale).coerceIn(0f, 255f).toInt()

        sendColor(r, g, b)

        handler.post {
            tvBass.text = "BASS: $r"
            tvMid.text = "MID: $g"
            tvHigh.text = "HIGH: $b"
            colorPreview.setBackgroundColor(Color.rgb(r, g, b))
        }
    }

    private fun bandEnergy(fftBuffer: FloatArray, startBin: Int, endBin: Int): Float {
        var sum = 0f
        for (i in startBin until endBin) {
            val real = fftBuffer[2 * i]
            val imag = fftBuffer[2 * i + 1]
            sum += sqrt(real * real + imag * imag)
        }
        return sum / (endBin - startBin)
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
        ) bluetoothGatt?.close()
    }
}