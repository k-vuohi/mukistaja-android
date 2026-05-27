package com.example.mukistaja

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mukistaja.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mukiBle by lazy { MukiBle(this) }

    private var sourceBitmap: Bitmap? = null
    private var scannedDevices: List<BleDevice> = emptyList()
    private var selectedDevice: BleDevice? = null
    private var pendingAfterBle: (() -> Unit)? = null

    private var previewJob: Job? = null

    // -------------------------------------------------------------------------
    // Activity result launchers — must all be registered at construction time

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) pickImage() else setStatus("Permissions denied")
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingAfterBle?.invoke()
        } else {
            setStatus("Bluetooth permission denied")
        }
        pendingAfterBle = null
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val stream = contentResolver.openInputStream(uri) ?: return@launch
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    sourceBitmap = bmp
                    binding.cropView.setBitmap(bmp)
                    binding.btnRotate.isEnabled = true
                    updatePreview()
                    setStatus("Pan and pinch to frame your image.")
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickImage.setOnClickListener { requestPermissionsAndPick() }
        binding.btnRotate.setOnClickListener { binding.cropView.rotate90() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnSend.setOnClickListener { sendToMuki() }

        binding.listDevices.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = scannedDevices[position]
            updateSendButton()
            setStatus("Selected: ${selectedDevice!!.displayName}")
        }

        binding.cropView.onCropChanged = { scheduleLivePreview() }

        updateSendButton()
        binding.btnRotate.isEnabled = false
    }

    // -------------------------------------------------------------------------

    private fun scheduleLivePreview() {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(80)
            val cropped = binding.cropView.getCroppedBitmap() ?: return@launch
            val preview = withContext(Dispatchers.Default) {
                MukiImage.toPreviewBitmap(cropped)
            }
            binding.cropView.setPreview(preview)
        }
    }

    private fun updatePreview() = scheduleLivePreview()

    // -------------------------------------------------------------------------

    private fun startScan() {
        if (!hasBlePermissions()) {
            pendingAfterBle = { startScan() }
            requestBlePermissions()
            return
        }

        binding.btnScan.isEnabled = false
        binding.btnSend.isEnabled = false
        binding.listDevices.visibility = View.GONE
        binding.tvDeviceLabel.visibility = View.GONE
        scannedDevices = emptyList()
        selectedDevice = null
        setStatus("Scanning for BLE devices (10s)...")
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        lifecycleScope.launch {
            val devices = withContext(Dispatchers.IO) { mukiBle.scan(10_000) }

            binding.progressBar.isIndeterminate = false
            binding.progressBar.visibility = View.INVISIBLE
            binding.btnScan.isEnabled = true

            if (devices.isEmpty()) {
                setStatus("No BLE devices found. Make sure Bluetooth is on and the mug is warm.")
                return@launch
            }

            scannedDevices = devices
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_single_choice,
                devices.map { it.displayName }
            )
            binding.listDevices.adapter = adapter
            binding.listDevices.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
            binding.listDevices.visibility = View.VISIBLE
            binding.tvDeviceLabel.visibility = View.VISIBLE
            setStatus("Found ${devices.size} device(s). Tap yours to select it.")
        }
    }

    private fun sendToMuki() {
        val device = selectedDevice ?: return
        val cropped = binding.cropView.getCroppedBitmap() ?: run {
            setStatus("Pick an image first.")
            return
        }

        binding.btnSend.isEnabled = false
        binding.btnPickImage.isEnabled = false
        binding.btnRotate.isEnabled = false
        binding.btnScan.isEnabled = false
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val imageData = withContext(Dispatchers.Default) {
                    MukiImage.toBitArray(cropped)
                        ?: throw RuntimeException("Image processing failed")
                }

                setStatus("Connecting to ${device.displayName}...")

                mukiBle.upload(device, imageData) { event ->
                    when (event) {
                        is MukiEvent.Progress -> {
                            val pct = event.sent * 100 / event.total
                            runOnUiThread {
                                binding.progressBar.progress = pct
                                setStatus("Uploading... $pct%")
                            }
                        }
                        is MukiEvent.Error -> runOnUiThread { setStatus("Error: ${event.message}") }
                        MukiEvent.Done -> runOnUiThread { setStatus("Done! Image sent to Muki.") }
                    }
                }
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            } finally {
                runOnUiThread {
                    binding.btnPickImage.isEnabled = true
                    binding.btnRotate.isEnabled = sourceBitmap != null
                    binding.btnScan.isEnabled = true
                    updateSendButton()
                    binding.progressBar.visibility = View.INVISIBLE
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun requestPermissionsAndPick() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES))
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE))
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        addBlePermissions(needed)
        if (needed.isEmpty()) pickImage() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>().also { addBlePermissions(it) }
        if (needed.isNotEmpty()) blePermissionLauncher.launch(needed.toTypedArray())
    }

    private fun addBlePermissions(list: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) list.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasBlePermissions(): Boolean {
        val needed = mutableListOf<String>().also { addBlePermissions(it) }
        return needed.isEmpty()
    }

    private fun pickImage() = pickImageLauncher.launch("image/*")

    private fun updateSendButton() {
        binding.btnSend.isEnabled = sourceBitmap != null && selectedDevice != null
    }

    private fun setStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
