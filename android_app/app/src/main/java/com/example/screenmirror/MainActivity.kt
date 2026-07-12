package com.example.screenmirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val TAG = "ScreenMirror"
    private lateinit var usbManager: UsbManager
    private var accessory: UsbAccessory? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val pfd = parcelFileDescriptor
            if (pfd != null) {
                // Start the Foreground Service to handle MediaProjection and USB writing
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                    // We must pass the FD to the service to write to the USB accessory
                    putExtra("fd", pfd.detachFd())
                }
                startForegroundService(intent)
                tvStatus.text = "Mirroring Active"
                btnStart.text = "Stop Mirroring"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener {
            if (btnStart.text == "Start Mirroring") {
                startMediaProjectionRequest()
            } else {
                stopMirroring()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkUsbAccessory()
    }

    private fun checkUsbAccessory() {
        val accessories = usbManager.accessoryList
        accessory = accessories?.firstOrNull()
        
        if (accessory != null) {
            if (usbManager.hasPermission(accessory)) {
                openAccessory(accessory!!)
            } else {
                tvStatus.text = "Permission required for USB Accessory"
                // Usually handled by intent filter automatically
            }
        } else {
            tvStatus.text = "Waiting for PC Connection..."
            btnStart.isEnabled = false
        }
    }

    private fun openAccessory(acc: UsbAccessory) {
        parcelFileDescriptor = usbManager.openAccessory(acc)
        if (parcelFileDescriptor != null) {
            tvStatus.text = "USB Accessory Connected"
            btnStart.isEnabled = true
        } else {
            tvStatus.text = "Failed to open USB Accessory"
            btnStart.isEnabled = false
        }
    }

    private fun startMediaProjectionRequest() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopMirroring() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)
        tvStatus.text = "USB Accessory Connected"
        btnStart.text = "Start Mirroring"
    }

    override fun onDestroy() {
        super.onDestroy()
        parcelFileDescriptor?.close()
    }
}
