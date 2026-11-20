package com.alfeela.chess3d

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var board: ChessBoardView
    private lateinit var lan: LanConnection
    private lateinit var mode2d: Button
    private lateinit var mode3d: Button
    private var playVsComputer = false

    private val wood = Color.rgb(65, 20, 13)
    private val gold = Color.rgb(224, 190, 120)
    private val cream = Color.rgb(248, 232, 198)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.statusBarColor = wood
        window.navigationBarColor = wood

        lan = LanConnection(
            onState = { message -> runOnUiThread { status.text = message } },
            onMessage = { message -> runOnUiThread { handleNetworkMessage(message) } }
        )
        board = ChessBoardView(this)
        board.onMove = { move ->
            if (playVsComputer) {
                updateGameStatus()
                Thread {
                    val computerMove = board.game.chooseComputerMove(3)
                    runOnUiThread {
                        if (computerMove != null && board.applyComputerMove(computerMove)) updateGameStatus()
                    }
                }.start()
            } else {
                lan.send("MOVE|${move.fromFile}|${move.fromRank}|${move.toFile}|${move.toRank}|${move.promotion?.name ?: "-"}")
                updateGameStatus()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(35, 9, 7), Color.rgb(105, 35, 20), Color.rgb(48, 12, 9))
            )
        }

        val title = TextView(this).apply {
            text = "Boards & Pieces"
            setTextColor(cream)
            textSize = 25f
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(5f, 2f, 3f, Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        mode2d = makeButton("2D", false)
        mode3d = makeButton("3D", true)
        modes.addView(mode2d, LinearLayout.LayoutParams(dp(82), dp(48)).apply { marginEnd = dp(12) })
        modes.addView(mode3d, LinearLayout.LayoutParams(dp(82), dp(48)))
        mode2d.setOnClickListener { selectMode(false) }
        mode3d.setOnClickListener { selectMode(true) }
        root.addView(modes, LinearLayout.LayoutParams(-1, dp(58)))

        val preview = FrameLayout(this).apply {
            background = rounded(Color.rgb(25, 22, 19), gold, 2)
            clipToPadding = true
        }
        preview.addView(board, FrameLayout.LayoutParams(-1, -1))
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(2), dp(8), dp(2), dp(8))
        })

        status = TextView(this).apply {
            text = "اختر Host أو Join لبدء اللعب عبر Wi-Fi"
            setTextColor(cream)
            textSize = 14f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val aiButton = makeButton("اللعب ضد الكمبيوتر • متوسط", true).apply {
            setOnClickListener {
                playVsComputer = true
                board.localSide = Side.WHITE
                board.resetBoard()
                lan.close()
                status.text = "أنت الأبيض • الكمبيوتر الأسود • مستوى متوسط • يعمل دون إنترنت"
            }
        }
        root.addView(aiButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(dp(2), dp(2), dp(2), dp(5))
        })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val host = makeButton("HOST • الأبيض", true).apply {
            setOnClickListener {
                playVsComputer = false
                board.localSide = Side.WHITE
                board.resetBoard()
                lan.host()
                status.text = "أنت الأبيض • بانتظار اللاعب الثاني على نفس Wi-Fi"
            }
        }
        val join = makeButton("JOIN • الأسود", true).apply {
            setOnClickListener {
                val ip = EditText(this@MainActivity).apply { hint = "192.168.1.10" }
                AlertDialog.Builder(this@MainActivity).setTitle("الاتصال باللاعب المضيف")
                    .setView(ip).setPositiveButton("اتصال") { _, _ ->
                        val address = ip.text.toString().trim()
                        if (address.isNotEmpty()) {
                            playVsComputer = false
                            board.localSide = Side.BLACK
                            board.resetBoard()
                            status.text = "جارٍ الاتصال بـ $address"
                            lan.join(address)
                        }
                    }.setNegativeButton("إلغاء", null).show()
            }
        }
        val reset = makeButton("جديدة", false).apply {
            setOnClickListener {
                board.resetBoard()
                lan.send("RESET")
                updateGameStatus()
            }
        }
        controls.addView(host, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        controls.addView(join, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        controls.addView(reset, LinearLayout.LayoutParams(dp(78), dp(48)))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(54)))
        setContentView(root)
    }

    private fun selectMode(is3d: Boolean) {
        mode3d.background = rounded(if (is3d) Color.rgb(112, 76, 39) else Color.rgb(48, 28, 20), gold, 2)
        mode2d.background = rounded(if (!is3d) Color.rgb(112, 76, 39) else Color.rgb(48, 28, 20), gold, 2)
        status.text = if (is3d) "عرض ثلاثي الأبعاد • اختر Host أو Join" else
            "عرض 2D غير متاح بعد؛ العرض الحالي ثلاثي الأبعاد"
    }

    private fun makeButton(label: String, highlighted: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("serif", Typeface.BOLD)
        setTextColor(cream)
        background = rounded(if (highlighted) Color.rgb(112, 76, 39) else Color.rgb(48, 28, 20), gold, 2)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(width), stroke)
        cornerRadius = dp(5).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun handleNetworkMessage(message: String) {
        if (message == "RESET") {
            board.resetBoard()
            status.text = "بدأ الخصم مباراة جديدة • دور الأبيض"
            return
        }
        val parts = message.split("|")
        if (parts.size == 6 && parts[0] == "MOVE") {
            val fromFile = parts[1].toIntOrNull() ?: return
            val fromRank = parts[2].toIntOrNull() ?: return
            val toFile = parts[3].toIntOrNull() ?: return
            val toRank = parts[4].toIntOrNull() ?: return
            val promotion = if (parts[5] == "-") null else
                runCatching { PieceType.valueOf(parts[5]) }.getOrNull()
            val move = ChessMove(fromFile, fromRank, toFile, toRank, promotion)
            if (!board.applyRemoteMove(move)) {
                status.text = "حركة غير صالحة • تحقق من الاتصال أو أعد المباراة"
            } else updateGameStatus()
        }
    }

    private fun updateGameStatus() {
        val game = board.game
        status.text = when {
            game.isCheckmate() -> "كش مات • الفائز: ${game.sideToMove.opposite()}"
            game.isStalemate() -> "تعادل • خنق"
            game.isInCheck(game.sideToMove) -> "كش • دور ${game.sideToMove}"
            else -> "دور ${game.sideToMove} • اختر قطعة ثم مربع الوجهة"
        }
    }

    override fun onDestroy() {
        lan.close()
        super.onDestroy()
    }
}
