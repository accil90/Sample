package com.aruna.domino

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aruna.domino.engine.GameSnapshot
import com.aruna.domino.engine.StrategyEngine
import com.aruna.domino.engine.Tile

class MainActivity : Activity() {
    private val engine = StrategyEngine()
    private lateinit var leftInput: EditText
    private lateinit var rightInput: EditText
    private lateinit var handInput: EditText
    private lateinit var playedInput: EditText
    private lateinit var countsInput: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ARUNA PRO"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        content.addView(TextView(this).apply {
            text = "ARUNA PRO\nAsisten strategi Gaple offline"
            textSize = 24f
        })
        content.addView(label("Ujung kiri meja (0–6)"))
        leftInput = field("6")
        content.addView(leftInput)
        content.addView(label("Ujung kanan meja (0–6)"))
        rightInput = field("6")
        content.addView(rightInput)
        content.addView(label("Kartu di tangan (contoh: 6-6, 6-3, 2-4)"))
        handInput = field("6-6, 6-3, 2-4")
        content.addView(handInput)
        content.addView(label("Kartu yang sudah turun (opsional, contoh: 0-0, 1-2)"))
        playedInput = field("")
        content.addView(playedInput)
        content.addView(label("Jumlah kartu lawan, urut ID 1,2,3 (opsional, contoh: 7,7,7)"))
        countsInput = field("")
        content.addView(countsInput)

        content.addView(Button(this).apply {
            text = "ANALISIS LANGKAH"
            setOnClickListener { analyzePosition() }
        })
        resultText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 12)
            text = "Masukkan posisi meja dan kartu di tangan, lalu tekan ANALISIS LANGKAH."
        }
        content.addView(resultText)
        setContentView(scroll)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setPadding(0, 14, 0, 4)
    }

    private fun field(value: String) = EditText(this).apply {
        setSingleLine(true)
        setText(value)
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun parseTiles(raw: String): List<Tile> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';').map { token ->
            val parts = token.trim().split('-', '/', ':')
            require(parts.size == 2) { "Format kartu salah: $token. Gunakan angka-angka, misalnya 6-3." }
            Tile.of(parts[0].trim().toInt(), parts[1].trim().toInt())
        }
    }

    private fun analyzePosition() {
        try {
            val left = leftInput.text.toString().trim().toInt()
            val right = rightInput.text.toString().trim().toInt()
            val hand = parseTiles(handInput.text.toString())
            require(hand.isNotEmpty()) { "Masukkan setidaknya satu kartu di tangan." }
            val played = parseTiles(playedInput.text.toString()).toSet()
            val counts = countsInput.text.toString().trim().takeIf { it.isNotEmpty() }
                ?.split(',', ';')
                ?.map { it.trim().toInt() }
                ?.mapIndexed { index, count -> (index + 1) to count }
                ?.toMap()
                ?: emptyMap()

            val result = engine.analyze(
                GameSnapshot(
                    hand = hand,
                    played = played,
                    leftEnd = left,
                    rightEnd = right,
                    playerHandCounts = counts
                )
            )
            val best = result.best
            resultText.text = buildString {
                append("HASIL ANALISIS\n")
                if (best == null) {
                    append("Tidak ada kartu legal. Kemungkinan harus lewat.")
                } else {
                    append("SARAN UTAMA: ${best.move.tile.low}-${best.move.tile.high}")
                    append(" ke sisi ${best.move.side.name}\n")
                    append("Skor: ${"%.2f".format(best.score)}")
                    append(" | Tekanan lawan: ${"%.0f".format(best.opponentPressure * 100)}%")
                    append(" | Peluang bisa bermain lagi: ${"%.0f".format(best.futurePlayChance * 100)}%\n")
                    result.alternatives.forEachIndexed { index, option ->
                        append("\nAlternatif ${index + 1}: ${option.move.tile.low}-${option.move.tile.high}")
                        append(" (${option.move.side.name}), skor ${"%.2f".format(option.score)}")
                    }
                }
                append("\n\nKartu legal: ${result.legalMoves.size}")
                append("\nPrediksi adalah estimasi, bukan kepastian kartu lawan.")
            }
        } catch (error: Exception) {
            resultText.text = "Periksa masukan: ${error.message ?: "data tidak valid"}"
        }
    }
}
