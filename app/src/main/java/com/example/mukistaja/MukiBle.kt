package com.example.mukistaja

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

private const val TAG = "MukiBle"

private val MUKI_CHAR_UUID = UUID.fromString("06640002-9087-04a8-658f-ce44cb96b4a1")

data class BleDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice
) {
    val displayName: String get() = if (name.isNotBlank()) "$name  ($address)" else address
}

sealed class MukiEvent {
    data class Progress(val sent: Int, val total: Int) : MukiEvent()
    data class Error(val message: String) : MukiEvent()
    object Done : MukiEvent()
}

@SuppressLint("MissingPermission")
class MukiBle(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private val writeAck = Channel<Boolean>(Channel.CONFLATED)

    /**
     * Scan for BLE devices for [timeoutMs] milliseconds.
     * Returns all discovered devices sorted by signal strength (strongest first).
     */
    suspend fun scan(timeoutMs: Long = 10_000L): List<BleDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) return emptyList()

        val found = mutableMapOf<String, Pair<BleDevice, Int>>() // address -> (device, rssi)
        val scanner = adapter.bluetoothLeScanner

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                found[addr] = Pair(BleDevice(name, addr, result.device), result.rssi)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        delay(timeoutMs)
        scanner.stopScan(callback)

        return found.values
            .sortedByDescending { it.second } // strongest signal first
            .map { it.first }
    }

    /**
     * Connect to [device] and upload [imageData].
     * Emits MukiEvent via [onEvent].
     */
    suspend fun upload(device: BleDevice, imageData: ByteArray, onEvent: (MukiEvent) -> Unit) {
        val characteristic = connectAndGetChar(device.device) ?: run {
            onEvent(MukiEvent.Error("Could not connect or find Muki characteristic.\nIs this the right device?"))
            return
        }

        try {
            val total = 291
            writeChunk(characteristic, byteArrayOf(0x74.toByte()))

            for (i in 0 until total) {
                val start = i * 20
                val end = minOf(start + 20, imageData.size)
                val chunk = ByteArray(20) { j ->
                    if (start + j < end) imageData[start + j] else 0xFF.toByte()
                }
                writeChunk(characteristic, chunk)
                onEvent(MukiEvent.Progress(i + 1, total))
            }

            writeChunk(characteristic, byteArrayOf(0x64.toByte()))
            onEvent(MukiEvent.Done)
        } catch (e: Exception) {
            onEvent(MukiEvent.Error("Upload failed: ${e.message}"))
        } finally {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        }
    }

    // -------------------------------------------------------------------------

    private suspend fun connectAndGetChar(device: BluetoothDevice): BluetoothGattCharacteristic? {
        val charDeferred = CompletableDeferred<BluetoothGattCharacteristic?>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected, discovering services")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!charDeferred.isCompleted) charDeferred.complete(null)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val char = g.services
                    .flatMap { it.characteristics }
                    .firstOrNull { it.uuid == MUKI_CHAR_UUID }
                charDeferred.complete(char)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeAck.trySend(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return withTimeoutOrNull(15_000) { charDeferred.await() }
    }

    private suspend fun writeChunk(char: BluetoothGattCharacteristic, data: ByteArray) {
        val g = gatt ?: throw IllegalStateException("Not connected")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }

        withTimeoutOrNull(3000) { writeAck.receive() }
            ?: throw RuntimeException("Write timeout")
    }
}
