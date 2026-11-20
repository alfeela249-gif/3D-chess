package com.alfeela.chess3d

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
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
            onState = { message -> runOnUiThread { status.text = message } },
            onMessage = { message -> runOnUiThread { handleNetworkMessage(message) } }
        )
        board = ChessBoardView(this)
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 8, 10), Color.rgb(25, 23, 20), Color.rgb(8, 9, 11))
            )
        }

        val title = TextView(this).apply {
            text = "♔   3D CHESS   ♚"
            setTextColor(gold)
            textSize = 27f
            letterSpacing = 0.12f
            typeface = Typeface.create("serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(5f, 2f, 3f, Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, dp(48)))
        val subtitle = TextView(this).apply {
            text = "مملكة الشطرنج • العب وتحدَّ أصدقاءك"
            setTextColor(gold)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, dp(26)))

        val sectionTitle = TextView(this).apply {
            text = "طريقة اللعب"
            setTextColor(cream)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        root.addView(sectionTitle, LinearLayout.LayoutParams(-1, dp(34)))

        val preview = FrameLayout(this).apply {
            background = rounded(Color.rgb(14, 14, 15), gold, 2)
            clipToPadding = true
        }
        preview.addView(board, FrameLayout.LayoutParams(-1, -1))
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(2), dp(8), dp(2), dp(8))
        })

        status = TextView(this).apply {
            text = "اختر طريقة اللعب للبدء"
            setTextColor(cream)
            textSize = 14f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val aiButton = makeButton("♟  اللعب ضد الكمبيوتر  •  مستوى متوسط", true).apply {
            setOnClickListener {
                stopOnlinePolling()
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
        val onlineButton = makeButton("♟  اللعب مع الأصدقاء عبر الإنترنت", true).apply {
            setOnClickListener { showOnlineDialog() }
        }
        root.addView(onlineButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(2), 0, dp(2), dp(4))
        })
        val accountButton = makeButton("⚙  الإعدادات والحساب", false).apply {
            setOnClickListener { showSettingsDialog() }
        }
        root.addView(accountButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(2), 0, dp(2), dp(4))
        })
        val friendsButton = makeButton("♙  قائمة الأصدقاء • إضافة صديق", false).apply {
            setOnClickListener { showFriendsDialog() }
        }
        root.addView(friendsButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(2), 0, dp(2), dp(4))
        })
        val lanButton = makeButton("♟  اللعب مع الأصدقاء بدون إنترنت • Wi-Fi", true).apply {
            setOnClickListener { showLanDialog() }
        }
        root.addView(lanButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            setMargins(dp(2), 0, dp(2), dp(4))
        })

        val historyButton = makeButton("📜  سجل النقلات", false).apply {
            setOnClickListener { showMoveHistory() }
        }
        root.addView(historyButton, LinearLayout.LayoutParams(-1, dp(40)).apply {
            setMargins(dp(2), 0, dp(2), dp(3))
        })

        val reset = makeButton("مباراة جديدة", false).apply {
            setOnClickListener {
                if (onlineMode) {
                    status.text = "إعادة ضبط مباراة الإنترنت غير متاحة أثناء اللعب"
                } else {
                    board.resetBoard()
                    lan.send("RESET")
                    updateGameStatus()
                }
            }
        }
        root.addView(reset, LinearLayout.LayoutParams(-1, dp(42)).apply {
            setMargins(dp(2), 0, dp(2), dp(2))
        })
        setContentView(root)
        if (intent.data?.scheme == "com.alfeela.chess3d") {
            status.text = auth.acceptOAuthCallback(intent.data!!).message
        }
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
        val options = arrayOf("الحساب وتسجيل الدخول", "مستوى الكمبيوتر: متوسط", "معلومات التطبيق")
        AlertDialog.Builder(this)
            .setTitle("إعدادات 3D Chess")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAccountDialog()
                    1 -> AlertDialog.Builder(this).setTitle("مستوى الكمبيوتر")
                        .setMessage("المستوى الحالي: متوسط")
                        .setPositiveButton("حسناً", null).show()
                    2 -> AlertDialog.Builder(this).setTitle("3D Chess")
                        .setMessage("لعبة شطرنج ثلاثية الأبعاد • العب ضد الكمبيوتر أو تحدَّ أصدقاءك محلياً وعبر الإنترنت.")
                        .setPositiveButton("حسناً", null).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
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

    private fun localWifiAddress(): String? {
        return try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip == 0) null else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
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
        stopOnlinePolling()
        lan.close()
        super.onDestroy()
    }
}
