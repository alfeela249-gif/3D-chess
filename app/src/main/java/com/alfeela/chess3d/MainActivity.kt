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
                    status.text = when {
                        message.startsWith("Waiting for opponent") ->
                            "بانتظار الصديق • عنوانك ${localWifiAddress() ?: "غير متاح"} • المنفذ 45454"
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
                    val computerMove = board.game.chooseComputerMove(3)
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
        }
        val crown = TextView(this).apply {
            text = "♛     ♚"
            setTextColor(gold)
            textSize = 42f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setShadowLayer(12f, 0f, 3f, Color.rgb(120, 75, 20))
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

        val computerTile = homeTile("▶  العب ضد الكمبيوتر", "مستوى متوسط • دون إنترنت", true) {
            stopOnlinePolling()
            playVsComputer = true
            board.localSide = Side.WHITE
            board.resetBoard()
            lan.close()
            status.text = "أنت الأبيض • الكمبيوتر الأسود • مستوى متوسط"
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
        addHomeAction(homeTile("⚙  الإعدادات", "الألوان • الصوت • تفضيلات اللعبة") {
            showSettingsDialog()
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
        val cameraControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun cameraButton(label: String, action: () -> Unit) {
            val button = makeButton(label, false).apply {
                textSize = 17f
                setOnClickListener { action() }
            }
            cameraControls.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        cameraButton("يسار") { board.rotateCamera(-0.45f) }
        cameraButton("يمين") { board.rotateCamera(0.45f) }
        cameraButton("تقريب +") { board.zoomCamera(-1.4f) }
        cameraButton("إبعاد −") { board.zoomCamera(1.4f) }
        cameraButton("إعادة") { board.resetCamera() }
        gameRoot.addView(cameraControls, LinearLayout.LayoutParams(-1, dp(48)))
        gameRoot.addView(TextView(this).apply {
            text = "اسحب فوق مربع فارغ للتدوير • إصبعان للتدوير والتكبير • استخدم الأزرار أسفل اللوحة"
            setTextColor(Color.rgb(205, 181, 139))
            textSize = 10f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(24)))
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
        val options = arrayOf("الحساب وتسجيل الدخول", "مستوى الكمبيوتر: متوسط", "ألوان القطع والرقعة", "معلومات التطبيق")
        AlertDialog.Builder(this)
            .setTitle("إعدادات 3D Chess")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAccountDialog()
                    1 -> AlertDialog.Builder(this).setTitle("مستوى الكمبيوتر")
                        .setMessage("المستوى الحالي: متوسط")
                        .setPositiveButton("حسناً", null).show()
                    2 -> showAppearanceDialog()
                    3 -> AlertDialog.Builder(this).setTitle("3D Chess")
                        .setMessage("لعبة شطرنج ثلاثية الأبعاد • العب ضد الكمبيوتر أو تحدَّ أصدقاءك محلياً وعبر الإنترنت.")
                        .setPositiveButton("حسناً", null).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("اللعب مع الأصدقاء بدون إنترنت")
            .setMessage("اتصل أنت وصديقك بشبكة Wi-Fi نفسها أو بنقطة اتصال أحد الهاتفين. لا تحتاج هذه المباراة إلى إنترنت أو تسجيل دخول.")
            .setPositiveButton("إنشاء مباراة • الأبيض") { _, _ ->
                stopOnlinePolling()
                playVsComputer = false
                board.localSide = Side.WHITE
                board.resetBoard()
                lan.host()
                showGameScreen()
                val address = localWifiAddress()
                status.text = if (address != null)
                    "أنت الأبيض • أعطِ صديقك العنوان $address والمنفذ 45454"
                else "أنت الأبيض • تعذر قراءة عنوان Wi-Fi؛ تأكد من اتصال الهاتف بالشبكة أو نقطة الاتصال"
            }
            .setNegativeButton("انضمام • الأسود") { _, _ ->
                val ip = EditText(this).apply { hint = "عنوان IP للمضيف مثل 192.168.1.10" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("الاتصال بصديقك")
                    .setMessage("أدخل عنوان IP للهاتف الذي أنشأ المباراة.")
                    .setView(ip)
                    .setPositiveButton("اتصال") { _, _ ->
                        val address = ip.text.toString().trim()
                        if (address.isNotEmpty()) {
                            stopOnlinePolling()
                            playVsComputer = false
                            board.localSide = Side.BLACK
                            board.resetBoard()
                            status.text = "جارٍ الاتصال بصديقك عبر Wi-Fi..."
                            lan.join(address)
                            showGameScreen()
                        } else {
                            status.text = "أدخل عنوان IP صحيحاً للاتصال"
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            .setNeutralButton("إغلاق", null)
            .show()
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
        return try {
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
            null
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
