package com.example.screenmirror

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenMirror"
    }

    private lateinit var usbManager: UsbManager
    private var accessory: UsbAccessory? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var isMirroring = false

    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    // BroadcastReceiver to detect USB accessory attach/detach at runtime
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Log.i(TAG, "USB Accessory attached")
                    checkUsbAccessory()
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.i(TAG, "USB Accessory detached")
                    if (isMirroring) {
                        stopMirroring()
                    }
                    accessory = null
                    parcelFileDescriptor?.close()
                    parcelFileDescriptor = null
                    tvStatus.text = "USB Disconnected"
                    btnStart.isEnabled = false
                }
            }
        }
    }

    // Launcher for MediaProjection permission request
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val pfd = parcelFileDescriptor
            if (pfd != null) {
                // Start the Foreground Service to handle MediaProjection and USB writing
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                    // Pass the file descriptor integer to the service
                    putExtra("fd", pfd.detachFd())
                }
                startForegroundService(serviceIntent)
                isMirroring = true
                tvStatus.text = "🔴 Mirroring Active"
                btnStart.text = "Stop Mirroring"
            } else {
                Toast.makeText(this, "USB connection lost. Please reconnect.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener {
            if (!isMirroring) {
                startMediaProjectionRequest()
            } else {
                stopMirroring()
            }
        }

        // Register USB broadcast receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Handle intent if the app was launched by USB accessory being plugged in
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            checkUsbAccessory()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isMirroring) {
            checkUsbAccessory()
        }
    }

    private fun checkUsbAccessory() {
        val accessories = usbManager.accessoryList
        accessory = accessories?.firstOrNull()

        if (accessory != null) {
            if (usbManager.hasPermission(accessory)) {
                openAccessory(accessory!!)
            } else {
                tvStatus.text = "Tap 'OK' on the USB permission dialog..."
                // The intent-filter in the manifest should auto-grant permission.
                // If not, we would need to use requestPermission() with a PendingIntent.
            }
        } else {
            tvStatus.text = "Waiting for PC Connection...\nPlug USB cable and run the PC app"
            btnStart.isEnabled = false
        }
    }

    private fun openAccessory(acc: UsbAccessory) {
        parcelFileDescriptor = usbManager.openAccessory(acc)
        if (parcelFileDescriptor != null) {
            tvStatus.text = "✅ USB Accessory Connected\nReady to mirror!"
            btnStart.isEnabled = true
        } else {
            tvStatus.text = "❌ Failed to open USB Accessory"
            btnStart.isEnabled = false
            Log.e(TAG, "openAccessory returned null")
        }
    }

    private fun startMediaProjectionRequest() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopMirroring() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        isMirroring = false
        tvStatus.text = "✅ USB Accessory Connected\nReady to mirror!"
        btnStart.text = "Start Mirroring"
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle USB attach when app is already running
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            checkUsbAccessory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) { }
        parcelFileDescriptor?.close()
    }
}
