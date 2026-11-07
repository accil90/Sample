package com.aruna.domino

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

class ScreenRecordService : Service() {
    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var outputPath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            startForegroundCompat()
            try {
                beginRecording(intent)
            } catch (e: Exception) {
                sendResult(null, "Не удалось начать запись: ${e.message}")
                finishRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "aruna_screen_record"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "ARUNA screen recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("ARUNA DOMINO PRO")
            .setContentText("Pengamatan layar sedang merekam")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(41, notification)
        }
    }

    private fun beginRecording(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: throw IllegalArgumentException("Izin tangkap layar tidak tersedia")

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection tidak tersedia")

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val folder = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ARUNA_DOMINO_OBSERVER")
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, "ARUNA_DOMINO_${System.currentTimeMillis()}.mp4")
        outputPath = file.absolutePath

        val videoRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = videoRecorder
        videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        videoRecorder.setVideoEncodingBitRate(5_000_000)
        videoRecorder.setVideoFrameRate(24)
        videoRecorder.setVideoSize(width, height)
        videoRecorder.setOutputFile(file.absolutePath)
        videoRecorder.prepare()

        display = projection!!.createVirtualDisplay(
            "ARUNA-DOMINO-RECORD", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoRecorder.surface, null, null
        )
        videoRecorder.start()
    }

    private fun finishRecording() {
        try {
            recorder?.stop()
            sendResult(outputPath, null)
        } catch (e: Exception) {
            outputPath?.let { File(it).delete() }
            sendResult(null, "Rekaman terlalu singkat atau gagal disimpan.")
        } finally {
            try { display?.release() } catch (_: Exception) {}
            display = null
            try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun sendResult(path: String?, error: String?) {
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).apply {
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_ERROR, error)
        })
    }

    override fun onDestroy() {
        if (recorder != null || projection != null) finishRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aruna.domino.RECORD_START"
        const val ACTION_STOP = "com.aruna.domino.RECORD_STOP"
        const val ACTION_FINISHED = "com.aruna.domino.RECORD_FINISHED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PATH = "recording_path"
        const val EXTRA_ERROR = "recording_error"
    }
}
