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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.URL
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.EditText
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.Toast
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack

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
    private var lastMatchMode: String? = null
    private var finishDialogShown = false
    private var activeMatchStartedAt: Long = 0L
    private var soundEnabled = true
    private var profileAvatarPreview: ImageView? = null
    private var selectedAvatarUri: Uri? = null
    private val profilePrefs by lazy { getSharedPreferences("player_profile", MODE_PRIVATE) }
    private val profileImageRequest = 7314

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
            context = this,
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
            soundEnabled = prefs.getBoolean("sound_enabled", true)
            board.setPieceStyle(prefs.getInt("piece_style", 0))
            board.setPieceSet(true)
            board.setMaterialOptions(prefs.getInt("piece_material", 0), prefs.getBoolean("piece_gloss", true), prefs.getInt("board_material", 0), prefs.getBoolean("board_gloss", true))
        }
        getSharedPreferences("board_appearance", MODE_PRIVATE).let { prefs ->
            board.setAppearance(
                prefs.getInt("white_piece", Color.rgb(240,230,210)),
                prefs.getInt("black_piece", Color.rgb(9,9,11)),
                prefs.getInt("light_square", Color.WHITE),
                prefs.getInt("dark_square", Color.rgb(133,139,149))
            )
            board.setSceneBackground(prefs.getInt("scene_background", Color.rgb(9,10,13)))
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

        board.onSound = { event -> playGameSound(event) }

        board.onPromotionRequested = { side, complete ->
            val choices = arrayOf("الملكة", "القلعة", "الفيل", "الحصان")
            val types = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
            AlertDialog.Builder(this)
                .setTitle("ترقية الجندي")
                .setMessage("اختر القطعة التي تريد الترقية إليها")
                .setItems(choices) { _, which ->
                    if (which in types.indices) complete(types[which])
                }
                // If the chooser is dismissed, complete the legal move as a queen
                // promotion rather than leaving the pawn stranded on the last rank.
                .setOnCancelListener { complete(PieceType.QUEEN) }
                .show()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
            background = rounded(Color.rgb(8, 8, 9), Color.rgb(224, 180, 94), 1)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        fun profileAction(label: String, width: Float = 1f, action: () -> Unit) {
            val item = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 240, 188))
                typeface = Typeface.create("serif", Typeface.BOLD)
                setOnClickListener { action() }
            }
            profileBar.addView(item, LinearLayout.LayoutParams(0, dp(42), width))
        }
        profileAction("♙  ملفي") { showAccountDialog() }
        profileBar.addView(View(this).apply { setBackgroundColor(Color.rgb(119, 89, 45)) },
            LinearLayout.LayoutParams(dp(1), dp(30)))
        profileAction("♟  أصدقائي") { showFriendsDialog() }
        profileBar.addView(View(this).apply { setBackgroundColor(Color.rgb(119, 89, 45)) },
            LinearLayout.LayoutParams(dp(1), dp(30)))
        profileAction("⚙", 0.7f) { showSettingsDialog() }
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
            text = "♔     ♕"
            setTextColor(Color.rgb(255, 211, 112))
            textSize = 64f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setShadowLayer(13f, 0f, 11f, Color.rgb(91, 51, 12))
            rotationY = -10f
            rotationX = 5f
            translationZ = dp(12).toFloat()
            elevation = dp(12).toFloat()
        }
        hero.addView(crown, LinearLayout.LayoutParams(-1, dp(76)))
        val title = TextView(this).apply {
            text = "3D CHESS"
            setTextColor(cream)
            textSize = 34f
            letterSpacing = 0.18f
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        hero.addView(title, LinearLayout.LayoutParams(-1, dp(48)))
        val subtitle = TextView(this).apply {
            text = "مملكة الشطرنج • تحدَّ أصدقاءك واصنع انتصارك"
            setTextColor(Color.rgb(211, 178, 119))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        hero.addView(subtitle, LinearLayout.LayoutParams(-1, dp(30)))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(194)).apply {
            topMargin = dp(16)
            bottomMargin = dp(8)
        })

        status = TextView(this).apply {
            text = "— اختر طريقة اللعب —"
            setTextColor(cream)
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Color.rgb(224, 180, 94))
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        homeStatus = status
        content.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        fun homeTile(icon: String, label: String, detail: String, highlighted: Boolean = false, action: () -> Unit): LinearLayout {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                elevation = dp(7).toFloat()
                rotationX = 1.5f
                background = rounded(
                    if (highlighted) Color.rgb(73, 51, 24) else Color.rgb(24, 24, 24),
                    if (highlighted) gold else Color.rgb(83, 73, 57), 1
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            tile.addView(TextView(this).apply {
                text = icon
                setTextColor(Color.rgb(245, 206, 124))
                textSize = 27f
                gravity = Gravity.CENTER
                setShadowLayer(5f, 0f, 3f, Color.BLACK)
            }, LinearLayout.LayoutParams(dp(58), -1))
            tile.addView(View(this).apply {
                setBackgroundColor(Color.rgb(136, 101, 46))
            }, LinearLayout.LayoutParams(dp(1), dp(38)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(8), 0)
            }
            copy.addView(TextView(this).apply {
                text = label
                setTextColor(if (highlighted) Color.rgb(255, 224, 164) else cream)
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                typeface = Typeface.create("serif", Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, dp(27)))
            copy.addView(TextView(this).apply {
                text = detail
                setTextColor(Color.rgb(180, 165, 140))
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            }, LinearLayout.LayoutParams(-1, dp(21)))
            tile.addView(copy, LinearLayout.LayoutParams(0, -1, 1f))
            tile.addView(TextView(this).apply {
                text = "‹"
                setTextColor(gold)
                textSize = 25f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), -1))
            return tile
        }

        val savedDepth = getSharedPreferences("game_settings", MODE_PRIVATE).getInt("computer_depth", 3)
        val savedLevel = when (savedDepth) { 1 -> "سهل"; 4 -> "صعب"; 5 -> "خبير"; else -> "متوسط" }
        val computerTile = homeTile("♟", "العب ضد الكمبيوتر", "مستوى $savedLevel • دون إنترنت") {
            stopOnlinePolling()
            beginMatch("computer")
            playVsComputer = true
            board.localSide = Side.WHITE
            board.resetBoard()
            lan.close()
            status.text = "أنت الأبيض • الكمبيوتر الأسود • مستوى $savedLevel"
            showGameScreen()
        }
        content.addView(computerTile, LinearLayout.LayoutParams(-1, dp(76)).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        })

        fun addHomeAction(tile: View, height: Int = 66) {
            content.addView(tile, LinearLayout.LayoutParams(-1, dp(height)).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            })
        }
        addHomeAction(homeTile("📶", "اللعب مع صديق عبر Wi-Fi", "اتصال محلي • دون إنترنت") {
            showLanDialog()
        }, 76)
        addHomeAction(homeTile("🌐", "اللعب عبر الإنترنت", "تحدَّ أصدقاءك أينما كانوا") {
            showOnlineDialog()
        }, 76)
        addHomeAction(homeTile("📜", "سجل المباريات", "تفاصيل مباريات الكمبيوتر • Wi-Fi • الإنترنت") {
            showMatchHistory()
        }, 76)

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
        topBar.addView(back, LinearLayout.LayoutParams(0, dp(46), 1.15f))
        val title = TextView(this).apply { text = "♛  3D CHESS  ♚"; setTextColor(gold); textSize = 18f; gravity = Gravity.CENTER; typeface = Typeface.create("serif", Typeface.BOLD) }
        topBar.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
        val historyButton = makeButton("النقلات", false).apply {
            textSize = 11f
            setOnClickListener { showMoveHistory() }
        }
        topBar.addView(historyButton, LinearLayout.LayoutParams(0, dp(42), 0.72f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
        gameRoot.addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
        val gameStatus = TextView(this).apply { setTextColor(cream); textSize = 14f; gravity = Gravity.CENTER; text = homeStatus.text; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        status = gameStatus
        gameRoot.addView(gameStatus, LinearLayout.LayoutParams(-1, dp(52)))
        val boardArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(10, 12, 15), gold, 2)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val whiteCaptures = TextView(this).apply {
            text = "الأبيض: —"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(246, 239, 222))
            typeface = Typeface.create("serif", Typeface.BOLD)
            maxLines = 1
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = rounded(Color.rgb(18, 16, 13), Color.rgb(125, 93, 48), 1)
        }
        val blackCaptures = TextView(this).apply {
            text = "الأسود: —"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(205, 205, 205))
            typeface = Typeface.create("serif", Typeface.BOLD)
            maxLines = 1
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = rounded(Color.rgb(18, 16, 13), Color.rgb(125, 93, 48), 1)
        }
        board.onCapturedPiecesChanged = { white, black ->
            fun symbols(pieces: List<ChessPiece>): String = if (pieces.isEmpty()) "—" else
                pieces.joinToString(" ") { piece ->
                    when (piece.type) {
                        PieceType.PAWN -> if (piece.side == Side.WHITE) "♙" else "♟"
                        PieceType.ROOK -> if (piece.side == Side.WHITE) "♖" else "♜"
                        PieceType.KNIGHT -> if (piece.side == Side.WHITE) "♘" else "♞"
                        PieceType.BISHOP -> if (piece.side == Side.WHITE) "♗" else "♝"
                        PieceType.QUEEN -> if (piece.side == Side.WHITE) "♕" else "♛"
                        PieceType.KING -> if (piece.side == Side.WHITE) "♔" else "♚"
                    }
                }
            runOnUiThread {
                whiteCaptures.text = "الأبيض: ${symbols(white)}"
                blackCaptures.text = "الأسود: ${symbols(black)}"
            }
        }
        board.onCapturedPiecesChanged?.invoke(board.game.capturedPieces(Side.WHITE), board.game.capturedPieces(Side.BLACK))
        (board.parent as? android.view.ViewGroup)?.removeView(board)
        board.set2DMode(getSharedPreferences("game_settings", MODE_PRIVATE).getBoolean("board_2d_mode", false))
        boardArea.addView(blackCaptures, LinearLayout.LayoutParams(-1, dp(34)))
        boardArea.addView(board, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        })
        boardArea.addView(whiteCaptures, LinearLayout.LayoutParams(-1, dp(34)))
        gameRoot.addView(boardArea, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        val displayModeButton = makeButton(if (board.is2DMode()) "عرض 3D" else "عرض 2D", false).apply {
            textSize = 11f
            setOnClickListener {
                val use2D = !board.is2DMode()
                board.set2DMode(use2D)
                getSharedPreferences("game_settings", MODE_PRIVATE).edit().putBoolean("board_2d_mode", use2D).apply()
                text = if (use2D) "عرض 3D" else "عرض 2D"
                Toast.makeText(this@MainActivity, if (use2D) "تم تحويل الرقعة إلى العرض ثنائي الأبعاد" else "تمت العودة إلى العرض ثلاثي الأبعاد", Toast.LENGTH_SHORT).show()
            }
        }
        val soundButton = makeButton(if (soundEnabled) "الصوت 🔊" else "الصوت 🔇", false).apply {
            textSize = 11f
            setOnClickListener {
                soundEnabled = !soundEnabled
                getSharedPreferences("game_settings", MODE_PRIVATE).edit()
                    .putBoolean("sound_enabled", soundEnabled).apply()
                text = if (soundEnabled) "الصوت 🔊" else "الصوت 🔇"
                if (soundEnabled) playGameSound("move")
                Toast.makeText(this@MainActivity,
                    if (soundEnabled) "تم تشغيل الصوت" else "تم إيقاف الصوت",
                    Toast.LENGTH_SHORT).show()
            }
        }
        val inGameControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        inGameControls.addView(displayModeButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        })
        inGameControls.addView(soundButton, LinearLayout.LayoutParams(0, dp(44), 0.82f).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        })
        gameRoot.addView(inGameControls, LinearLayout.LayoutParams(-1, dp(48)))
        gameRoot.addView(TextView(this).apply {
            text = "اسحب بإصبع واحد لتحريك زاوية الرؤية • قرّب أو أبعد بإصبعين"
            setTextColor(Color.rgb(218, 190, 145))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(24)))
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
        lastMatchMode = mode
        finishDialogShown = false
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 6, 8), Color.rgb(24, 19, 13), Color.rgb(5, 6, 8)))
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = makeButton("‹ رجوع", false).apply {
            setOnClickListener { setContentView(homeRoot); status = homeStatus }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(92), dp(44)))
        header.addView(TextView(this).apply {
            text = "⚙  إعدادات الخامات"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(gold)
            typeface = Typeface.create("serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(50)))

        fun sectionTitle(label: String) {
            content.addView(TextView(this).apply {
                text = label
                textSize = 18f
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setTextColor(gold)
                typeface = Typeface.create("serif", Typeface.BOLD)
                setPadding(dp(4), dp(8), dp(4), dp(4))
            }, LinearLayout.LayoutParams(-1, dp(42)))
        }
        fun selector(label: String, values: List<String>, initial: Int, onSelect: (Int) -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(39, 28, 17), Color.rgb(15, 13, 11))).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(157, 117, 55))
                }
            }
            card.addView(TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(cream)
            }, LinearLayout.LayoutParams(-1, dp(28)))
            val current = TextView(this).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 220, 155))
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            var selected = initial.coerceIn(values.indices)
            val symbols = if (label.contains("القطع")) listOf("♔", "♔", "♟", "♟", "♛", "♛")
                else listOf("▦", "▦", "▤", "▤", "▥", "▥")
            val horizontal = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val tileViews = mutableListOf<LinearLayout>()
            lateinit var refresh: () -> Unit
            values.forEachIndexed { index, value ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selected = index; refresh(); onSelect(selected) }
                }
                tile.addView(TextView(this).apply {
                    text = symbols[index]
                    textSize = 27f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(255, 226, 176))
                }, LinearLayout.LayoutParams(dp(70), dp(52)))
                tile.addView(TextView(this).apply {
                    text = value
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(cream)
                }, LinearLayout.LayoutParams(dp(70), dp(36)))
                tiles.addView(tile, LinearLayout.LayoutParams(dp(78), dp(96)).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
                tileViews.add(tile)
            }
            horizontal.addView(tiles)
            card.addView(horizontal, LinearLayout.LayoutParams(-1, dp(104)))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val previous = makeButton("‹", true)
            val next = makeButton("›", true)
            refresh = {
                current.text = values[selected]
                tileViews.forEachIndexed { index, tile ->
                    tile.background = rounded(
                        if (index == selected) Color.rgb(75, 52, 24) else Color.rgb(34, 29, 23),
                        if (index == selected) Color.rgb(255, 195, 83) else Color.rgb(105, 83, 55),
                        if (index == selected) 2 else 1
                    )
                }
                previous.isEnabled = selected > 0
                next.isEnabled = selected < values.lastIndex
                previous.alpha = if (previous.isEnabled) 1f else 0.35f
                next.alpha = if (next.isEnabled) 1f else 0.35f
            }
            previous.setOnClickListener {
                if (selected > 0) { selected--; refresh(); onSelect(selected); horizontal.post { horizontal.smoothScrollTo(tileViews[selected].left, 0) } }
            }
            next.setOnClickListener {
                if (selected < values.lastIndex) { selected++; refresh(); onSelect(selected); horizontal.post { horizontal.smoothScrollTo(tileViews[selected].left, 0) } }
            }
            controls.addView(previous, LinearLayout.LayoutParams(dp(58), dp(40)))
            controls.addView(current, LinearLayout.LayoutParams(0, dp(40), 1f))
            controls.addView(next, LinearLayout.LayoutParams(dp(58), dp(40)))
            card.addView(controls, LinearLayout.LayoutParams(-1, dp(42)))
            refresh()
            content.addView(card, LinearLayout.LayoutParams(-1, dp(164)).apply {
                topMargin = dp(5); bottomMargin = dp(5)
            })
        }

        sectionTitle("♟  خامة القطع ولمعانها")
        val pieceOptions = listOf("رخام لامع", "رخام مطفي", "خشب لامع", "خشب مطفي", "بلاستيك لامع", "بلاستيك مطفي")
        val pieceInitial = (prefs.getInt("piece_material", 0).coerceIn(0, 2) * 2) +
            (if (prefs.getBoolean("piece_gloss", true)) 0 else 1)
        selector("اختر خامة القطع", pieceOptions, pieceInitial) { value ->
            prefs.edit()
                .putInt("piece_material", value / 2)
                .putBoolean("piece_gloss", value % 2 == 0)
                .apply()
            applyMaterialOptions()
        }

        sectionTitle("▦  خامة الرقعة ولمعانها")
        val boardOptions = listOf("رخام مطفي", "رخام لامع", "خشب مطفي", "خشب لامع", "بلاستيك مطفي", "بلاستيك لامع")
        val oldBoardMaterial = prefs.getInt("board_material", 0).coerceIn(0, 2)
        val boardInitial = oldBoardMaterial * 2 + (if (prefs.getBoolean("board_gloss", true)) 1 else 0)
        selector("اختر خامة الرقعة", boardOptions, boardInitial) { value ->
            prefs.edit()
                .putInt("board_material", value / 2)
                .putBoolean("board_gloss", value % 2 == 1)
                .apply()
            applyMaterialOptions()
        }

        sectionTitle("إعدادات أخرى")
        val extras = listOf(
            "ألوان القطع والرقعة" to { showAppearanceDialog() },
            "أشكال القطع" to {
                val styles = arrayOf("كلاسيكي", "عصري", "ملكي")
                AlertDialog.Builder(this).setTitle("اختر شكل القطع").setSingleChoiceItems(styles,
                    prefs.getInt("piece_style", 0).coerceIn(0, 2)) { dialog, selected ->
                    prefs.edit().putInt("piece_style", selected).apply()
                    board.setPieceStyle(selected); dialog.dismiss()
                }.setNegativeButton("إغلاق", null).show()
            },
            "صعوبة الكمبيوتر" to {
                val levels = arrayOf("سهل", "متوسط", "صعب", "خبير")
                val depths = intArrayOf(1, 3, 4, 5)
                AlertDialog.Builder(this).setTitle("صعوبة الكمبيوتر")
                    .setSingleChoiceItems(levels, depths.indexOf(prefs.getInt("computer_depth", 3)).coerceAtLeast(0)) { dialog, selected ->
                        prefs.edit().putInt("computer_depth", depths[selected]).apply(); dialog.dismiss()
                    }.setNegativeButton("إغلاق", null).show()
            },
            "حساسية الكاميرا" to {
                val controls = arrayOf("هادئ", "متوازن", "سريع")
                val values = floatArrayOf(0.65f, 1f, 1.4f)
                AlertDialog.Builder(this).setTitle("حساسية الكاميرا")
                    .setSingleChoiceItems(controls, 1) { dialog, selected ->
                        prefs.edit().putFloat("camera_sensitivity", values[selected]).apply()
                        board.cameraTouchSensitivity = values[selected]; dialog.dismiss()
                    }.setNegativeButton("إغلاق", null).show()
            },
        )
        extras.take(4).forEach { (label, action) ->
            content.addView(makeButton(label, false).apply {
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        }
        content.addView(makeButton(if (soundEnabled) "إيقاف الصوت 🔇" else "تشغيل الصوت 🔊", false).apply {
            setOnClickListener {
                soundEnabled = !soundEnabled
                prefs.edit().putBoolean("sound_enabled", soundEnabled).apply()
                text = if (soundEnabled) "إيقاف الصوت 🔇" else "تشغيل الصوت 🔊"
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        content.addView(makeButton("الحساب وتسجيل الدخول", false).apply {
            setOnClickListener { showAccountDialog() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(4) })
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showMaterialOptionsDialog() {
        val prefs = getSharedPreferences("game_settings", MODE_PRIVATE)
        val options = arrayOf("خامة القطع", "لمعة القطع", "خامة الرقعة", "لمعة الرقعة")
        AlertDialog.Builder(this).setTitle("تخصيص الخامات واللمعة").setItems(options) { _, which ->
            val choices = when (which) {
                0 -> arrayOf("رخام", "خشب")
                1 -> arrayOf("بلمعة", "بدون لمعة")
                2 -> arrayOf("زجاج", "رخام", "خشب")
                else -> arrayOf("بلمعة", "بدون لمعة")
            }
            val key = when (which) { 0 -> "piece_material"; 1 -> "piece_gloss"; 2 -> "board_material"; else -> "board_gloss" }
            val current = when (which) {
                0 -> prefs.getInt(key, 0)
                1 -> if (prefs.getBoolean(key, true)) 0 else 1
                2 -> prefs.getInt(key, 0)
                else -> if (prefs.getBoolean(key, true)) 0 else 1
            }
            AlertDialog.Builder(this).setTitle(options[which]).setSingleChoiceItems(choices, current) { dialog, selected ->
                if (which == 1 || which == 3) prefs.edit().putBoolean(key, selected == 0).apply()
                else prefs.edit().putInt(key, selected).apply()
                applyMaterialOptions()
                dialog.dismiss()
                Toast.makeText(this, "تم حفظ اختيارك", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("إغلاق", null).show()
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun applyMaterialOptions() {
        val prefs = getSharedPreferences("game_settings", MODE_PRIVATE)
        board.setMaterialOptions(prefs.getInt("piece_material", 0), prefs.getBoolean("piece_gloss", true),
            prefs.getInt("board_material", 0), prefs.getBoolean("board_gloss", true))
    }

    private fun showAppearanceDialog() {
        val prefs = getSharedPreferences("board_appearance", MODE_PRIVATE)
        val labels = arrayOf("قطع الأبيض", "قطع الأسود", "المربعات الفاتحة", "المربعات الداكنة", "خلفية المشهد")
        val keys = arrayOf("white_piece", "black_piece", "light_square", "dark_square", "scene_background")
        val palettes = arrayOf(
            arrayOf("عاجي", "أبيض ناصع", "ذهبي", "أزرق جليدي"),
            arrayOf("أسود لامع", "كحلي", "أحمر داكن", "أخضر زمردي"),
            arrayOf("أبيض", "عاجي", "رمادي فاتح", "أزرق ثلجي"),
            arrayOf("رمادي", "فحمي", "أزرق داكن", "بني خشبي"),
            arrayOf("أسود كلاسيكي", "رمادي فحمي", "أزرق ليلي", "بني داكن", "أخضر داكن", "عنابي")
        )
        val colors = arrayOf(
            intArrayOf(Color.rgb(240,230,210), Color.WHITE, Color.rgb(220,178,91), Color.rgb(160,205,235)),
            intArrayOf(Color.rgb(9,9,11), Color.rgb(22,35,62), Color.rgb(112,30,38), Color.rgb(20,92,68)),
            intArrayOf(Color.WHITE, Color.rgb(242,232,212), Color.rgb(220,224,230), Color.rgb(220,235,245)),
            intArrayOf(Color.rgb(133,139,149), Color.rgb(65,70,78), Color.rgb(42,65,92), Color.rgb(112,78,52)),
            intArrayOf(Color.rgb(9,10,13), Color.rgb(38,40,45), Color.rgb(13,27,48), Color.rgb(43,29,20), Color.rgb(15,39,30), Color.rgb(48,20,29))
        )
        fun apply() {
            board.setAppearance(
                prefs.getInt(keys[0], colors[0][0]),
                prefs.getInt(keys[1], colors[1][0]),
                prefs.getInt(keys[2], colors[2][0]),
                prefs.getInt(keys[3], colors[3][0])
            )
            board.setSceneBackground(prefs.getInt(keys[4], colors[4][0]))
        }
        apply()
        fun choose(index: Int) {
            AlertDialog.Builder(this).setTitle("اختر لون " + labels[index])
                .setSingleChoiceItems(palettes[index], -1) { dialog, which ->
                    prefs.edit().putInt(keys[index], colors[index][which]).apply()
                    apply()
                    dialog.dismiss()
                    if (index < labels.lastIndex) choose(index + 1)
                    else AlertDialog.Builder(this).setMessage("تم تطبيق الألوان مباشرة وحفظها على هذا الجهاز.").setPositiveButton("حسناً", null).show()
                }.setNegativeButton("تخطي") { dialog, _ ->
                    dialog.dismiss()
                    if (index < labels.lastIndex) choose(index + 1)
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
        if (auth.hasSession()) {
            showPlayerProfileDialog()
            return
        }
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

    private fun showPlayerProfileDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(42, 34, 22))
                setStroke(dp(2), gold)
            }
            clipToOutline = true
            val saved = profilePrefs.getString("avatar_uri", null)
            if (!saved.isNullOrBlank()) setImageURI(Uri.parse(saved))
        }
        profileAvatarPreview = avatar
        selectedAvatarUri = profilePrefs.getString("avatar_uri", null)?.let { Uri.parse(it) }
        panel.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(104)))
        val choosePhoto = makeButton("اختيار صورة من الهاتف أو المعرض", false).apply {
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(picker, profileImageRequest)
            }
        }
        panel.addView(choosePhoto, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        val username = EditText(this).apply {
            hint = "اسم المستخدم بالإنجليزية (3-20 حرفاً أو رقم أو _)"
            setText(profilePrefs.getString("username", ""))
            setSingleLine(true)
        }
        val nickname = EditText(this).apply {
            hint = "الاسم الظاهر"
            setText(profilePrefs.getString("nickname", ""))
            setSingleLine(true)
        }
        panel.addView(username)
        panel.addView(nickname)
        Thread {
            val remote = auth.loadPlayerProfile()
            if (remote != null) runOnUiThread {
                if (username.text.isNullOrBlank()) username.setText(remote.username)
                if (nickname.text.isNullOrBlank()) nickname.setText(remote.nickname)
                if (selectedAvatarUri == null && !remote.avatarUrl.isNullOrBlank()) {
                    Thread {
                        val bitmap = try { URL(remote.avatarUrl).openStream().use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                        if (bitmap != null) runOnUiThread { avatar.setImageBitmap(bitmap) }
                    }.start()
                }
            }
        }.start()
        AlertDialog.Builder(this).setTitle("ملفي الشخصي").setView(panel)
            .setPositiveButton("حفظ الملف الشخصي") { _, _ ->
                val user = username.text.toString().trim()
                val display = nickname.text.toString().trim().ifBlank { user }
                if (!user.matches(Regex("[A-Za-z0-9_]{3,20}"))) {
                    Toast.makeText(this, "استخدم 3 إلى 20 حرفاً إنجليزياً أو رقماً أو _ لاسم المستخدم", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                status.text = "جارٍ حفظ الملف الشخصي..."
                val photoUri = selectedAvatarUri
                Thread {
                    var avatarUrl = auth.loadPlayerProfile()?.avatarUrl
                    if (photoUri != null) {
                        val imageBytes = encodeAvatar(photoUri)
                        if (imageBytes == null) {
                            runOnUiThread { status.text = "تعذر قراءة الصورة المختارة." }
                            return@Thread
                        }
                        val upload = auth.uploadPlayerAvatar(imageBytes)
                        if (!upload.first.ok || upload.second == null) {
                            runOnUiThread { status.text = upload.first.message }
                            return@Thread
                        }
                        avatarUrl = upload.second
                    }
                    val result = auth.savePlayerProfile(user, display, avatarUrl)
                    runOnUiThread {
                        status.text = result.message
                        if (result.ok) {
                            profilePrefs.edit().putString("username", user).putString("nickname", display).apply()
                            Toast.makeText(this, "تم حفظ الملف الشخصي", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNeutralButton("تسجيل الخروج") { _, _ ->
                auth.signOut()
                status.text = "تم تسجيل الخروج من الحساب"
            }
            .setNegativeButton("إغلاق", null).show()
    }

    @Deprecated("Use the document picker result for profile photos")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == profileImageRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            selectedAvatarUri = uri
            profilePrefs.edit().putString("avatar_uri", uri.toString()).apply()
            profileAvatarPreview?.setImageURI(uri)
            Toast.makeText(this, "تم اختيار صورة الملف الشخصي", Toast.LENGTH_SHORT).show()
        }
    }

    private fun encodeAvatar(uri: Uri): ByteArray? {
        return try {
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = if (source.width > 512 || source.height > 512) {
                val ratio = minOf(512f / source.width, 512f / source.height)
                Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1), true)
            } else source
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)
            if (scaled !== source) scaled.recycle()
            source.recycle()
            output.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun showFriendsDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val username = EditText(this).apply { hint = "اسم المستخدم لإرسال طلب صداقة" }
        panel.addView(username)
        AlertDialog.Builder(this).setTitle("الأصدقاء").setView(panel)
            .setPositiveButton("إرسال طلب صداقة") { _, _ ->
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
        when {
            game.isCheckmate() -> {
                val winner = game.sideToMove.opposite()
                status.text = "كش مات • الفائز: ${sideName(winner)}"
                if (!finishDialogShown) showMatchFinishedDialog(winner, false)
            }
            game.isStalemate() -> {
                status.text = "تعادل • لا توجد نقلات قانونية"
                if (!finishDialogShown) showMatchFinishedDialog(null, true)
            }
            game.isInCheck(game.sideToMove) -> status.text = "كش على الملك • دور ${sideName(game.sideToMove)}"
            else -> status.text = "دور ${sideName(game.sideToMove)} • اختر قطعة ثم مربع الوجهة"
        }
    }

    private fun showMatchFinishedDialog(winner: Side?, draw: Boolean) {
        finishDialogShown = true
        saveActiveMatch()
        val localWon = winner != null && winner == board.localSide
        val playerName = profilePrefs.getString("nickname", null)?.takeIf { it.isNotBlank() }
            ?: profilePrefs.getString("username", null)?.takeIf { it.isNotBlank() }
            ?: "أنت"
        val heading = when {
            draw -> "تعادل!"
            localWon -> "انتصار $playerName 🏆"
            else -> "هزيمة $playerName"
        }
        val detail = if (draw) "انتهت المباراة بالتعادل." else
            "الفائز: ${sideName(winner!!)}\n${if (localWon) "أحسنت اللعب!" else "حظاً أوفر في المباراة القادمة."}"
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(8))
            background = rounded(Color.rgb(20, 17, 13), gold, 1)
        }
        val emblem = TextView(this).apply {
            text = if (draw) "🤝" else if (localWon) "♛" else "♟"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(gold)
        }
        panel.addView(emblem, LinearLayout.LayoutParams(-1, dp(66)))
        val resultTitle = TextView(this).apply {
            text = heading
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(gold)
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(resultTitle, LinearLayout.LayoutParams(-1, -2))
        val message = TextView(this).apply {
            text = detail
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(cream)
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
        }
        panel.addView(message, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setNeutralButton("العودة إلى الصفحة الرئيسية", null)
            .setNegativeButton("مراجعة المباراة السابقة", null)
            .setPositiveButton("مباراة جديدة", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                onlinePoll?.let { mainHandler.removeCallbacks(it) }
                lan.close()
                setContentView(homeRoot)
                status = homeStatus
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dialog.dismiss()
                showMoveHistory()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                dialog.dismiss()
                startAnotherMatch()
            }
        }
        dialog.show()
    }

    private fun startAnotherMatch() {
        val mode = lastMatchMode ?: activeMatchMode ?: "computer"
        beginMatch(mode)
        board.resetBoard()
        finishDialogShown = false
        when (mode) {
            "computer" -> {
                playVsComputer = true
                onlineMode = false
                lan.close()
                board.localSide = Side.WHITE
                status.text = "مباراة جديدة • أنت الأبيض والكمبيوتر الأسود"
            }
            "wifi" -> {
                playVsComputer = false
                onlineMode = false
                lan.send("RESET")
                status.text = "مباراة جديدة • دور الأبيض"
            }
            "online" -> {
                playVsComputer = false
                onlineMode = true
                onlineMoves = JSONArray()
                val roomId = onlineRoomId
                if (roomId != null) Thread {
                    val result = auth.submitOnlineMoves(roomId, onlineMoves, "white")
                    runOnUiThread { if (!result.ok) status.text = result.message }
                }.start()
                status.text = "مباراة جديدة • دور الأبيض"
            }
            else -> status.text = "مباراة جديدة • دور الأبيض"
        }
    }

    private fun playGameSound(event: String) {
        if (!soundEnabled) return

        // Short synthesized effects give the board a warmer, less electronic feel
        // without adding large audio assets or blocking chess interaction.
        Thread({
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                val durationMs = when (event) {
                    "move" -> 105
                    "capture" -> 190
                    "check" -> 300
                    "mate" -> 620
                    else -> 105
                }
                val count = sampleRate * durationMs / 1000
                val samples = ShortArray(count)
                val random = java.util.Random()

                fun toneAt(time: Double, frequency: Double): Double {
                    return kotlin.math.sin(2.0 * Math.PI * frequency * time)
                }

                for (i in 0 until count) {
                    val t = i.toDouble() / sampleRate
                    val progress = i.toDouble() / count
                    var value = 0.0

                    when (event) {
                        "move" -> {
                            val decay = Math.exp(-t * 34.0)
                            val body = toneAt(t, 185.0) * 0.42 + toneAt(t, 310.0) * 0.16
                            val click = (random.nextDouble() * 2.0 - 1.0) * Math.exp(-t * 95.0) * 0.30
                            value = (body + click) * decay
                        }
                        "capture" -> {
                            val first = Math.exp(-t * 24.0)
                            val secondTime = (t - 0.075).coerceAtLeast(0.0)
                            val second = if (t >= 0.075) Math.exp(-secondTime * 30.0) else 0.0
                            val knock = toneAt(t, 145.0) * first * 0.48 +
                                toneAt(secondTime, 205.0) * second * 0.40
                            val texture = (random.nextDouble() * 2.0 - 1.0) *
                                Math.exp(-t * 70.0) * 0.22
                            value = knock + texture
                        }
                        "check" -> {
                            val fade = (1.0 - progress).coerceAtLeast(0.0)
                            val pulse = 0.5 + 0.5 * kotlin.math.sin(2.0 * Math.PI * 5.0 * t)
                            value = (toneAt(t, 660.0) * 0.48 +
                                toneAt(t, 880.0) * 0.22) * pulse * fade
                        }
                        "mate" -> {
                            val fadeIn = (progress / 0.06).coerceAtMost(1.0)
                            val fadeOut = ((1.0 - progress) / 0.30).coerceAtMost(1.0)
                            val envelope = fadeIn * fadeOut
                            val chord = toneAt(t, 392.0) * 0.34 +
                                toneAt(t, 494.0) * 0.30 +
                                toneAt(t, 587.0) * 0.24
                            value = chord * envelope
                        }
                    }

                    // Gentle attack/release envelope prevents clicks at sample edges.
                    val edge = (i.toDouble() / (sampleRate * 0.008)).coerceAtMost(1.0) *
                        ((count - 1 - i).toDouble() / (sampleRate * 0.025)).coerceAtMost(1.0)
                    val normalized = (value * edge * 0.48).coerceIn(-0.92, 0.92)
                    samples[i] = (normalized * Short.MAX_VALUE).toInt().toShort()
                }

                val minimumBuffer = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimumBuffer, samples.size * 2),
                    AudioTrack.MODE_STREAM
                )
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.setVolume(0.9f)
                    track.play()
                    var offset = 0
                    while (offset < samples.size) {
                        val written = track.write(samples, offset, samples.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                    if (offset == samples.size) Thread.sleep(35L)
                }
            } catch (_: Exception) {
                // Audio is optional; never interrupt a chess move if unavailable.
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
            }
        }, "chess-audio").start()
    }

    override fun onDestroy() {
        stopOnlinePolling()
        lan.close()
        super.onDestroy()
    }
}
