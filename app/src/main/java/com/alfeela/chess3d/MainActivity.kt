package com.alfeela.chess3d

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var board: ChessBoardView
    private lateinit var lan: LanConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        lan = LanConnection(
            onState = { message -> runOnUiThread { status.text = message } },
            onMessage = { message -> runOnUiThread { handleNetworkMessage(message) } }
        )
        board = ChessBoardView(this)
        board.onMove = { move ->
            lan.send("MOVE|${move.fromFile}|${move.fromRank}|${move.toFile}|${move.toRank}|${move.promotion?.name ?: "-"}")
            updateGameStatus()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 31))
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
        }
        status = TextView(this).apply {
            text = "3D CHESS  •  Local Wi-Fi • White to move"
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 44))
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }

        val host = Button(this).apply {
            text = "Host (White)"
            setOnClickListener {
                board.localSide = Side.WHITE
                board.resetBoard()
                lan.host()
                status.text = "Hosting on Wi-Fi • You play White • Port 45454"
            }
        }
        val join = Button(this).apply {
            text = "Join (Black)"
            setOnClickListener {
                val ip = EditText(this@MainActivity).apply { hint = "Host IP, e.g. 192.168.1.10" }
                AlertDialog.Builder(this@MainActivity).setTitle("Join local Wi-Fi game")
                    .setView(ip)
                    .setPositiveButton("Connect") { _, _ ->
                        val address = ip.text.toString().trim()
                        if (address.isNotEmpty()) {
                            board.localSide = Side.BLACK
                            board.resetBoard()
                            status.text = "Connecting to $address…"
                            lan.join(address)
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        val reset = Button(this).apply {
            text = "New game"
            setOnClickListener {
                board.resetBoard()
                lan.send("RESET")
                updateGameStatus()
            }
        }
        controls.addView(host); controls.addView(join); controls.addView(reset)
        root.addView(controls, LinearLayout.LayoutParams(-1, 58))
        setContentView(root)
    }

    private fun handleNetworkMessage(message: String) {
        if (message == "RESET") {
            board.resetBoard()
            status.text = "Opponent started a new game • White to move"
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
                status.text = "Received invalid move • Check connection or restart game"
            } else {
                updateGameStatus()
            }
        }
    }

    private fun updateGameStatus() {
        val game = board.game
        status.text = when {
            game.isCheckmate() -> "Checkmate • ${game.sideToMove.opposite()} wins"
            game.isStalemate() -> "Draw • Stalemate"
            game.isInCheck(game.sideToMove) -> "Check • ${game.sideToMove} to move"
            else -> "${game.sideToMove} to move • Tap a piece, then its destination"
        }
    }

    override fun onDestroy() {
        lan.close()
        super.onDestroy()
    }
}
