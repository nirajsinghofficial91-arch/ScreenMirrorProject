package com.example.screenmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenMirrorChannel"
        private const val NOTIFICATION_ID = 1
        private const val PACKET_MAGIC = 0xDEADBEEF.toInt()
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile
    private var isStreaming = false
    private var usbOutputStream: FileOutputStream? = null
    private var encodingThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("resultData")
        }
        val fd = intent.getIntExtra("fd", -1)

        if (resultCode == -1 || resultData == null || fd == -1) {
            Log.e(TAG, "Invalid start parameters: resultCode=$resultCode, resultData=$resultData, fd=$fd")
            stopSelf()
            return START_NOT_STICKY
        }

        startStreaming(resultCode, resultData, fd)
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, fdInt: Int) {
        try {
            val pfd = ParcelFileDescriptor.adoptFd(fdInt)
            usbOutputStream = FileOutputStream(pfd.fileDescriptor)

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            // Register a callback to know when projection stops
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopStreaming()
                    stopSelf()
                }
            }, null)

            setupMediaCodec()

            isStreaming = true
            startEncodingThread()
            Log.i(TAG, "Streaming started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            stopSelf()
        }
    }

    private fun setupMediaCodec() {
        // Get actual screen dimensions
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        // Use a fixed resolution for consistent streaming
        val width = 720
        val height = 1280
        val dpi = resources.displayMetrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4 Mbps — good quality for USB
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 keyframe per second for recovery
            // Set profile and level for broad compatibility
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        }

        // Create VirtualDisplay that renders to the encoder's input surface
        mediaProjection?.let { projection ->
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenMirrorDisplay",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        }
    }

    private fun startEncodingThread() {
        encodingThread = thread(name = "H264EncoderThread") {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = mediaCodec ?: return@thread
            val outputStream = usbOutputStream ?: return@thread

            // Pre-allocate header buffer
            val headerBuffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)

            Log.i(TAG, "Encoding thread started")

            while (isStreaming) {
                try {
                    val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout
                    when {
                        outputBufferId >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Read the encoded H.264 data
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(data)

                                // Write packet: [MAGIC(4)] [SIZE(4)] [H.264 DATA(N)]
                                headerBuffer.clear()
                                headerBuffer.putInt(PACKET_MAGIC)
                                headerBuffer.putInt(data.size)

                                outputStream.write(headerBuffer.array())
                                outputStream.write(data)
                                // Don't flush every frame — too expensive. Let the OS buffer.
                            }
                            codec.releaseOutputBuffer(outputBufferId, false)
                        }
                        outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.i(TAG, "Encoder output format changed: $newFormat")
                        }
                        // INFO_TRY_AGAIN_LATER → just loop again
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Codec in bad state", e)
                    break
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "USB write error — cable disconnected?", e)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Encoding error", e)
                    break
                }
            }

            Log.i(TAG, "Encoding thread stopped")
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        encodingThread?.join(2000) // Wait up to 2 seconds for the thread to exit

        try { usbOutputStream?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}

        usbOutputStream = null
        virtualDisplay = null
        inputSurface = null
        mediaCodec = null
        mediaProjection = null
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Mirror Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps screen mirroring running in the background"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Streaming over USB Accessory")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
