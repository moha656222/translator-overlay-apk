package com.tuapp.translatoroverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

object CaptureManager {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val captureHandler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null

    const val CHANNEL_ID = "capture_service"
    const val NOTIFICATION_ID = 1001

    fun initProjection(context: Context, resultCode: Int, data: Intent) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        val displayMetrics = context.resources.displayMetrics
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    fun startPeriodicCapture(onBitmapReady: (Bitmap) -> Unit) {
        captureRunnable = object : Runnable {
            override fun run() {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width

                        val bitmap = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        onBitmapReady(bitmap)
                    } finally {
                        image.close()
                    }
                }
                captureHandler.postDelayed(this, 2500)
            }
        }
        captureHandler.post(captureRunnable!!)
    }

    fun stopCapture() {
        captureHandler.removeCallbacksAndMessages(null)
        imageReader?.close()
        mediaProjection?.stop()
        imageReader = null
        mediaProjection = null
    }

    fun buildForegroundNotification(context: Context): android.app.Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura de Pantalla",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Traductor Activo")
            .setContentText("Capturando pantalla para traducción en tiempo real")
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setOngoing(true)
            .build()
    }
}
