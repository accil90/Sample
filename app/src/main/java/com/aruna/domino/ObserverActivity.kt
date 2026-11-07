package com.aruna.domino

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ObserverActivity : Activity() {
    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private var lastVideoPath: String? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val error = intent?.getStringExtra(ScreenRecordService.EXTRA_ERROR)
            val path = intent?.getStringExtra(ScreenRecordService.EXTRA_PATH)
            if (error != null) {
                statusView.text = error
                return
            }
            if (path != null) {
                lastVideoPath = path
                statusView.text = "Rekaman tersimpan. Sedang membuat laporan teks..."
                analyzeRecording(path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Pengamat Layar ARUNA"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        statusView = TextView(this).apply {
            text = "Rekam layar permainan. Setelah dihentikan, ARUNA membuat laporan piksel dan posisi visual sebagai bahan kalibrasi."
            textSize = 15f
        }
        reportView = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        root.addView(statusView)
        root.addView(makeButton("MULAI REKAM LAYAR") { requestCapture() })
        root.addView(makeButton("HENTIKAN & ANALISIS") { stopCapture() })
        root.addView(makeButton("ANALISIS ULANG REKAMAN TERAKHIR") {
            lastVideoPath?.let { analyzeRecording(it) }
                ?: Toast.makeText(this, "Belum ada rekaman.", Toast.LENGTH_SHORT).show()
        })
        root.addView(makeButton("SALIN LAPORAN TEKS") {
            val text = reportView.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Laporan belum tersedia.", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ARUNA screen analysis", text))
                Toast.makeText(this, "Laporan disalin. Kirim teks ini ke Kakak.", Toast.LENGTH_LONG).show()
            }
        })
        val scroll = ScrollView(this).apply { addView(reportView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val filter = IntentFilter(ScreenRecordService.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Legacy activity result used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            statusView.text = "Merekam layar. Pindah ke permainan; hentikan rekaman dari notifikasi atau buka kembali ARUNA untuk menekan HENTIKAN."
        } else {
            statusView.text = "Izin perekaman layar dibatalkan."
        }
    }

    private fun stopCapture() {
        startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
        statusView.text = "Memproses penghentian rekaman..."
    }

    private fun analyzeRecording(path: String) {
        Thread {
            val result = buildString {
                appendLine("ARUNA DOMINO PRO — LAPORAN PENGAMATAN LAYAR")
                appendLine("File: ${File(path).name}")
                appendLine("Catatan: ini peta luminansi sampel, bukan pembacaan nilai kartu otomatis.")
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                    appendLine("Resolusi: ${width}x${height}; durasi: ${duration} ms")
                    val stamps = listOf(0L, duration / 2, (duration - 500).coerceAtLeast(0L)).distinct()
                    stamps.forEachIndexed { index, ms ->
                        val bitmap = retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        appendLine()
                        appendLine("FRAME ${index + 1} @ ${ms} ms")
                        if (bitmap == null) {
                            appendLine("[frame tidak dapat dibaca]")
                        } else {
                            val sampleW = 24
                            val sampleH = 12
                            val chars = " .:-=+*#%@"
                            for (y in 0 until sampleH) {
                                val line = StringBuilder()
                                for (x in 0 until sampleW) {
                                    val px = (x * bitmap.width / sampleW).coerceIn(0, bitmap.width - 1)
                                    val py = (y * bitmap.height / sampleH).coerceIn(0, bitmap.height - 1)
                                    val color = bitmap.getPixel(px, py)
                                    val r = (color shr 16) and 255
                                    val g = (color shr 8) and 255
                                    val b = color and 255
                                    val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                                    val charIndex = (luminance * (chars.length - 1) / 255).coerceIn(0, chars.length - 1)
                                    line.append(chars[charIndex])
                                }
                                appendLine(line.toString())
                            }
                            appendLine("Pita bawah (area kartu tangan):")
                            val bandRows = 6
                            for (y in 0 until bandRows) {
                                val line = StringBuilder()
                                for (x in 0 until sampleW) {
                                    val px = (x * bitmap.width / sampleW).coerceIn(0, bitmap.width - 1)
                                    val py = (bitmap.height * 55 / 100 + y * (bitmap.height * 45 / 100) / bandRows)
                                        .coerceIn(0, bitmap.height - 1)
                                    val c = bitmap.getPixel(px, py)
                                    val lum = (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)).toInt()
                                    line.append(chars[(lum * (chars.length - 1) / 255).coerceIn(0, chars.length - 1)])
                                }
                                appendLine(line.toString())
                            }
                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    appendLine("Gagal membaca video: ${e.message}")
                } finally {
                    retriever.release()
                }
                appendLine()
                appendLine("Kirim laporan ini bersama screenshot satu frame yang menampilkan kartu tangan dan meja untuk kalibrasi deteksi.")
            }
            runOnUiThread {
                reportView.text = result
                statusView.text = "Analisis teks selesai. Tekan SALIN LAPORAN TEKS untuk mengirim hasil."
            }
        }.start()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object { private const val REQUEST_CAPTURE = 7201 }
}
