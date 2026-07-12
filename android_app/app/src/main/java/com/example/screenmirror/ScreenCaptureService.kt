package com.example.screenmirror

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
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ScreenCaptureService : Service() {

    private val TAG = "ScreenCaptureService"
    private val CHANNEL_ID = "ScreenMirrorChannel"
    private val NOTIFICATION_ID = 1

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null

    private var isStreaming = false
    private var usbOutputStream: FileOutputStream? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val resultData = intent.getParcelableExtra<Intent>("resultData")
            val fd = intent.getIntExtra("fd", -1)

            if (resultCode != -1 && resultData != null && fd != -1) {
                startStreaming(resultCode, resultData, fd)
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, fdInt: Int) {
        val pfd = ParcelFileDescriptor.adoptFd(fdInt)
        usbOutputStream = FileOutputStream(pfd.fileDescriptor)
        
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupMediaCodec()
        
        isStreaming = true
        startEncodingThread()
    }

    private fun setupMediaCodec() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        // Scale down to 720p or 1080p for stable USB streaming
        val width = 720
        val height = 1280
        val dpi = metrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 I-frame per second

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        val surface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        mediaProjection?.let {
            virtualDisplay = it.createVirtualDisplay(
                "ScreenMirrorDisplay",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        }
    }

    private fun startEncodingThread() {
        thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val codec = mediaCodec ?: return@thread
            val outputStream = usbOutputStream ?: return@thread

            while (isStreaming) {
                try {
                    val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            // Simple packet framing for USB: [MAGIC (4)] [SIZE (4)] [DATA (N)]
                            val header = ByteBuffer.allocate(8)
                            header.putInt(0xDEADBEEF.toInt()) // Magic
                            header.putInt(data.size)         // Size
                            
                            outputStream.write(header.array())
                            outputStream.write(data)
                            outputStream.flush()
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoding error", e)
                    break
                }
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        usbOutputStream?.close()
        virtualDisplay?.release()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaProjection?.stop()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Streaming over USB Accessory")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
