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
        lan = LanConnection(onState = { message -> runOnUiThread { status.text = message } }, onMessage = { message -> runOnUiThread { status.text = "Opponent: $message" } })
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
        val host = Button(this).apply { text = "Host game"; setOnClickListener { lan.host(); status.text = "Waiting for opponent on Wi-Fi (port 45454)" } }
        val join = Button(this).apply { text = "Join game"; setOnClickListener { val ip = EditText(this@MainActivity).apply { hint = "Host IP, e.g. 192.168.1.10" }; AlertDialog.Builder(this@MainActivity).setTitle("Join local Wi-Fi game").setView(ip).setPositiveButton("Connect") { _, _ -> val address = ip.text.toString().trim(); if (address.isNotEmpty()) { status.text = "Connecting…"; lan.join(address) } }.setNegativeButton("Cancel", null).show() } }
        val reset = Button(this).apply { text = "Reset board"; setOnClickListener { board.resetBoard(); status.text = "Board reset" } }
        controls.addView(host); controls.addView(join); controls.addView(reset)
        root.addView(controls, LinearLayout.LayoutParams(-1, 58))
        setContentView(root)
    }
    override fun onDestroy() { lan.close(); super.onDestroy() }
}
