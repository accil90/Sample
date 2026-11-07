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
    private var selectingOpening = true
    private val mySeat = 1
    private var openingSeat = 3
    private var currentTurn = 4
    private var leftEnd = 0
    private var rightEnd = 0
    private var started = false
    private data class MoveSnapshot(
        val leftEnd: Int,
        val rightEnd: Int,
        val currentTurn: Int,
        val hand: Set<Tile>,
        val played: Set<Tile>,
        val passes: List<PassRecord>
    )
    private var lastMoveSnapshot: MoveSnapshot? = null
    private var pendingTile: Tile? = null
    private var pendingIsOwn = false
    private var currentPicker: ((Tile) -> Unit)? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var advice: TextView
    private lateinit var recommendationTiles: LinearLayout
    private lateinit var opponentSideChoices: LinearLayout
    private lateinit var handSummary: TextView

    private val red = Color.rgb(190, 35, 55)
    private val darkRed = Color.rgb(130, 20, 38)
    private val pale = Color.rgb(255, 244, 245)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ARUNA DOMINO PRO"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(250, 247, 247))
        }
        setContentView(root)
        showSetup()
    }

    private fun heading(text: String, size: Float = 20f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(darkRed)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(3))
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
        lastMoveSnapshot = null
        clear()

        root.addView(heading("PERSIAPAN PERMAINAN", 19f))
        root.addView(TextView(this).apply {
            text = "Pilih meja pembuka, kartu pembuka, lalu tandai 7 kartu milikmu."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })

        val seats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        (1..4).forEach { seat ->
            seats.addView(action(if (openingSeat == seat) "✓ MEJA $seat" else "MEJA $seat") {
                openingSeat = seat
                showSetup()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }
        root.addView(seats)

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modes.addView(action(if (selectingOpening) "✓ PILIH PEMBUKA" else "PILIH PEMBUKA") {
            selectingOpening = true
            showSetup()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        modes.addView(action(if (!selectingOpening) "✓ PILIH 7 KARTU SAYA" else "PILIH 7 KARTU SAYA") {
            selectingOpening = false
            showSetup()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        root.addView(modes)

        root.addView(TextView(this).apply {
            text = if (selectingOpening) "Ketuk satu kartu untuk menetapkan kartu pembuka." else "Ketuk kartu untuk menandai kepemilikan. Pilihan: ${hand.size}/7"
            textSize = 12f
            setTextColor(darkRed)
            setPadding(dp(2), dp(5), dp(2), dp(3))
        })

        root.addView(tileGrid(
            allTiles,
            7,
            selectedTiles = hand,
            selectedTile = opening
        ) { tile ->
            if (selectingOpening) {
                opening = tile
                hand.remove(tile)
                selectingOpening = false
            } else {
                if (tile == opening) {
                    statusMessage("Kartu pembuka tidak bisa menjadi kartu tangan.")
                } else if (tile in hand) {
                    hand.remove(tile)
                } else if (hand.size < 7) {
                    hand.add(tile)
                } else {
                    statusMessage("Maksimal 7 kartu. Batalkan pilihan lain terlebih dahulu.")
                }
            }
            showSetup()
        })

        root.addView(TextView(this).apply {
            text = "PEMBUKA: ${opening?.let { tileName(it) } ?: "belum dipilih"}     KARTU SAYA: ${hand.size}/7"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(darkRed)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        })
        root.addView(action("MULAI PERMAINAN") {
            val startTile = opening
            if (startTile == null) {
                statusMessage("Pilih kartu pembuka terlebih dahulu.")
            } else if (hand.size != 7) {
                statusMessage("Pilih tepat 7 kartu yang kamu miliki.")
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
        if (lastMoveSnapshot != null) {
            root.addView(action("UNDO — batalkan kartu terakhir") { undoLastMove() })
        }
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

        // Layout satu layar: kartu lawan di kiri, kontrol dan rekomendasi di kanan.
        val gameColumns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
        }
        val opponentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        opponentPanel.addView(heading("KARTU LAWAN", 13f))
        opponentPanel.addView(TextView(this).apply {
            text = "Pilih kartu yang baru dimainkan"
            textSize = 10f
            setTextColor(Color.DKGRAY)
        })
        val opponentTiles = tileGrid(allTiles.filter { it !in hand }, 4, markedTiles = played) { tile ->
            acceptPlayedTile(tile, false)
        }
        opponentPanel.addView(opponentTiles)

        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(3), dp(4))
            background = roundedBackground(pale, 10)
        }
        opponentSideChoices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        rightPanel.addView(opponentSideChoices)
        rightPanel.addView(heading("SARAN KARTU", 13f))
        recommendationTiles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        recommendationTiles.addView(TextView(this).apply {
            text = "Menghitung..."
            textSize = 10f
            setTextColor(darkRed)
        })
        rightPanel.addView(recommendationTiles)
        gameColumns.addView(opponentPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        gameColumns.addView(rightPanel, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(gameColumns)
        advice = TextView(this).apply {
            textSize = 13f
            setTextColor(darkRed)
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = roundedBackground(pale, 10)
            text = "Hasil analisis dan alasan rekomendasi akan muncul di sini."
        }
        root.addView(advice)

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

        root.addView(action("PENGAMAT LAYAR & REKAMAN") {
            startActivity(android.content.Intent(this, ObserverActivity::class.java))
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
        opponentSideChoices.removeAllViews()
        opponentSideChoices.visibility = View.VISIBLE
        opponentSideChoices.addView(TextView(this).apply {
            text = "PILIH SISI"
            textSize = 10f
            setTextColor(darkRed)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (tile.contains(leftEnd)) {
            opponentSideChoices.addView(action("KIRI") {
                commitTile(tile, Side.LEFT, pendingIsOwn)
            }.apply {
                textSize = 11f
                minWidth = 0
                setPadding(dp(3), 0, dp(3), 0)
            }, LinearLayout.LayoutParams(dp(76), dp(38)).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        if (tile.contains(rightEnd)) {
            opponentSideChoices.addView(action("KANAN") {
                commitTile(tile, Side.RIGHT, pendingIsOwn)
            }.apply {
                textSize = 11f
                minWidth = 0
                setPadding(dp(3), 0, dp(3), 0)
            }, LinearLayout.LayoutParams(dp(76), dp(38)).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
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
        lastMoveSnapshot = MoveSnapshot(
            leftEnd = leftEnd,
            rightEnd = rightEnd,
            currentTurn = currentTurn,
            hand = hand.toSet(),
            played = played.toSet(),
            passes = passes.toList()
        )
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

    private fun undoLastMove() {
        val snapshot = lastMoveSnapshot ?: return
        leftEnd = snapshot.leftEnd
        rightEnd = snapshot.rightEnd
        currentTurn = snapshot.currentTurn
        hand.clear()
        hand.addAll(snapshot.hand)
        played.clear()
        played.addAll(snapshot.played)
        passes.clear()
        passes.addAll(snapshot.passes)
        lastMoveSnapshot = null
        showGame()
        advice.text = "Kartu terakhir dibatalkan. Posisi meja dan kartu tangan sudah dipulihkan."
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
                item.isClickable = true
                item.isFocusable = true
                item.background = roundedBackground(0xFFFFE7EA.toInt(), 8)
                item.contentDescription = "Mainkan kartu saran ${tileName(suggestedTile)}"
                item.setOnClickListener {
                    // Ketuk kartu rekomendasi untuk langsung memainkannya.
                    // Jika cocok pada kedua ujung, fungsi ini menampilkan pilihan KIRI/KANAN.
                    acceptPlayedTile(suggestedTile, true)
                }

                recommendationTiles.addView(item)
                recommendationTiles.addView(TextView(this).apply {
                    text = if (index == 0) "SARAN TERBAIK • KETUK UNTUK MAIN" else "ALTERNATIF $index • KETUK UNTUK MAIN"
                    textSize = 9f
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
        handSummary.text = "Kartu tersisa: ${hand.size}"
    }

    private fun tileGrid(
        tiles: List<Tile>,
        columns: Int,
        markedTiles: Set<Tile> = emptySet(),
        selectedTiles: Set<Tile> = emptySet(),
        selectedTile: Tile? = null,
        onSelect: (Tile) -> Unit
    ): GridLayout {
        return GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            tiles.forEach { tile ->
                val alreadyPlayed = tile in markedTiles
                val isSelected = tile in selectedTiles || tile == selectedTile
                val face = TileFaceView(tile, alreadyPlayed, isSelected).apply {
                    contentDescription = when {
                        alreadyPlayed -> "${tileName(tile)}, sudah dimainkan"
                        isSelected -> "${tileName(tile)}, kartu pembuka terpilih"
                        else -> tileName(tile)
                    }
                    isEnabled = !alreadyPlayed
                    if (!alreadyPlayed) setOnClickListener { onSelect(tile) }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = dp(40)
                    height = dp(57)
                    setMargins(dp(1), dp(2), dp(1), dp(2))
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

    private inner class TileFaceView(
        private val tile: Tile,
        private val alreadyPlayed: Boolean = false,
        private val selected: Boolean = false
    ) : View(this@MainActivity) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) 0xFFFFDDE2.toInt() else 0xFFFFF5F5.toInt()
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
            if (selected) {
                val selectedBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF8E182B.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawRoundRect(r, 8f, 8f, selectedBorder)
                val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF8E182B.toInt()
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.drawText("✓", width - 15f, 15f, check)
            }
            canvas.drawLine(7f, height / 2f, width - 7f, height / 2f, line)
            drawPips(canvas, tile.low, width * 0.5f, height * 0.25f)
            drawPips(canvas, tile.high, width * 0.5f, height * 0.75f)
            if (alreadyPlayed) {
                val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x88FFFFFF.toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(r, 8f, 8f, shade)
                val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF8E182B.toInt()
                    strokeWidth = 4f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(width * 0.22f, height * 0.22f, width * 0.78f, height * 0.78f, mark)
                canvas.drawLine(width * 0.78f, height * 0.22f, width * 0.22f, height * 0.78f, mark)
            }
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
