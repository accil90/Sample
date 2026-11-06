package com.aruna.domino

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aruna.domino.engine.GameSnapshot
import com.aruna.domino.engine.PassRecord
import com.aruna.domino.engine.Side
import com.aruna.domino.engine.StrategyEngine
import com.aruna.domino.engine.Tile

class MainActivity : Activity() {
    private val engine = StrategyEngine()
    private val allTiles = Tile.fullSet().sortedWith(compareBy<Tile> { it.low }.thenBy { it.high })
    private val hand = linkedSetOf<Tile>()
    private val played = linkedSetOf<Tile>()
    private val passes = mutableListOf<PassRecord>()
    private var opening: Tile? = null
    private var leftEnd = 0
    private var rightEnd = 0
    private var started = false
    private var pendingTile: Tile? = null
    private var pendingIsOwn = false
    private var currentPicker: ((Tile) -> Unit)? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var advice: TextView
    private lateinit var handSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ARUNA DOMINO PRO"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        showSetup()
    }

    private fun heading(text: String, size: Float = 20f) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 12, 0, 8)
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun clear() = root.removeAllViews()

    private fun showSetup() {
        started = false
        clear()
        root.addView(heading("ARUNA DOMINO PRO"))
        root.addView(heading("1. Pilih kartu pembuka", 17f))
        root.addView(TextView(this).apply { text = "Ketuk satu kartu yang menjadi pembuka di meja." })
        val openingGrid = tileGrid(allTiles, 4) { tile ->
            opening = tile
            hand.remove(tile)
            showSetup()
        }
        root.addView(openingGrid)
        root.addView(TextView(this).apply {
            text = "Pembuka terpilih: ${opening?.let { tileName(it) } ?: "belum dipilih"}"
            textSize = 16f
        })
        root.addView(heading("2. Pilih semua kartu di tanganmu", 17f))
        root.addView(TextView(this).apply {
            text = "Ketuk kartu untuk memilih atau membatalkan pilihan. Kartu pembuka tidak dapat dipilih sebagai kartu tangan."
        })
        val handGrid = tileGrid(allTiles.filter { it != opening && it !in played }, 4) { tile ->
            if (tile in hand) hand.remove(tile) else hand.add(tile)
            showSetup()
        }
        root.addView(handGrid)
        root.addView(TextView(this).apply {
            text = "Kartu tangan dipilih: ${hand.size}"
            textSize = 16f
        })
        root.addView(action("MULAI PERMAINAN") {
            val startTile = opening
            if (startTile == null) {
                statusMessage("Pilih kartu pembuka terlebih dahulu.")
            } else if (hand.isEmpty()) {
                statusMessage("Pilih kartu yang ada di tanganmu terlebih dahulu.")
            } else {
                played.clear()
                played.add(startTile)
                leftEnd = startTile.low
                rightEnd = startTile.high
                passes.clear()
                started = true
                showGame()
            }
        })
    }

    private fun showGame() {
        clear()
        root.addView(heading("ARUNA DOMINO PRO"))
        status = heading("Meja: kiri $leftEnd  |  kanan $rightEnd", 18f)
        root.addView(status)
        handSummary = TextView(this).apply { textSize = 15f }
        root.addView(handSummary)
        updateHandSummary()

        root.addView(action("SARAN KARTU SAYA") { analyze() })
        advice = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 12)
            text = "Tekan SARAN KARTU SAYA untuk melihat rekomendasi."
        }
        root.addView(advice)

        root.addView(heading("Kartu yang baru dimainkan lawan", 17f))
        root.addView(TextView(this).apply {
            text = "Pilih gambar kartu lawan. Jika cocok di kedua ujung, pilih KIRI atau KANAN."
        })
        root.addView(tileGrid(allTiles.filter { it !in played && it !in hand }, 4) { tile ->
            acceptPlayedTile(tile, false)
        })

        root.addView(heading("Kartu di tanganmu", 17f))
        root.addView(tileGrid(hand.toList(), 4) { tile ->
            acceptPlayedTile(tile, true)
        })
        root.addView(action("PASS — SAYA TIDAK BISA BERMAIN") {
            passes.add(PassRecord(0, leftEnd, rightEnd, played.size))
            advice.text = "PASS dicatat. Posisi meja tidak berubah."
        })
        root.addView(action("AKHIRI / ATUR ULANG PERMAINAN") {
            hand.clear()
            played.clear()
            opening = null
            showSetup()
        })
    }

    private fun acceptPlayedTile(tile: Tile, isOwn: Boolean) {
        if (tile in played) {
            statusMessage("Kartu itu sudah tercatat di meja.")
            return
        }
        if (isOwn && tile !in hand) return
        val leftFits = tile.contains(leftEnd)
        val rightFits = tile.contains(rightEnd)
        if (!leftFits && !rightFits) {
            statusMessage("Kartu itu tidak cocok dengan ujung meja saat ini.")
            return
        }
        pendingTile = tile
        pendingIsOwn = isOwn
        when {
            leftFits && rightFits -> showSideChoice(tile)
            leftFits -> commitTile(tile, Side.LEFT, isOwn)
            else -> commitTile(tile, Side.RIGHT, isOwn)
        }
    }

    private fun showSideChoice(tile: Tile) {
        clear()
        root.addView(heading("Pilih sisi pemasangan"))
        root.addView(TextView(this).apply {
            text = "Kartu ${tileName(tile)} bisa dipasang di kedua ujung meja."
            textSize = 17f
        })
        root.addView(action("KIRI  (ujung $leftEnd)") { commitTile(tile, Side.LEFT, pendingIsOwn) })
        root.addView(action("KANAN  (ujung $rightEnd)") { commitTile(tile, Side.RIGHT, pendingIsOwn) })
        root.addView(action("BATAL") { showGame() })
    }

    private fun commitTile(tile: Tile, side: Side, isOwn: Boolean) {
        val exposed = if (side == Side.LEFT) leftEnd else rightEnd
        val newEnd = tile.otherEnd(exposed) ?: exposed
        if (side == Side.LEFT) leftEnd = newEnd else rightEnd = newEnd
        played.add(tile)
        if (isOwn) hand.remove(tile)
        pendingTile = null
        showGame()
        advice.text = if (isOwn) {
            "Kartu ${tileName(tile)} tercatat dimainkan di ${side.name}. Sisa kartu: ${hand.size}."
        } else {
            "Lawan memainkan ${tileName(tile)} di ${side.name}. Posisi meja diperbarui."
        }
    }

    private fun analyze() {
        try {
            val result = engine.analyze(
                GameSnapshot(
                    hand = hand.toList(),
                    played = played.toSet(),
                    leftEnd = leftEnd,
                    rightEnd = rightEnd,
                    passes = passes.toList()
                )
            )
            val best = result.best
            advice.text = if (best == null) {
                "Tidak ada kartu legal. Pertimbangkan PASS."
            } else {
                buildString {
                    append("SARAN UTAMA: ${tileName(best.move.tile)} ke ${best.move.side.name}\n")
                    append("Skor strategi: ${"%.2f".format(best.score)}\n")
                    append("Tekanan lawan (estimasi): ${"%.0f".format(best.opponentPressure * 100)}%\n")
                    append("Peluang bisa bermain lagi (estimasi): ${"%.0f".format(best.futurePlayChance * 100)}%")
                    result.alternatives.take(3).forEachIndexed { index, option ->
                        append("\nAlternatif ${index + 1}: ${tileName(option.move.tile)} — ${option.move.side.name}")
                    }
                    append("\n\nPrediksi adalah perkiraan, bukan kepastian kartu lawan.")
                }
            }
        } catch (e: Exception) {
            advice.text = "Analisis belum dapat dilakukan: ${e.message}"
        }
    }

    private fun updateHandSummary() {
        handSummary.text = "Kartu tersisa: ${hand.size}  |  ${hand.sortedWith(compareBy<Tile> { it.low }.thenBy { it.high }).joinToString("  ") { tileName(it) }}"
    }

    private fun tileGrid(tiles: List<Tile>, columns: Int, onSelect: (Tile) -> Unit): GridLayout {
        return GridLayout(this).apply {
            columnCount = columns
            tiles.forEach { tile ->
                val face = TileFaceView(tile).apply {
                    contentDescription = tileName(tile)
                    setOnClickListener { onSelect(tile) }
                }
                addView(face, ViewGroup.LayoutParams(dp(68), dp(48)))
            }
        }
    }

    private fun tileName(tile: Tile) = "${tile.low}|${tile.high}"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun statusMessage(message: String) {
        if (started) {
            advice.text = message
        } else {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private inner class TileFaceView(private val tile: Tile) : View(this@MainActivity) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF7.toInt(); style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF171717.toInt(); style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF777777.toInt(); strokeWidth = 1.5f }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = RectF(3f, 3f, width - 3f, height - 3f)
            canvas.drawRoundRect(r, 7f, 7f, fill)
            canvas.drawRoundRect(r, 7f, 7f, border)
            canvas.drawLine(width / 2f, 6f, width / 2f, height - 6f, line)
            drawPips(canvas, tile.low, width * 0.25f, height * 0.5f)
            drawPips(canvas, tile.high, width * 0.75f, height * 0.5f)
        }

        private fun drawPips(canvas: Canvas, value: Int, cx: Float, cy: Float) {
            if (value == 0) return
            val d = minOf(width, height) * 0.085f
            val offsets = when (value) {
                1 -> listOf(0f to 0f)
                2 -> listOf(-1f to -1f, 1f to 1f)
                3 -> listOf(-1f to -1f, 0f to 0f, 1f to 1f)
                4 -> listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
                5 -> listOf(-1f to -1f, 1f to -1f, 0f to 0f, -1f to 1f, 1f to 1f)
                else -> listOf(-1f to -1f, 1f to -1f, -1f to 0f, 1f to 0f, -1f to 1f, 1f to 1f)
            }
            offsets.forEach { (x, y) -> canvas.drawCircle(cx + x * d * 1.8f, cy + y * d * 1.8f, d, dot) }
        }
    }
}
