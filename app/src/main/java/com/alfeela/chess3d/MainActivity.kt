package com.alfeela.chess3d

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
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
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var homeStatus: TextView
    private lateinit var homeRoot: LinearLayout
    private lateinit var board: ChessBoardView
    private lateinit var lan: LanConnection
    private lateinit var auth: AuthManager
    private lateinit var mode2d: Button
    private lateinit var mode3d: Button
    private var playVsComputer = false
    private var onlineMode = false
    private var onlineRoomId: String? = null
    private var onlineMoves = JSONArray()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onlinePoll: Runnable? = null
    private var pendingHotspotSettingsReturn = false
    private var activeMatchMode: String? = null
    private var activeMatchStartedAt: Long = 0L

    private val wood = Color.rgb(10, 10, 12)
    private val gold = Color.rgb(220, 178, 91)
    private val cream = Color.rgb(246, 239, 222)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.statusBarColor = wood
        window.navigationBarColor = wood

        auth = AuthManager(this)
        lan = LanConnection(
            onState = { message ->
                runOnUiThread {
                    if (::board.isInitialized) {
                        board.isEnabled = message == "Opponent connected" || message.startsWith("Connected to ")
                    }
                    status.text = when {
                        message.startsWith("Waiting for opponent") ->
                            "غرفتك جاهزة • بانتظار انضمام الصديق عبر البحث التلقائي"
                        message == "Searching for nearby rooms" ->
                            "جارٍ البحث عن غرف 3D Chess على شبكة Wi-Fi المتصلة..."
                        message.startsWith("Found room at") ->
                            "تم العثور على الغرفة • جارٍ الاتصال تلقائياً..."
                        message == "Discovery timeout: no room found" ->
                            "لم نعثر على غرفة • تأكد من اتصالك بنقطة اتصال صديقك وأنه بدأ المجموعة، ثم حاول مجدداً"
                        message == "Opponent connected" -> "تم اتصال الصديق بنجاح عبر Wi-Fi"
                        message == "Connection closed by opponent" -> "انقطع اتصال الصديق • تحقق من الشبكة ثم أعد إنشاء المباراة"
                        message.startsWith("Connection lost:") -> "انقطع اتصال Wi-Fi: ${message.substringAfter(":").trim()}"
                        message.startsWith("Host error:") -> "تعذر إنشاء المباراة: ${message.substringAfter(":").trim()}"
                        message.startsWith("Join error:") -> "تعذر الاتصال: ${message.substringAfter(":").trim()} • تأكد من العنوان والشبكة نفسها"
                        message.startsWith("Send failed:") -> "تعذر إرسال النقلة: ${message.substringAfter(":").trim()}"
                        else -> message
                    }
                }
            },
            onMessage = { message -> runOnUiThread { handleNetworkMessage(message) } }
        )
        board = ChessBoardView(this)
        getSharedPreferences("game_settings", MODE_PRIVATE).let { prefs ->
            board.cameraTouchSensitivity = prefs.getFloat("camera_sensitivity", 1.0f)
            board.setPieceStyle(prefs.getInt("piece_style", 0))
        }
        getSharedPreferences("board_appearance", MODE_PRIVATE).let { prefs ->
            board.setAppearance(
                prefs.getInt("white_piece", Color.rgb(240,230,210)),
                prefs.getInt("black_piece", Color.rgb(9,9,11)),
                prefs.getInt("light_square", Color.WHITE),
                prefs.getInt("dark_square", Color.rgb(133,139,149))
            )
        }
        board.onMove = { move ->
            if (onlineMode) {
                val moveJson = JSONObject().put("ff", move.fromFile).put("fr", move.fromRank)
                    .put("tf", move.toFile).put("tr", move.toRank).put("p", move.promotion?.name ?: "-")
                onlineMoves.put(moveJson)
                val roomId = onlineRoomId
                if (roomId != null) Thread {
                    val result = auth.submitOnlineMoves(roomId, onlineMoves, board.game.sideToMove.name.lowercase())
                    runOnUiThread { if (!result.ok) status.text = result.message else updateGameStatus() }
                }.start()
            } else if (playVsComputer) {
                updateGameStatus()
                Thread {
                    val depth = getSharedPreferences("game_settings", MODE_PRIVATE).getInt("computer_depth", 3)
                    val computerMove = board.game.chooseComputerMove(depth)
                    runOnUiThread { if (computerMove != null && board.applyComputerMove(computerMove)) updateGameStatus() }
                }.start()
            } else {
                lan.send("MOVE|${move.fromFile}|${move.fromRank}|${move.toFile}|${move.toRank}|${move.promotion?.name ?: "-"}")
                updateGameStatus()
            }
        }

        board.onPromotionRequested = { side, complete ->
            val choices = arrayOf("الملكة", "القلعة", "الفيل", "الحصان")
            val types = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
            AlertDialog.Builder(this)
                .setTitle("ترقية الجندي")
                .setMessage("اختر القطعة التي تريد الترقية إليها")
                .setItems(choices) { _, which -> complete(types[which]) }
                .setOnCancelListener { /* Keep the pawn move pending; no illegal default promotion. */ }
                .show()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 6, 8), Color.rgb(19, 17, 14), Color.rgb(5, 6, 8))
            )
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val profileBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(20, 19, 17), Color.rgb(104, 82, 48), 1)
            setPadding(dp(14), dp(5), dp(10), dp(5))
        }
        val profile = TextView(this).apply {
            text = "♙  ملف اللاعب"
            setTextColor(cream)
            textSize = 14f
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        profileBar.addView(profile, LinearLayout.LayoutParams(0, dp(40), 1f))
        fun quickAction(symbol: String, action: () -> Unit) {
            val item = TextView(this).apply {
                text = symbol
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(gold)
                setOnClickListener { action() }
            }
            profileBar.addView(item, LinearLayout.LayoutParams(dp(44), dp(42)))
        }
        quickAction("♟") { showFriendsDialog() }
        quickAction("⚙") { showSettingsDialog() }
        content.addView(profileBar, LinearLayout.LayoutParams(-1, dp(54)))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(43, 32, 19), Color.rgb(18, 17, 16), Color.rgb(9, 10, 12))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(157, 117, 55))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(12).toFloat()
            rotationX = 1.5f
        }
        val crown = TextView(this).apply {
            text = "♛     ♚"
            setTextColor(gold)
            textSize = 42f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setShadowLayer(16f, 0f, 7f, Color.rgb(120, 75, 20))
            rotationY = -8f
            elevation = dp(8).toFloat()
        }
        hero.addView(crown, LinearLayout.LayoutParams(-1, dp(62)))
        val title = TextView(this).apply {
            text = "3D CHESS"
            setTextColor(cream)
            textSize = 29f
            letterSpacing = 0.18f
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        hero.addView(title, LinearLayout.LayoutParams(-1, dp(42)))
        val subtitle = TextView(this).apply {
            text = "مملكة الشطرنج • تحدَّ أصدقاءك واصنع انتصارك"
            setTextColor(Color.rgb(211, 178, 119))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        hero.addView(subtitle, LinearLayout.LayoutParams(-1, dp(28)))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(174)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })

        status = TextView(this).apply {
            text = "اختر طريقة اللعب"
            setTextColor(cream)
            textSize = 13f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        homeStatus = status
        content.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        fun homeTile(label: String, detail: String, highlighted: Boolean = false, action: () -> Unit): LinearLayout {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                elevation = dp(9).toFloat()
                rotationX = 2.5f
                background = rounded(
                    if (highlighted) Color.rgb(73, 51, 24) else Color.rgb(24, 24, 24),
                    if (highlighted) gold else Color.rgb(83, 73, 57), 1
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            tile.addView(TextView(this).apply {
                text = label
                setTextColor(if (highlighted) Color.rgb(255, 224, 164) else cream)
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("serif", Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, dp(30)))
            tile.addView(TextView(this).apply {
                text = detail
                setTextColor(Color.rgb(180, 165, 140))
                textSize = 11f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(24)))
            return tile
        }

        val savedDepth = getSharedPreferences("game_settings", MODE_PRIVATE).getInt("computer_depth", 3)
        val savedLevel = when (savedDepth) { 1 -> "سهل"; 4 -> "صعب"; 5 -> "خبير"; else -> "متوسط" }
        val computerTile = homeTile("▶  العب ضد الكمبيوتر", "مستوى $savedLevel • دون إنترنت", true) {
            stopOnlinePolling()
            beginMatch("computer")
            playVsComputer = true
            board.localSide = Side.WHITE
            board.resetBoard()
            lan.close()
            status.text = "أنت الأبيض • الكمبيوتر الأسود • مستوى $savedLevel"
            showGameScreen()
        }
        content.addView(computerTile, LinearLayout.LayoutParams(-1, dp(66)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        fun addHomeAction(tile: View, height: Int = 66) {
            content.addView(tile, LinearLayout.LayoutParams(-1, dp(height)).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            })
        }
        addHomeAction(homeTile("♟  اللعب مع صديق عبر Wi-Fi", "اتصال محلي • دون إنترنت") {
            showLanDialog()
        }, 72)
        addHomeAction(homeTile("🌐  اللعب عبر الإنترنت", "تحدَّ أصدقاءك أينما كانوا") {
            showOnlineDialog()
        }, 72)
        addHomeAction(homeTile("📜  سجل المباريات", "مباريات الكمبيوتر • الأصدقاء • الإنترنت") {
            showMatchHistory()
        }, 62)
        addHomeAction(homeTile("♟  الأصدقاء", "إضافة الأصدقاء وإدارة قائمتك") {
            showFriendsDialog()
        }, 62)

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun utilityButton(label: String, action: () -> Unit) {
            val button = makeButton(label, false).apply {
                textSize = 12f
                setOnClickListener { action() }
            }
            utilityRow.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(3), dp(5), dp(3), dp(5))
            })
        }
        utilityButton("📜  سجل النقلات") { showMoveHistory() }
        utilityButton("❔  قواعد اللعب") { showHowToPlay() }
        content.addView(utilityRow, LinearLayout.LayoutParams(-1, dp(54)))

        val footer = TextView(this).apply {
            text = "BLACK  •  GOLD  •  STRATEGY"
            setTextColor(Color.rgb(145, 119, 76))
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        }
        content.addView(footer, LinearLayout.LayoutParams(-1, dp(30)))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        homeRoot = root
        setContentView(homeRoot)
        if (intent.data?.scheme == "com.alfeela.chess3d") {
            status.text = auth.acceptOAuthCallback(intent.data!!).message
        }
    }

    private fun showGameScreen() {
        val gameRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(5, 6, 8), Color.rgb(19, 17, 14), Color.rgb(5, 6, 8)))
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = makeButton("‹  القائمة الرئيسية", false).apply {
            textSize = 13f
            setOnClickListener {
                saveActiveMatch()
                stopOnlinePolling()
                lan.close()
                setContentView(homeRoot)
                status = homeStatus
            }
        }
        topBar.addView(back, LinearLayout.LayoutParams(0, dp(48), 1f))
        val title = TextView(this).apply { text = "♛  3D CHESS  ♚"; setTextColor(gold); textSize = 20f; gravity = Gravity.CENTER; typeface = Typeface.create("serif", Typeface.BOLD) }
        topBar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        gameRoot.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
        val gameStatus = TextView(this).apply { setTextColor(cream); textSize = 14f; gravity = Gravity.CENTER; text = homeStatus.text; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        status = gameStatus
        gameRoot.addView(gameStatus, LinearLayout.LayoutParams(-1, dp(52)))
        val boardFrame = FrameLayout(this).apply { background = rounded(Color.rgb(10, 12, 15), gold, 2); clipToPadding = true }
        (board.parent as? android.view.ViewGroup)?.removeView(board)
        boardFrame.addView(board, FrameLayout.LayoutParams(-1, -1))
        gameRoot.addView(boardFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(8), 0, dp(8)) })
        // Camera is controlled directly on the 3D board with touch gestures.
        // A compact hint replaces the old bottom camera-button strip.
        gameRoot.addView(TextView(this).apply {
            text = "اسحب بإصبع واحد لتدوير الرقعة وإمالتها • استخدم إصبعين للتقريب والإبعاد"
            setTextColor(Color.rgb(218, 190, 145))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }, LinearLayout.LayoutParams(-1, dp(30)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val history = makeButton("📜 سجل النقلات", false).apply { setOnClickListener { showMoveHistory() } }
        val reset = makeButton("مباراة جديدة", true).apply {
            setOnClickListener {
                if (onlineMode) status.text = "إعادة ضبط مباراة الإنترنت غير متاحة أثناء اللعب"
                else { board.resetBoard(); lan.send("RESET"); updateGameStatus() }
            }
        }
        actions.addView(history, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        actions.addView(reset, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        gameRoot.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))
        val rules = makeButton("❔ طريقة اللعب والقواعد", false).apply { setOnClickListener { showHowToPlay() } }
        gameRoot.addView(rules, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(gameRoot)
    }
    private fun showHowToPlay() {
        val pieces = arrayOf("♟ الجندي", "♜ القلعة", "♞ الحصان", "♝ الفيل", "♛ الملكة", "♚ الملك", "قواعد عامة ونهاية المباراة")
        val details = arrayOf(
            "الجندي\n• يتحرك مربعاً واحداً إلى الأمام إذا كان المربع خالياً.\n• من مكانه الأصلي يمكنه التقدم مربعين إذا كان المربعان خاليين.\n• يأسر قطعة الخصم قطرياً مربعاً واحداً إلى الأمام، ولا يتحرك قطرياً دون أسر.\n• الأخذ بالتجاوز: يمكنه أسر جندي الخصم الذي تقدم مربعين، في النقلة التالية مباشرة، كأنه تحرك مربعاً واحداً.\n• الترقية: عند وصوله إلى آخر صف يتحول إلى ملكة أو قلعة أو فيل أو حصان.",
            "القلعة\n• تتحرك أفقياً أو عمودياً لأي عدد من المربعات.\n• لا تستطيع القفز فوق القطع.\n• تأسر قطعة الخصم إذا وقفت على مربعها.\n• تشارك الملك في حركة التبييت بشروطها.",
            "الحصان\n• يتحرك على شكل حرف L: مربعان في اتجاه ثم مربع واحد عمودياً عليه.\n• هو القطعة الوحيدة التي تستطيع القفز فوق القطع.\n• يأسر قطعة الخصم الموجودة في مربع وصوله، ولا يأسر القطع التي يقفز فوقها.",
            "الفيل\n• يتحرك قطرياً لأي عدد من المربعات.\n• يبقى طوال المباراة على لون المربعات نفسه الذي بدأ عليه.\n• لا يستطيع القفز فوق القطع، ويأسر بالوصول إلى مربع قطعة الخصم.",
            "الملكة\n• أقوى القطع حركة؛ تجمع حركة القلعة والفيل.\n• تتحرك أفقياً أو عمودياً أو قطرياً لأي عدد من المربعات.\n• لا تستطيع القفز فوق القطع، وتأسر بالوصول إلى مربع الخصم.",
            "الملك\n• يتحرك مربعاً واحداً في أي اتجاه: أفقياً أو عمودياً أو قطرياً.\n• لا يجوز أن ينتقل إلى مربع مهدد بقطعة من الخصم.\n• التبييت: يتحرك الملك مربعين باتجاه قلعة لم تتحرك من قبل، ثم تنتقل القلعة إلى المربع المجاور للملك. يشترط ألا يكون الملك في كش، وألا يعبر أو ينتهي في مربع مهدد، وألا توجد قطع بينهما، وألا يكون الملك أو القلعة قد تحركا سابقاً.",
            "قواعد عامة ونهاية المباراة\n• الأبيض يبدأ أولاً، ثم يتناوب اللاعبان نقلة واحدة لكل دور.\n• الكش: الملك مهدد بالأسر؛ يجب إزالة التهديد فوراً.\n• كش مات: الملك في كش ولا توجد نقلة قانونية تنقذه؛ تنتهي المباراة بخسارة صاحبه.\n• التعادل: قد يحدث عند عدم وجود نقلة قانونية والملك ليس في كش (خنق)، أو باتفاق اللاعبين، أو وفق قواعد التكرار وقاعدة الخمسين نقلة.\n• لا يجوز تنفيذ نقلة تترك ملكك في كش."
        )
        AlertDialog.Builder(this)
            .setTitle("دليل حركة القطع والقواعد")
            .setItems(pieces) { _, which ->
                AlertDialog.Builder(this)
                    .setTitle(pieces[which])
                    .setMessage(details[which])
                    .setPositiveButton("فهمت", null)
                    .setNeutralButton("كل القطع") { _, _ -> showHowToPlay() }
                    .show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun beginMatch(mode: String) {
        saveActiveMatch()
        activeMatchMode = mode
        activeMatchStartedAt = System.currentTimeMillis()
    }

    private fun saveActiveMatch() {
        val mode = activeMatchMode ?: return
        val moves = board.game.moveHistory()
        if (moves.isEmpty()) { activeMatchMode = null; activeMatchStartedAt = 0L; return }
        val game = board.game
        val result = when {
            game.isCheckmate() -> "كش مات • الفائز " + sideName(game.sideToMove.opposite())
            game.isStalemate() -> "تعادل • خنق"
            else -> "غير مكتملة"
        }
        val moveLines = JSONArray()
        moves.forEachIndexed { index, move ->
            val from = "" + ('a' + move.fromFile) + (8 - move.fromRank)
            val to = "" + ('a' + move.toFile) + (8 - move.toRank)
            val suffix = move.promotion?.let { "=" + it.name } ?: ""
            moveLines.put((if (index % 2 == 0) (index / 2 + 1).toString() + ". " else "") + from + "-" + to + suffix)
        }
        val now = System.currentTimeMillis()
        val item = JSONObject().put("mode", mode).put("started", activeMatchStartedAt)
            .put("ended", now).put("duration", ((now - activeMatchStartedAt).coerceAtLeast(0L) / 1000L))
            .put("result", result).put("count", moves.size).put("moves", moveLines)
        val prefs = getSharedPreferences("match_history", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("entries", "[]") ?: "[]") }.getOrElse { JSONArray() }
        history.put(item)
        val trimmed = JSONArray()
        for (i in (history.length() - 100).coerceAtLeast(0) until history.length()) trimmed.put(history.getJSONObject(i))
        prefs.edit().putString("entries", trimmed.toString()).apply()
        activeMatchMode = null
        activeMatchStartedAt = 0L
    }

    private fun showMatchHistory() {
        val history = runCatching { JSONArray(getSharedPreferences("match_history", MODE_PRIVATE).getString("entries", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val labels = arrayOf("مباريات ضد الكمبيوتر", "مباريات الأصدقاء عبر Wi-Fi", "مباريات الإنترنت")
        val modes = arrayOf("computer", "wifi", "online")
        AlertDialog.Builder(this).setTitle("سجل المباريات • اختر التصنيف")
            .setItems(labels) { _, category ->
                val matches = (0 until history.length()).mapNotNull { i ->
                    history.optJSONObject(i)?.takeIf { it.optString("mode") == modes[category] }
                }.reversed()
                if (matches.isEmpty()) {
                    AlertDialog.Builder(this).setTitle(labels[category])
                        .setMessage("لا توجد مباريات مسجلة في هذا القسم بعد. ستظهر هنا تفاصيل المباريات بعد إنهائها أو مغادرتها.")
                        .setPositiveButton("حسناً", null).show()
                    return@setItems
                }
                val date = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.getDefault())
                val rows = matches.map { m ->
                    date.format(java.util.Date(m.optLong("ended"))) + "  •  " + m.optInt("count") + " نقلة\n" + m.optString("result")
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle(labels[category]).setItems(rows) { _, selected ->
                    val m = matches[selected]
                    val duration = m.optLong("duration")
                    val moves = m.optJSONArray("moves") ?: JSONArray()
                    val moveText = (0 until moves.length()).map { moves.optString(it) }.chunked(2).joinToString("\n") { it.joinToString("     ") }
                    val details = "التصنيف: " + labels[category] + "\nالتاريخ: " + date.format(java.util.Date(m.optLong("ended"))) +
                        "\nالمدة: " + (duration / 60) + " دقيقة و" + (duration % 60) + " ثانية\nعدد النقلات: " + m.optInt("count") +
                        "\nالنتيجة: " + m.optString("result") + "\n\nتفاصيل النقلات:\n" + moveText
                    AlertDialog.Builder(this).setTitle("تفاصيل المباراة").setMessage(details).setPositiveButton("إغلاق", null).show()
                }.setNegativeButton("رجوع", null).show()
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun showMoveHistory() {
        val moves = board.game.moveHistory()
        val lines = moves.mapIndexed { index, move ->
            val from = "${'a' + move.fromFile}${8 - move.fromRank}"
            val to = "${'a' + move.toFile}${8 - move.toRank}"
            val suffix = move.promotion?.let { "=${it.name.first()}" } ?: ""
            val notation = "$from-$to$suffix"
            if (index % 2 == 0) "${index / 2 + 1}. $notation" else notation
        }
        AlertDialog.Builder(this)
            .setTitle("سجل النقلات")
            .setMessage(if (lines.isEmpty()) "لم تبدأ أي نقلة بعد." else lines.chunked(2).joinToString("\n") { it.joinToString("     ") })
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("game_settings", MODE_PRIVATE)
        val options = arrayOf("صعوبة الكمبيوتر", "ألوان القطع والرقعة", "أشكال القطع", "التحكم بالكاميرا", "الحساب وتسجيل الدخول", "معلومات التطبيق")
        AlertDialog.Builder(this).setTitle("إعدادات اللعبة")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val levels = arrayOf("سهل", "متوسط", "صعب", "خبير")
                        val depths = intArrayOf(1, 3, 4, 5)
                        val current = depths.indexOf(prefs.getInt("computer_depth", 3)).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("اختر صعوبة الكمبيوتر")
                            .setSingleChoiceItems(levels, current) { dialog, selected ->
                                prefs.edit().putInt("computer_depth", depths[selected]).apply()
                                dialog.dismiss()
                                AlertDialog.Builder(this).setMessage("تم حفظ مستوى ${levels[selected]}. سيُطبّق على المباراة التالية.")
                                    .setPositiveButton("حسناً", null).show()
                            }.setNegativeButton("إلغاء", null).show()
                    }
                    1 -> showAppearanceDialog()
                    2 -> {
                        val styles = arrayOf("كلاسيكي", "عصري", "ملكي")
                        val current = prefs.getInt("piece_style", 0).coerceIn(0, 2)
                        AlertDialog.Builder(this).setTitle("اختر شكل القطع")
                            .setSingleChoiceItems(styles, current) { dialog, selected ->
                                prefs.edit().putInt("piece_style", selected).apply()
                                board.setPieceStyle(selected)
                                dialog.dismiss()
                                AlertDialog.Builder(this).setMessage("تم تطبيق تصميم ${styles[selected]} وحفظه.")
                                    .setPositiveButton("حسناً", null).show()
                            }.setNegativeButton("إلغاء", null).show()
                    }
                    3 -> {
                        val controls = arrayOf("هادئ", "متوازن", "سريع")
                        val values = floatArrayOf(0.65f, 1.0f, 1.4f)
                        val current = when (prefs.getFloat("camera_sensitivity", 1.0f)) {
                            in 0.0f..0.8f -> 0
                            in 0.81f..1.15f -> 1
                            else -> 2
                        }
                        AlertDialog.Builder(this).setTitle("حساسية حركة الكاميرا")
                            .setSingleChoiceItems(controls, current) { dialog, selected ->
                                prefs.edit().putFloat("camera_sensitivity", values[selected]).apply()
                                board.cameraTouchSensitivity = values[selected]
                                dialog.dismiss()
                                AlertDialog.Builder(this).setMessage("تم ضبط التحكم على وضع ${controls[selected]}.")
                                    .setPositiveButton("حسناً", null).show()
                            }.setNegativeButton("إلغاء", null).show()
                    }
                    4 -> showAccountDialog()
                    5 -> AlertDialog.Builder(this).setTitle("3D Chess")
                        .setMessage("خصص صعوبة الكمبيوتر وألوان الرقعة وأشكال القطع والتحكم بالكاميرا.")
                        .setPositiveButton("حسناً", null).show()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun showAppearanceDialog() {
        val prefs = getSharedPreferences("board_appearance", MODE_PRIVATE)
        val labels = arrayOf("قطع الأبيض", "قطع الأسود", "المربعات الفاتحة", "المربعات الداكنة")
        val keys = arrayOf("white_piece", "black_piece", "light_square", "dark_square")
        val palettes = arrayOf(
            arrayOf("عاجي", "أبيض ناصع", "ذهبي", "أزرق جليدي"),
            arrayOf("أسود لامع", "كحلي", "أحمر داكن", "أخضر زمردي"),
            arrayOf("أبيض", "عاجي", "رمادي فاتح", "أزرق ثلجي"),
            arrayOf("رمادي", "فحمي", "أزرق داكن", "بني خشبي")
        )
        val colors = arrayOf(
            intArrayOf(Color.rgb(240,230,210), Color.WHITE, Color.rgb(220,178,91), Color.rgb(160,205,235)),
            intArrayOf(Color.rgb(9,9,11), Color.rgb(22,35,62), Color.rgb(112,30,38), Color.rgb(20,92,68)),
            intArrayOf(Color.WHITE, Color.rgb(242,232,212), Color.rgb(220,224,230), Color.rgb(220,235,245)),
            intArrayOf(Color.rgb(133,139,149), Color.rgb(65,70,78), Color.rgb(42,65,92), Color.rgb(112,78,52))
        )
        fun apply() {
            board.setAppearance(
                prefs.getInt(keys[0], colors[0][0]),
                prefs.getInt(keys[1], colors[1][0]),
                prefs.getInt(keys[2], colors[2][0]),
                prefs.getInt(keys[3], colors[3][0])
            )
        }
        apply()
        fun choose(index: Int) {
            AlertDialog.Builder(this).setTitle("اختر لون " + labels[index])
                .setSingleChoiceItems(palettes[index], -1) { dialog, which ->
                    prefs.edit().putInt(keys[index], colors[index][which]).apply()
                    apply()
                    dialog.dismiss()
                    if (index < 3) choose(index + 1)
                    else AlertDialog.Builder(this).setMessage("تم تطبيق الألوان مباشرة وحفظها على هذا الجهاز.").setPositiveButton("حسناً", null).show()
                }.setNegativeButton("تخطي") { dialog, _ ->
                    dialog.dismiss()
                    if (index < 3) choose(index + 1)
                }.show()
        }
        choose(0)
    }

    private fun showLanDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(22))
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 6, 8), Color.rgb(25, 20, 14), Color.rgb(7, 8, 10)))
        }
        val back = makeButton("‹  القائمة الرئيسية", false).apply {
            textSize = 13f
            setOnClickListener { setContentView(homeRoot); status = homeStatus }
        }
        root.addView(back, LinearLayout.LayoutParams(-1, dp(46)))
        val emblem = TextView(this).apply {
            text = "♟"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(gold)
        }
        root.addView(emblem, LinearLayout.LayoutParams(-1, dp(76)))
        root.addView(TextView(this).apply {
            text = "اللعب مع الأصدقاء"
            textSize = 25f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(cream)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(TextView(this).apply {
            text = "اتصل بصديقك والعبا معاً عبر Wi-Fi دون الحاجة إلى الإنترنت"
            textSize = 13f
            setTextColor(Color.rgb(190, 177, 153))
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(-1, dp(48)))

        fun optionCard(icon: String, titleText: String, description: String, action: () -> Unit): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(37, 30, 21), Color.rgb(19, 18, 17))).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.rgb(126, 94, 49))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                elevation = dp(3).toFloat()
            }
            val symbol = TextView(this).apply {
                text = icon
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(gold)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.rgb(54, 42, 27))
                }
            }
            card.addView(symbol, LinearLayout.LayoutParams(dp(62), dp(66)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            texts.addView(TextView(this).apply {
                text = titleText
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.rgb(239, 211, 158))
            })
            texts.addView(TextView(this).apply {
                text = description
                textSize = 12f
                setTextColor(Color.rgb(194, 184, 166))
                setPadding(0, dp(5), 0, 0)
            })
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = "‹"
                textSize = 27f
                setTextColor(gold)
            }, LinearLayout.LayoutParams(dp(24), -2))
            return card
        }

        val create = optionCard("♜", "إنشاء مجموعة", "افتح نقطة اتصالك؛ سيعثر عليك صديقك تلقائياً دون إدخال عنوان IP") {
            pendingHotspotSettingsReturn = true
            try {
                startActivity(Intent("android.settings.TETHER_SETTINGS"))
            } catch (_: Exception) {
                try { startActivity(Intent("android.settings.SETTINGS")) }
                catch (_: Exception) {
                    pendingHotspotSettingsReturn = false
                    Toast.makeText(this, "افتح إعدادات الهاتف ثم نقطة الاتصال", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(create, LinearLayout.LayoutParams(-1, dp(112)).apply {
            topMargin = dp(20)
        })
        val join = optionCard("♞", "انضمام للمجموعة", "ابحث تلقائياً عن غرفة صديقك عبر Wi-Fi") {
            stopOnlinePolling()
            beginMatch("wifi")
            playVsComputer = false
            board.localSide = Side.BLACK
            board.resetBoard()
            board.isEnabled = false
            status.text = "جارٍ البحث عن غرف قريبة..."
            showGameScreen()
            lan.discoverAndJoin()
        }
        root.addView(join, LinearLayout.LayoutParams(-1, dp(112)).apply {
            topMargin = dp(12)
        })
        root.addView(TextView(this).apply {
            text = "✦  اتصال محلي آمن وبسيط  ✦"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(166, 143, 105))
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(root)
    }

    private fun startLanHost() {
        stopOnlinePolling()
        beginMatch("wifi")
        playVsComputer = false
        board.localSide = Side.WHITE
        board.resetBoard()
        board.isEnabled = false
        lan.host()
        showGameScreen()
        val address = localWifiAddress()
        status.text = if (address != null)
            "نقطة الاتصال جاهزة • أنت الأبيض • سيعثر عليك صديقك تلقائياً عند البحث"
        else
            "تم فتح المباراة • فعّل نقطة الاتصال؛ سيعثر عليك صديقك تلقائياً عبر البحث"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingHotspotSettingsReturn) {
            pendingHotspotSettingsReturn = false
            startLanHost()
        }
    }

    private fun showOnlineDialog() {
        if (!auth.hasSession()) { status.text = "سجّل الدخول أولاً للعب عبر الإنترنت"; showAccountDialog(); return }
        AlertDialog.Builder(this).setTitle("اللعب عبر الإنترنت")
            .setPositiveButton("إنشاء غرفة") { _, _ -> createOnlineRoom() }
            .setNegativeButton("انضمام برمز") { _, _ ->
                val code = EditText(this).apply { hint = "رمز الغرفة" }
                AlertDialog.Builder(this@MainActivity).setTitle("أدخل رمز الدعوة").setView(code)
                    .setPositiveButton("انضمام") { _, _ -> joinOnlineRoom(code.text.toString()) }
                    .setNegativeButton("إلغاء", null).show()
            }.show()
    }

    private fun createOnlineRoom(invitedFriend: String? = null) {
        status.text = "جارٍ إنشاء غرفة..."
        Thread {
            val result = auth.createOnlineRoom()
            runOnUiThread {
                if (!result.ok || result.id == null) { status.text = result.message; return@runOnUiThread }
                beginMatch("online")
                beginMatch("online")
                onlineMode = true; playVsComputer = false; onlineRoomId = result.id; onlineMoves = JSONArray()
                board.localSide = Side.WHITE; board.resetBoard(); board.isEnabled = false
                status.text = "رمز الغرفة: ${result.code} • بانتظار انضمام صديقك..."
                showGameScreen()
                startOnlinePolling()
                val inviteText = "دعوة للعب 3D Chess عبر الإنترنت${invitedFriend?.let { " مع $it" } ?: ""}. رمز الغرفة: ${result.code}"
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "دعوة للعب 3D Chess")
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }
                startActivity(Intent.createChooser(share, "إرسال دعوة اللعب"))
            }
        }.start()
    }

    private fun joinOnlineRoom(code: String) {
        status.text = "جارٍ الانضمام إلى الغرفة..."
        Thread {
            val result = auth.joinOnlineRoom(code)
            runOnUiThread {
                if (!result.ok || result.id == null) { status.text = result.message; return@runOnUiThread }
                onlineMode = true; playVsComputer = false; onlineRoomId = result.id; onlineMoves = JSONArray()
                board.localSide = Side.BLACK; board.resetBoard(); board.isEnabled = true
                applyRoomMoves(result.gameState?.optJSONArray("moves") ?: JSONArray())
                status.text = "انضممت إلى الغرفة ${result.code} • أنت الأسود"
                showGameScreen()
                startOnlinePolling()
            }
        }.start()
    }

    private fun startOnlinePolling() {
        onlinePoll?.let { mainHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val id = onlineRoomId ?: return
                Thread {
                    val result = auth.fetchOnlineRoom(id)
                    runOnUiThread {
                        if (result.ok) {
                            if (result.status == "playing") board.isEnabled = true
                            result.gameState?.optJSONArray("moves")?.let { applyRoomMoves(it) }
                        }
                    }
                }.start()
                mainHandler.postDelayed(this, 1800)
            }
        }
        onlinePoll = task
        mainHandler.post(task)
    }

    private fun applyRoomMoves(serverMoves: JSONArray) {
        if (serverMoves.length() <= onlineMoves.length()) return
        for (i in onlineMoves.length() until serverMoves.length()) {
            val item = serverMoves.optJSONObject(i) ?: continue
            val promotion = item.optString("p", "-").let { if (it == "-") null else runCatching { PieceType.valueOf(it) }.getOrNull() }
            val move = ChessMove(item.optInt("ff"), item.optInt("fr"), item.optInt("tf"), item.optInt("tr"), promotion)
            if (!board.applySyncedMove(move)) { status.text = "تعذرت مزامنة حركة المباراة"; return }
            onlineMoves.put(item)
        }
        updateGameStatus()
    }

    private fun stopOnlinePolling() {
        onlinePoll?.let { mainHandler.removeCallbacks(it) }
        onlinePoll = null
        onlineMode = false
        onlineRoomId = null
        board.isEnabled = true
    }
    private fun showAccountDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val email = EditText(this).apply { hint = "البريد الإلكتروني"; inputType = 33 }
        val password = EditText(this).apply { hint = "كلمة المرور"; inputType = 129 }
        panel.addView(email)
        panel.addView(password)
        AlertDialog.Builder(this).setTitle("حساب 3D Chess").setView(panel)
            .setPositiveButton("تسجيل الدخول") { _, _ ->
                runAuth { auth.signIn(email.text.toString().trim(), password.text.toString()) }
            }
            .setNeutralButton("إنشاء حساب") { _, _ ->
                runAuth { auth.signUp(email.text.toString().trim(), password.text.toString()) }
            }
            .setNegativeButton("Google") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.startGoogleOAuth()))) }
                catch (_: Exception) { status.text = "تعذر فتح صفحة Google" }
            }.show()
    }

    private fun showFriendsDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val username = EditText(this).apply { hint = "اسم المستخدم (3-20 حرفاً)" }
        val nickname = EditText(this).apply { hint = "الاسم الظاهر" }
        panel.addView(username)
        panel.addView(nickname)
        AlertDialog.Builder(this).setTitle("الملف الشخصي والأصدقاء").setView(panel)
            .setPositiveButton("حفظ اسمي") { _, _ ->
                runAuth { auth.savePlayerProfile(username.text.toString(), nickname.text.toString().ifBlank { username.text.toString() }) }
            }
            .setNeutralButton("إرسال طلب صداقة") { _, _ ->
                runAuth { auth.sendFriendRequest(username.text.toString()) }
            }
            .setNegativeButton("الأصدقاء والطلبات") { _, _ -> showFriendList() }.show()
    }

    private fun showFriendList() {
        status.text = "جارٍ تحميل الأصدقاء والطلبات..."
        Thread {
            val result = auth.loadFriends()
            runOnUiThread {
                if (!result.ok) { status.text = result.message; return@runOnUiThread }
                if (result.entries.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("الأصدقاء").setMessage("لا توجد صداقات أو طلبات حالياً. أضف صديقاً باستخدام اسم المستخدم.").setPositiveButton("حسناً", null).show()
                    return@runOnUiThread
                }
                val labels = result.entries.map { entry ->
                    val state = when { entry.status == "accepted" -> "صديق"; entry.incoming -> "طلب وارد"; entry.status == "pending" -> "بانتظار الرد"; else -> entry.status }
                    "${entry.nickname} (@${entry.username}) — $state"
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("الأصدقاء وطلبات الصداقة").setItems(labels) { _, index ->
                    val entry = result.entries[index]
                    if (entry.incoming) {
                        AlertDialog.Builder(this).setTitle("طلب صداقة من ${entry.nickname}")
                            .setPositiveButton("قبول") { _, _ -> runAuth { auth.respondFriendRequest(entry.userId, true) } }
                            .setNegativeButton("رفض") { _, _ -> runAuth { auth.respondFriendRequest(entry.userId, false) } }
                            .setNeutralButton("لاحقاً", null).show()
                    } else if (entry.status == "accepted") {
                        AlertDialog.Builder(this).setTitle(entry.nickname)
                            .setMessage("هذا اللاعب ضمن قائمة أصدقائك.")
                            .setPositiveButton("دعوة للعب") { _, _ -> createOnlineRoom(entry.nickname) }
                            .setNegativeButton("إغلاق", null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle(entry.nickname)
                            .setMessage("تم إرسال طلب الصداقة، وبانتظار موافقة اللاعب.")
                            .setPositiveButton("حسناً", null).show()
                    }
                }.setPositiveButton("إغلاق", null).show()
            }
        }.start()
    }

    private fun runAuth(action: () -> AuthManager.Result) {
        val emailStatus = status
        emailStatus.text = "جارٍ الاتصال بخدمة الحسابات..."
        Thread {
            val result = action()
            runOnUiThread {
                emailStatus.text = result.message
                if (result.ok && auth.hasSession()) emailStatus.text = "تم تسجيل الدخول • يمكنك متابعة اللعب عبر الإنترنت"
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "com.alfeela.chess3d") {
            val result = auth.acceptOAuthCallback(intent.data!!)
            status.text = result.message
        }
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
        background = rounded(if (highlighted) Color.rgb(91, 67, 31) else Color.rgb(28, 27, 25), gold, 2)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(width), stroke)
        cornerRadius = dp(5).toFloat()
    }

    /**
     * Resolve the phone's Wi-Fi IPv4 address even when Android has selected
     * mobile data as the default route (common when Wi-Fi has no internet).
     */
    private fun localWifiAddress(): String? {
        // Prefer Android's Wi-Fi network metadata when available.
        try {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = (listOfNotNull(connectivity.activeNetwork) +
                connectivity.allNetworks.toList()).distinct()
            for (network in networks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val properties = connectivity.getLinkProperties(network) ?: continue
                val address = properties.linkAddresses
                    .asSequence()
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                if (address != null) return address
            }
        } catch (_: Exception) {
            // Fall through to interface enumeration for devices that hide SoftAP details.
        }

        // Some Android versions do not expose the phone's own hotspot as a
        // ConnectivityManager Wi-Fi network. Inspect active interfaces as fallback.
        return try {
            val candidates = java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { address ->
                    !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                }
            candidates.firstOrNull { it.hostAddress.startsWith("192.168.") }?.hostAddress
                ?: candidates.firstOrNull { it.hostAddress.startsWith("10.") }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
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

    private fun sideName(side: Side): String =
        if (side == Side.WHITE) "الأبيض" else "الأسود"

    private fun updateGameStatus() {
        val game = board.game
        status.text = when {
            game.isCheckmate() -> "كش مات • الفائز: ${sideName(game.sideToMove.opposite())}"
            game.isStalemate() -> "تعادل • لا توجد نقلات قانونية"
            game.isInCheck(game.sideToMove) -> "كش على الملك • دور ${sideName(game.sideToMove)}"
            else -> "دور ${sideName(game.sideToMove)} • اختر قطعة ثم مربع الوجهة"
        }
    }

    override fun onDestroy() {
        stopOnlinePolling()
        lan.close()
        super.onDestroy()
    }
}
