package com.webivation.grafit.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the BLE lifecycle for an R02 fitness ring: scanning, connecting,
 * enabling notifications, polling commands, and delivering decoded [RingMetric]
 * events via [metricChannel].
 *
 * Call [startScan] once BLE permissions are granted, then observe [connectionState]
 * and collect from [metricChannel].
 */
@SuppressLint("MissingPermission")
class R02BleManager(
    private val context: Context,
    /** Target device name prefix – user-configurable, defaults to "R02". */
    private val deviceName: String = "R02",
    /** How often (ms) to poll the ring for fresh readings. */
    private val pollIntervalMs: Long = 5_000L
) {
    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _connectionState = MutableStateFlow(State.DISCONNECTED)
    val connectionState: StateFlow<State> = _connectionState.asStateFlow()

    /** Decoded ring metrics. Collect from a coroutine scope. */
    val metricChannel = Channel<RingMetric>(capacity = 64)

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    fun startScan() {
        if (_connectionState.value != State.DISCONNECTED) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE not available")
            return
        }
        _connectionState.value = State.SCANNING
        Log.i(TAG, "Scanning for '$deviceName'…")

        val filter = ScanFilter.Builder().setDeviceName(deviceName).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)

        // Safety timeout: stop scanning after 30 s if nothing found
        handler.postDelayed({
            if (_connectionState.value == State.SCANNING) {
                Log.w(TAG, "Scan timeout – no '$deviceName' found")
                scanner.stopScan(scanCallback)
                _connectionState.value = State.DISCONNECTED
            }
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == State.SCANNING) {
            _connectionState.value = State.DISCONNECTED
        }
    }

    fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        _connectionState.value = State.DISCONNECTED
    }

    // -----------------------------------------------------------------------
    // Scan callback
    // -----------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "Found device: ${device.name} [${device.address}]")
            adapter?.bluetoothLeScanner?.stopScan(this)
            connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = State.DISCONNECTED
        }
    }

    // -----------------------------------------------------------------------
    // GATT connection
    // -----------------------------------------------------------------------

    private fun connectTo(device: BluetoothDevice) {
        _connectionState.value = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected – discovering services…")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    this@R02BleManager.gatt?.close()
                    this@R02BleManager.gatt = null
                    writeChar = null
                    _connectionState.value = State.DISCONNECTED
                    handler.removeCallbacksAndMessages(null)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(R02Protocol.SERVICE_UUID) ?: run {
                Log.e(TAG, "R02 service not found – check UUID")
                return
            }
            writeChar = service.getCharacteristic(R02Protocol.CHAR_WRITE_UUID)
            val notifyChar = service.getCharacteristic(R02Protocol.CHAR_NOTIFY_UUID)

            if (writeChar == null || notifyChar == null) {
                Log.e(TAG, "Required characteristics not found")
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(R02Protocol.CCCD_UUID)
            cccd?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            _connectionState.value = State.CONNECTED
            Log.i(TAG, "Connected to R02 – starting poll loop")
            schedulePoll()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == R02Protocol.CHAR_NOTIFY_UUID) {
                val metric = R02Protocol.parse(characteristic.value ?: return)
                if (metric.hasData()) {
                    metricChannel.trySend(metric)
                }
            }
        }

        @Deprecated("Deprecated in API 33 – kept for API < 33 compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == R02Protocol.CHAR_NOTIFY_UUID) {
                val metric = R02Protocol.parse(value)
                if (metric.hasData()) {
                    metricChannel.trySend(metric)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Poll loop
    // -----------------------------------------------------------------------

    private fun schedulePoll() {
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollRing()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollRing() {
        val g = gatt ?: return
        val wc = writeChar ?: return
        listOf(
            R02Protocol.CMD_GET_HEART_RATE,
            R02Protocol.CMD_GET_SPO2,
            R02Protocol.CMD_GET_STEPS,
            R02Protocol.CMD_GET_BATTERY
        ).forEach { cmd ->
            wc.value = cmd
            g.writeCharacteristic(wc)
            Thread.sleep(100) // small gap between commands
        }
    }

    companion object {
        private const val TAG = "R02BleManager"
        private const val SCAN_TIMEOUT_MS = 30_000L
    }
}
