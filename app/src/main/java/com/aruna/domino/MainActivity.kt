package com.aruna.domino

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private val mySeat = 1
    private var openingSeat = 3
    private var currentTurn = 4
    private var leftEnd = 0
    private var rightEnd = 0
    private var started = false
    private var pendingTile: Tile? = null
    private var pendingIsOwn = false
    private var currentPicker: ((Tile) -> Unit)? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var advice: TextView
    private lateinit var recommendationTiles: LinearLayout
    private lateinit var handSummary: TextView

    private val red = Color.rgb(190, 35, 55)
    private val darkRed = Color.rgb(130, 20, 38)
    private val pale = Color.rgb(255, 244, 245)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ARUNA DOMINO PRO"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setBackgroundColor(Color.rgb(250, 247, 247))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(250, 247, 247))
            addView(root)
        })
        showSetup()
    }

    private fun heading(text: String, size: Float = 20f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(darkRed)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(red)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun clear() = root.removeAllViews()

    private fun showSetup() {
        started = false
        clear()

        root.addView(heading("PILIH PEMBUKA", 21f))
        root.addView(TextView(this).apply {
            text = "Meja berapa yang menurunkan kartu pertama?"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(8))
        })
        (1..4).forEach { seat ->
            root.addView(action(if (openingSeat == seat) "✓ MEJA $seat" else "MEJA $seat") {
                openingSeat = seat
                showSetup()
            })
        }

        root.addView(heading("KARTU PEMBUKA", 18f))
        root.addView(TextView(this).apply {
            text = "Pilih kartu yang pertama kali diturunkan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        root.addView(tileGrid(allTiles, 4) { tile ->
            opening = tile
            hand.remove(tile)
            showSetup()
        })
        root.addView(TextView(this).apply {
            text = "Kartu pembuka: ${opening?.let { tileName(it) } ?: "belum dipilih"}"
            textSize = 15f
            setTextColor(darkRed)
            setPadding(0, dp(6), 0, dp(4))
        })

        root.addView(heading("KARTU DI TANGAN SAYA", 18f))
        root.addView(TextView(this).apply {
            text = "Pilih semua kartu yang kamu pegang. Ketuk lagi untuk membatalkan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        root.addView(tileGrid(allTiles.filter { it != opening }, 4) { tile ->
            if (tile in hand) hand.remove(tile) else hand.add(tile)
            showSetup()
        })
        root.addView(TextView(this).apply {
            text = "Kartu dipilih: ${hand.size}"
            textSize = 15f
            setTextColor(darkRed)
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(action("MULAI PERMAINAN") {
            val startTile = opening
            if (startTile == null) {
                statusMessage("Pilih kartu pembuka terlebih dahulu.")
            } else if (hand.isEmpty()) {
                statusMessage("Pilih kartu yang ada di tanganmu terlebih dahulu.")
            } else if (startTile in hand) {
                statusMessage("Kartu pembuka tidak boleh dipilih sebagai kartu tangan.")
            } else {
                played.clear()
                played.add(startTile)
                leftEnd = startTile.low
                rightEnd = startTile.high
                passes.clear()
                currentTurn = nextSeat(openingSeat)
                started = true
                showGame()
            }
        })
    }

    private fun showGame() {
        clear()
        root.addView(heading("ARUNA DOMINO PRO", 23f))
        status = heading("Ujung meja: kiri $leftEnd  |  kanan $rightEnd", 18f)
        root.addView(status)
        root.addView(heading("Giliran: MEJA $currentTurn" + if (currentTurn == mySeat) " (SAYA)" else " (LAWAN)", 17f))
        handSummary = TextView(this).apply {
            textSize = 15f
            setTextColor(darkRed)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(handSummary)
        updateHandSummary()
        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "Posisi saya: Meja $mySeat | Pembuka: Meja $openingSeat\nRiwayat kartu: " + played.joinToString("  ") { tileName(it) } + "\nRiwayat PASS: " + passes.size
            setPadding(0, dp(8), 0, dp(8))
        })

        // Separate side-by-side panels: hand left, visual suggestions right.
        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
        }
        val handPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = roundedBackground(Color.WHITE, 10)
        }
        handPanel.addView(heading("KARTU SAYA", 15f))
        handPanel.addView(tileGridSmall(hand.toList(), 3) { })
        val suggestionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = roundedBackground(pale, 10)
        }
        suggestionPanel.addView(heading("SARAN KARTU", 15f))
        recommendationTiles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        recommendationTiles.addView(TextView(this).apply {
            text = "Menghitung saran kartu..."
            textSize = 12f
            setTextColor(darkRed)
        })
        suggestionPanel.addView(recommendationTiles)
        cardsRow.addView(handPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardsRow.addView(suggestionPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(cardsRow)

        advice = TextView(this).apply {
            textSize = 13f
            setTextColor(darkRed)
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = roundedBackground(pale, 10)
            text = "Hasil analisis dan alasan rekomendasi akan muncul di sini."
        }
        root.addView(advice)

        root.addView(heading("Kartu yang dimainkan lawan", 17f))
        root.addView(TextView(this).apply {
            text = "Pilih kartu yang baru saja diturunkan lawan."
            textSize = 14f
        })
        root.addView(tileGrid(allTiles.filter { it !in played && it !in hand }, 4) { tile ->
            acceptPlayedTile(tile, false)
        })

        root.addView(action(if (currentTurn == mySeat) "SAYA PASS" else "MEJA $currentTurn PASS") {
            val passedSeat = currentTurn
            if (passedSeat != mySeat) passes.add(PassRecord(passedSeat, leftEnd, rightEnd, played.size))
            currentTurn = nextSeat(currentTurn)
            showGame()
            advice.text = "PASS Meja $passedSeat dicatat. Giliran berikutnya Meja $currentTurn."
        })
        root.addView(action("AKHIRI / ATUR ULANG") {
            hand.clear()
            played.clear()
            opening = null
            showSetup()
        })

        // Generate recommendations automatically whenever the game screen appears.
        analyze()
    }

    private fun acceptPlayedTile(tile: Tile, isOwn: Boolean) {
        if (tile in played) {
            statusMessage("Kartu itu sudah tercatat di meja.")
            return
        }
        if (isOwn && currentTurn != mySeat) {
            statusMessage("Sekarang giliran Meja $currentTurn, bukan giliranmu.")
            return
        }
        if (!isOwn && currentTurn == mySeat) {
            statusMessage("Sekarang giliranmu. Pilih kartu dari tanganmu.")
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
        root.addView(action("PASANG DI KIRI  (ujung $leftEnd)") { commitTile(tile, Side.LEFT, pendingIsOwn) })
        root.addView(action("PASANG DI KANAN  (ujung $rightEnd)") { commitTile(tile, Side.RIGHT, pendingIsOwn) })
        root.addView(action("BATAL") { showGame() })
    }

    private fun playSuggested(tile: Tile, side: Side) {
        if (currentTurn != mySeat) {
            statusMessage("Tombol saran aktif saat giliranmu.")
            return
        }
        val end = if (side == Side.LEFT) leftEnd else rightEnd
        if (!tile.contains(end)) {
            statusMessage("Kartu ${tileName(tile)} tidak cocok di sisi ${side.name}.")
            return
        }
        if (tile !in hand) {
            statusMessage("Kartu saran itu tidak ada di tanganmu.")
            return
        }
        commitTile(tile, side, true)
    }

    private fun commitTile(tile: Tile, side: Side, isOwn: Boolean) {
        val exposed = if (side == Side.LEFT) leftEnd else rightEnd
        val newEnd = tile.otherEnd(exposed) ?: exposed
        if (side == Side.LEFT) leftEnd = newEnd else rightEnd = newEnd
        played.add(tile)
        if (isOwn) hand.remove(tile)
        val playedBy = currentTurn
        currentTurn = nextSeat(currentTurn)
        pendingTile = null
        showGame()
        advice.text = if (isOwn) {
            "Meja $playedBy memainkan ${tileName(tile)} di ${side.name}. Sisa kartu: ${hand.size}. Giliran Meja $currentTurn."
        } else {
            "Meja $playedBy memainkan ${tileName(tile)} di ${side.name}. Posisi meja diperbarui. Giliran Meja $currentTurn."
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
            recommendationTiles.removeAllViews()
            if (best == null) {
                advice.text = "Tidak ada kartu legal. Pertimbangkan PASS."
                return
            }

            val suggestions = listOf(best) + result.alternatives.take(3)
            suggestions.forEachIndexed { index, option ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(3), dp(4), dp(3), dp(4))
                }
                val suggestedTile = option.move.tile
                item.addView(TileFaceView(suggestedTile), LinearLayout.LayoutParams(
                    dp(38), dp(56)
                ).apply { setMargins(dp(2), dp(2), dp(5), dp(2)) })

                // Side controls belong beside the recommended tile, not in the player's hand.
                val sideChoices = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val canLeft = suggestedTile.contains(leftEnd)
                val canRight = suggestedTile.contains(rightEnd)
                if (canLeft) {
                    sideChoices.addView(action("KIRI") {
                        playSuggested(suggestedTile, Side.LEFT)
                    }.apply {
                        isEnabled = currentTurn == mySeat
                        textSize = 10f
                        minWidth = 0
                        setPadding(dp(4), 0, dp(4), 0)
                    }, LinearLayout.LayoutParams(dp(58), dp(30)).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
                if (canRight) {
                    sideChoices.addView(action("KANAN") {
                        playSuggested(suggestedTile, Side.RIGHT)
                    }.apply {
                        isEnabled = currentTurn == mySeat
                        textSize = 10f
                        minWidth = 0
                        setPadding(dp(4), 0, dp(4), 0)
                    }, LinearLayout.LayoutParams(dp(58), dp(30)).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
                item.addView(sideChoices)
                recommendationTiles.addView(item)
                recommendationTiles.addView(TextView(this).apply {
                    text = if (index == 0) "SARAN TERBAIK" else "ALTERNATIF $index"
                    textSize = 10f
                    setTextColor(darkRed)
                    setPadding(dp(2), 0, 0, dp(3))
                })
            }

            advice.text = buildString {
                append("Skor strategi: ${"%.2f".format(best.score)}\n")
                append("Tekanan lawan (estimasi): ${"%.0f".format(best.opponentPressure * 100)}%\n")
                append("Peluang bisa bermain lagi (estimasi): ${"%.0f".format(best.futurePlayChance * 100)}%")
                append("\nPrediksi adalah perkiraan, bukan kepastian kartu lawan.")
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
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            tiles.forEach { tile ->
                val face = TileFaceView(tile).apply {
                    contentDescription = tileName(tile)
                    setOnClickListener { onSelect(tile) }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = dp(54)
                    height = dp(78)
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                }
                addView(face, params)
            }
        }
    }

    private fun handTileList(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            hand.sortedWith(compareBy<Tile> { it.low }.thenBy { it.high }).forEach { tile ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(TileFaceView(tile), LinearLayout.LayoutParams(dp(38), dp(56)).apply {
                    setMargins(dp(2), dp(2), dp(5), dp(2))
                })
                val leftButton = action("KIRI") {
                    playSuggested(tile, Side.LEFT)
                }.apply {
                    isEnabled = currentTurn == mySeat && tile.contains(leftEnd)
                    textSize = 10f
                    minWidth = 0
                    setPadding(dp(3), 0, dp(3), 0)
                }
                val rightButton = action("KANAN") {
                    playSuggested(tile, Side.RIGHT)
                }.apply {
                    isEnabled = currentTurn == mySeat && tile.contains(rightEnd)
                    textSize = 10f
                    minWidth = 0
                    setPadding(dp(3), 0, dp(3), 0)
                }
                row.addView(leftButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
                row.addView(rightButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
                addView(row)
            }
        }
    }

    private fun tileGridSmall(tiles: List<Tile>, columns: Int, onSelect: (Tile) -> Unit): GridLayout {
        return GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            tiles.forEach { tile ->
                val face = TileFaceView(tile).apply {
                    contentDescription = tileName(tile)
                    setOnClickListener { onSelect(tile) }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = dp(38)
                    height = dp(56)
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                }
                addView(face, params)
            }
        }
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun nextSeat(seat: Int) = (seat % 4) + 1
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
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB71C35.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF5F5.toInt()
            style = Paint.Style.FILL
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC62842.toInt()
            style = Paint.Style.FILL
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE5A0AA.toInt()
            strokeWidth = 1.8f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = RectF(3f, 3f, width - 3f, height - 3f)
            canvas.drawRoundRect(r, 8f, 8f, fill)
            canvas.drawRoundRect(r, 8f, 8f, border)
            canvas.drawLine(7f, height / 2f, width - 7f, height / 2f, line)
            drawPips(canvas, tile.low, width * 0.5f, height * 0.25f)
            drawPips(canvas, tile.high, width * 0.5f, height * 0.75f)
        }

        private fun drawPips(canvas: Canvas, value: Int, cx: Float, cy: Float) {
            if (value == 0) return
            val d = minOf(width, height) * 0.075f
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
