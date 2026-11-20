package com.alfeela.chess3d

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var board: ChessBoardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        board = ChessBoardView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 31))
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
        }
        status = TextView(this).apply {
            text = "3D CHESS  •  Local Wi-Fi prototype"
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 44))
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val host = Button(this).apply { text = "Host game"; setOnClickListener { status.text = "Host mode: LAN transport is being implemented" } }
        val join = Button(this).apply { text = "Join game"; setOnClickListener { status.text = "Join mode: enter host IP in the next milestone" } }
        val reset = Button(this).apply { text = "Reset board"; setOnClickListener { board.resetBoard(); status.text = "Board reset" } }
        controls.addView(host); controls.addView(join); controls.addView(reset)
        root.addView(controls, LinearLayout.LayoutParams(-1, 58))
        setContentView(root)
    }
}
