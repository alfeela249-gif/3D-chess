package com.alfeela.chess3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ChessBoardView(context: Context) : GLSurfaceView(context) {
    val game = ChessGame()
    private val boardRenderer = BoardRenderer(game)
    var localSide: Side = Side.WHITE
    var onMove: ((ChessMove) -> Unit)? = null
    var onPromotionRequested: ((Side, (PieceType) -> Unit) -> Unit)? = null
    private var selected: Pair<Int, Int>? = null
    private var downSquare: Pair<Int, Int>? = null
    private var downX = 0f
    private var downY = 0f
    private var rotateGesture = false
    private var lastRotateX = 0f
    private var lastPointerSpan = 0f

    private fun squareAt(x: Float, y: Float): Pair<Int, Int>? =
        boardRenderer.squareAt(x, y, width, height)

    private fun animate(move: ChessMove) {
        boardRenderer.animateMove(move)
        requestRender()
        postDelayed(object : Runnable {
            override fun run() {
                if (boardRenderer.isAnimating()) {
                    requestRender()
                    postDelayed(this, 16L)
                }
            }
        }, 16L)
    }

    private fun completeMove(move: ChessMove) {
        if (game.makeMove(move)) {
            selected = null
            boardRenderer.setSelection(null, emptySet())
            animate(move)
            onMove?.invoke(move)
        }
    }

    private fun refreshSelection() {
        val moves = selected?.let { game.legalMovesFrom(it.first, it.second) }
            ?.map { it.toFile to it.toRank }?.toSet() ?: emptySet()
        boardRenderer.setSelection(selected, moves)
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(boardRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun resetBoard() {
        game.reset()
        selected = null
        boardRenderer.setSelection(null, emptySet())
        requestRender()
    }

    fun applySyncedMove(move: ChessMove): Boolean {
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); animate(move) }
        return applied
    }

    fun applyComputerMove(move: ChessMove): Boolean {
        if (game.sideToMove == localSide) return false
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); animate(move) }
        return applied
    }

    fun applyRemoteMove(move: ChessMove): Boolean {
        if (game.sideToMove == localSide) return false
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); requestRender() }
        return applied
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downSquare = squareAt(event.x, event.y)
                downX = event.x
                downY = event.y
                rotateGesture = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                rotateGesture = true
                lastRotateX = event.getX(0)
                lastPointerSpan = pointerSpan(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotateGesture && event.pointerCount >= 2) {
                    val currentX = event.getX(0)
                    val currentSpan = pointerSpan(event)
                    boardRenderer.rotateBy((currentX-lastRotateX)*0.008f)
                    if (lastPointerSpan > 0f && currentSpan > 0f) {
                        boardRenderer.zoomBy((lastPointerSpan-currentSpan)*0.012f)
                    }
                    lastRotateX = currentX
                    lastPointerSpan = currentSpan
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (rotateGesture) {
                    rotateGesture = false
                    downSquare = null
                    return true
                }
                val destination = squareAt(event.x, event.y) ?: run {
                    downSquare = null
                    return true
                }
                if (game.sideToMove != localSide) return true
                val dragged = downSquare
                val isDrag = kotlin.math.abs(event.x-downX) > 12f || kotlin.math.abs(event.y-downY) > 12f
                val source = if (isDrag) dragged else selected
                if (source != null) {
                    val candidates = game.legalMovesFrom(source.first, source.second)
                        .filter { it.toFile == destination.first && it.toRank == destination.second }
                    val candidate = candidates.firstOrNull()
                    if (candidate != null) {
                        val promotionOptions = candidates.mapNotNull { it.promotion }.distinct()
                        if (promotionOptions.isNotEmpty()) {
                            val chooser = onPromotionRequested
                            if (chooser != null) {
                                chooser(localSide) { choice ->
                                    val selectedMove = candidates.firstOrNull { it.promotion == choice } ?: candidate
                                    completeMove(selectedMove)
                                }
                            } else completeMove(candidate)
                        } else completeMove(candidate)
                    } else if (game.pieceAt(destination.first, destination.second)?.side == localSide) {
                        selected = destination
                        refreshSelection()
                        requestRender()
                    } else if (!isDrag) {
                        selected = null
                        refreshSelection()
                        requestRender()
                    }
                } else if (game.pieceAt(destination.first, destination.second)?.side == localSide) {
                    selected = destination
                    refreshSelection()
                    requestRender()
                }
                downSquare = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downSquare = null
                rotateGesture = false
                return true
            }
        }
        return true
    }
}

private class BoardRenderer(private val game: ChessGame) : GLSurfaceView.Renderer {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var program = 0
    private var positionHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0
    private var normalHandle = 0
    @Volatile private var selected: Pair<Int, Int>? = null
    @Volatile private var legalTargets: Set<Pair<Int, Int>> = emptySet()
    @Volatile private var animatedMove: ChessMove? = null
    @Volatile private var animationStart = 0L
    @Volatile private var boardRotation = 0f
    @Volatile private var cameraDistance = 15.0f
    private val animationDuration = 420L

    fun rotationRadians(): Float = boardRotation

    /**
     * Convert a screen touch to a board square using the exact perspective camera.
     * The old screen-space approximation ignored perspective foreshortening and
     * drifted badly near the far edge, especially after rotating the board.
     */
    fun squareAt(screenX: Float, screenY: Float, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        if (screenWidth <= 0 || screenHeight <= 0 ||
            screenX < 0f || screenX >= screenWidth || screenY < 0f || screenY >= screenHeight) return null

        val p = FloatArray(16)
        val v = FloatArray(16)
        val viewProjection = FloatArray(16)
        val inverse = FloatArray(16)
        val aspect = screenWidth.toFloat() / screenHeight.toFloat()
        Matrix.perspectiveM(p, 0, 52f, aspect, 0.1f, 60f)
        val distance = cameraDistance
        Matrix.setLookAtM(v, 0, 0f, distance * 0.56f, distance * 0.72f, 0f, 0.25f, 0f, 0f, 1f, 0f)
        Matrix.rotateM(v, 0, Math.toDegrees(boardRotation.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, p, 0, v, 0)
        if (!Matrix.invertM(inverse, 0, viewProjection, 0)) return null

        val ndcX = 2f * screenX / screenWidth.toFloat() - 1f
        val ndcY = 1f - 2f * screenY / screenHeight.toFloat()
        val near = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val far = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val worldNear = FloatArray(4)
        val worldFar = FloatArray(4)
        Matrix.multiplyMV(worldNear, 0, inverse, 0, near, 0)
        Matrix.multiplyMV(worldFar, 0, inverse, 0, far, 0)
        if (kotlin.math.abs(worldNear[3]) < 0.000001f || kotlin.math.abs(worldFar[3]) < 0.000001f) return null

        val nx = worldNear[0] / worldNear[3]
        val ny = worldNear[1] / worldNear[3]
        val nz = worldNear[2] / worldNear[3]
        val fx = worldFar[0] / worldFar[3]
        val fy = worldFar[1] / worldFar[3]
        val fz = worldFar[2] / worldFar[3]
        val dy = fy - ny
        if (kotlin.math.abs(dy) < 0.000001f) return null

        // Top face of the playable squares is at y = 0.06 in the renderer.
        val t = (0.06f - ny) / dy
        if (t < 0f) return null
        val boardX = nx + (fx - nx) * t
        val boardZ = nz + (fz - nz) * t
        if (boardX < -4f || boardX >= 4f || boardZ < -4f || boardZ >= 4f) return null

        val file = kotlin.math.floor(boardX + 4f).toInt()
        val rank = kotlin.math.floor(boardZ + 4f).toInt()
        return if (file in 0..7 && rank in 0..7) file to rank else null
    }
    fun rotateBy(delta: Float) { boardRotation += delta }
    fun zoomBy(delta: Float) { cameraDistance = (cameraDistance + delta).coerceIn(9.5f, 22f) }

    fun animateMove(move: ChessMove) { animatedMove = move; animationStart = android.os.SystemClock.uptimeMillis() }
    fun isAnimating(): Boolean = animatedMove != null

    private val vertexShader = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        attribute vec3 aNormal;
        varying vec3 vNormal;
        varying vec3 vPosition;
        void main(){
            vNormal = normalize(aNormal);
            vPosition = aPosition;
            gl_Position = uMvp * vec4(aPosition,1.0);
        }
    """
    private val fragmentShader = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec3 vNormal;
        varying vec3 vPosition;
        void main(){
            vec3 n = normalize(vNormal);
            vec3 lightDir = normalize(vec3(-0.45, 0.85, 0.55));
            float diffuse = max(dot(n, lightDir), 0.0);
            vec3 viewDir = normalize(vec3(0.0, 1.2, 2.0) - vPosition);
            vec3 halfDir = normalize(lightDir + viewDir);
            float specular = pow(max(dot(n, halfDir), 0.0), 42.0);
            float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0) * 0.16;
            vec3 lit = uColor.rgb * (0.28 + diffuse * 0.78 + rim) + vec3(1.0,0.83,0.55) * specular * 0.52;
            gl_FragColor = vec4(lit, uColor.a);
        }
    """
    private val cube = floatArrayOf(
        -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,-0.5f, 0.5f,0.5f,-0.5f, -0.5f,0.5f,-0.5f,
        -0.5f,-0.5f,0.5f, 0.5f,-0.5f,0.5f, 0.5f,0.5f,0.5f, -0.5f,0.5f,0.5f
    )
    private val indices = shortArrayOf(
        0,1,2,0,2,3, 4,6,5,4,7,6, 0,4,5,0,5,1,
        3,2,6,3,6,7, 1,5,6,1,6,2, 0,3,7,0,7,4
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(cube.size*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(cube); position(0) }
    private val indexBuffer = ByteBuffer.allocateDirect(indices.size*2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }

    fun setSelection(value: Pair<Int, Int>?, targets: Set<Pair<Int, Int>>) {
        selected = value
        legalTargets = targets
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.12f,0.045f,0.025f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,vs); GLES20.glAttachShader(it,fs); GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(program,"aPosition")
        mvpHandle = GLES20.glGetUniformLocation(program,"uMvp")
        colorHandle = GLES20.glGetUniformLocation(program,"uColor")
        normalHandle = GLES20.glGetAttribLocation(program,"aNormal")
        // Keep both sides visible while generated procedural meshes use per-face normals.
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0,0,width,height)
        viewportWidth = width
        viewportHeight = height
        updateProjection()
    }

    private fun updateProjection() {
        val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 52f, aspect, 0.1f, 60f)
    }

    override fun onDrawFrame(gl: GL10?) {
        updateProjection()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val distance = cameraDistance
        Matrix.setLookAtM(view,0,0f,distance*0.56f,distance*0.72f,0f,0.25f,0f,0f,1f,0f)
        Matrix.rotateM(view,0,Math.toDegrees(boardRotation.toDouble()).toFloat(),0f,1f,0f)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        // Thick polished black plinth gives the board a real raised edge.
        drawCube(0f,-0.24f,0f,8.62f,0.42f,8.62f,0.025f,0.028f,0.032f)
        drawCube(0f,-0.015f,0f,8.48f,0.08f,8.48f,0.11f,0.105f,0.10f)
        val previousMove = game.lastMove
        val checkedKing = if (game.isInCheck(game.sideToMove)) {
            (0..7).asSequence().flatMap { r -> (0..7).asSequence().map { f -> f to r } }
                .firstOrNull { (f, r) -> game.pieceAt(f, r) == ChessPiece(game.sideToMove, PieceType.KING) }
        } else null
        for (rank in 0..7) for (file in 0..7) {
            val light = (file+rank)%2==0
            val square = file to rank
            val isSelected = selected == square
            val isTarget = legalTargets.contains(square)
            val isLastMove = previousMove?.let {
                (it.fromFile == file && it.fromRank == rank) || (it.toFile == file && it.toRank == rank)
            } ?: false
            val isCheckedKing = checkedKing == square
            drawCube(file-3.5f,0f,rank-3.5f,0.98f,0.12f,0.98f,
                if (isCheckedKing) 0.82f else if (isSelected) 0.72f else if (isTarget) 0.58f else if (isLastMove) 0.58f else if(light) 0.78f else 0.16f,
                if (isCheckedKing) 0.12f else if (isSelected) 0.48f else if (isTarget) 0.48f else if (isLastMove) 0.38f else if(light) 0.74f else 0.18f,
                if (isCheckedKing) 0.10f else if (isSelected) 0.16f else if (isTarget) 0.14f else if (isLastMove) 0.12f else if(light) 0.70f else 0.20f)
            if (isTarget && game.pieceAt(file, rank) == null) {
                drawCube(file-3.5f,0.075f,rank-3.5f,0.22f,0.035f,0.22f,0.95f,0.70f,0.28f)
            }
            val piece = game.pieceAt(file, rank) ?: continue
            val white = piece.side == Side.WHITE
            val r = if (white) 0.92f else 0.10f
            val g = if (white) 0.62f else 0.11f
            val b = if (white) 0.28f else 0.13f
            var x = file-3.5f
            var z = rank-3.5f
            val moving = animatedMove
            if (moving != null) {
                val progress = ((android.os.SystemClock.uptimeMillis()-animationStart).toFloat()/animationDuration).coerceIn(0f,1f)
                val eased = progress*progress*(3f-2f*progress)
                // Animate the moved piece from its source square to its destination.
                if (moving.toFile == file && moving.toRank == rank) {
                    x = (moving.fromFile-3.5f) + ((moving.toFile-moving.fromFile)*eased)
                    z = (moving.fromRank-3.5f) + ((moving.toRank-moving.fromRank)*eased)
                }
                // Castling moves two pieces: animate the rook as well, rather than
                // teleporting it to the adjacent square while the king is moving.
                val isCastle = game.pieceAt(moving.toFile, moving.toRank)?.type == PieceType.KING &&
                    kotlin.math.abs(moving.toFile-moving.fromFile) == 2
                if (isCastle && piece.type == PieceType.ROOK) {
                    val homeRank = moving.toRank
                    val rookFrom = if (moving.toFile == 6) 7 else 0
                    val rookTo = if (moving.toFile == 6) 5 else 3
                    if (file == rookTo && rank == homeRank) {
                        x = (rookFrom-3.5f) + ((rookTo-rookFrom)*eased)
                        z = homeRank-3.5f
                    }
                }
                if (progress >= 1f) animatedMove = null
            }
            // Rounded, stacked chess-piece silhouettes in polished gold and black.
            drawCylinder(x,0.13f,z,0.34f,0.14f,r*0.68f,g*0.68f,b*0.68f)
            drawCylinder(x,0.23f,z,0.27f,0.12f,r,g,b)
            drawCylinder(x,0.36f,z,0.17f,0.20f,r*0.94f,g*0.94f,b*0.94f)
            when(piece.type) {
                PieceType.PAWN -> {
                    drawCylinder(x,0.51f,z,0.13f,0.12f,r,g,b)
                    drawSphere(x,0.66f,z,0.15f,r,g,b)
                }
                PieceType.ROOK -> {
                    drawCylinder(x,0.59f,z,0.22f,0.26f,r,g,b)
                    drawCylinder(x,0.75f,z,0.27f,0.08f,r,g,b)
                    for(k in -2..2) drawCube(x+k*0.09f,0.84f,z,0.075f,0.12f,0.075f,r,g,b)
                }
                PieceType.KNIGHT -> {
                    drawCylinder(x,0.59f,z,0.21f,0.24f,r,g,b)
                    drawSphere(x,0.81f,z,0.20f,r,g,b)
                    drawCube(x+0.10f,0.94f,z,0.17f,0.23f,0.14f,r,g,b)
                }
                PieceType.BISHOP -> {
                    drawCylinder(x,0.62f,z,0.20f,0.30f,r,g,b)
                    drawSphere(x,0.85f,z,0.13f,r,g,b)
                    drawCube(x,0.98f,z,0.045f,0.18f,0.045f,0.98f,0.80f,0.48f)
                }
                PieceType.QUEEN -> {
                    drawCylinder(x,0.63f,z,0.22f,0.34f,r,g,b)
                    drawSphere(x,0.90f,z,0.15f,r,g,b)
                    for(k in 0..5) {
                        val angle = k * 1.047f
                        drawSphere(x+kotlin.math.cos(angle)*0.17f,1.00f,z+kotlin.math.sin(angle)*0.17f,0.055f,0.98f,0.78f,0.42f)
                    }
                }
                PieceType.KING -> {
                    drawCylinder(x,0.63f,z,0.22f,0.34f,r,g,b)
                    drawSphere(x,0.90f,z,0.15f,r,g,b)
                    drawCube(x,1.12f,z,0.055f,0.30f,0.055f,0.98f,0.80f,0.48f)
                    drawCube(x,1.12f,z,0.25f,0.055f,0.055f,0.98f,0.80f,0.48f)
                }
            }
        }
    }

    private fun drawCylinder(x:Float,y:Float,z:Float,radius:Float,height:Float,r:Float,g:Float,b:Float) {
        val vertices = ArrayList<Float>()
        val segments = 40
        // Turned, multi-ring profile: beveled foot, narrow neck and rounded crown.
        val profile = listOf(
            0.00f to -0.50f, 0.78f to -0.50f, 0.94f to -0.40f,
            1.00f to -0.27f, 0.94f to -0.16f, 0.78f to -0.10f,
            0.72f to 0.02f, 0.82f to 0.12f, 0.76f to 0.20f,
            0.62f to 0.25f, 0.58f to 0.34f, 0.72f to 0.43f,
            0.66f to 0.50f, 0.00f to 0.50f
        )
        for (p in 0 until profile.size-1) {
            val (ra, ya) = profile[p]
            val (rb, yb) = profile[p+1]
            for (i in 0 until segments) {
                val a = i * 2.0 * Math.PI / segments
                val c = (i+1) * 2.0 * Math.PI / segments
                val ax = kotlin.math.cos(a).toFloat()
                val az = kotlin.math.sin(a).toFloat()
                val bx = kotlin.math.cos(c).toFloat()
                val bz = kotlin.math.sin(c).toFloat()
                val v = floatArrayOf(
                    ax*ra*radius, ya*height, az*ra*radius,
                    bx*ra*radius, ya*height, bz*ra*radius,
                    bx*rb*radius, yb*height, bz*rb*radius,
                    ax*ra*radius, ya*height, az*ra*radius,
                    bx*rb*radius, yb*height, bz*rb*radius,
                    ax*rb*radius, yb*height, az*rb*radius
                )
                v.forEach { vertices.add(it) }
            }
        }
        drawTriangles(vertices.toFloatArray(),x,y,z,r,g,b)
    }

    private fun drawSphere(x:Float,y:Float,z:Float,radius:Float,r:Float,g:Float,b:Float) {
        val vertices = ArrayList<Float>()
        val rings = 10
        val slices = 20
        for(i in 0 until rings) {
            val p1 = -Math.PI/2 + i*Math.PI/rings
            val p2 = -Math.PI/2 + (i+1)*Math.PI/rings
            for(j in 0 until slices) {
                val t1 = j*2*Math.PI/slices
                val t2 = (j+1)*2*Math.PI/slices
                fun vertex(p:Double,t:Double) = floatArrayOf((kotlin.math.cos(p)*kotlin.math.cos(t)).toFloat()*radius,kotlin.math.sin(p).toFloat()*radius,(kotlin.math.cos(p)*kotlin.math.sin(t)).toFloat()*radius)
                val a=vertex(p1,t1); val c=vertex(p1,t2); val d=vertex(p2,t2); val e=vertex(p2,t1)
                listOf(a,c,d,a,d,e).forEach { v -> v.forEach { vertices.add(it) } }
            }
        }
        drawTriangles(vertices.toFloatArray(),x,y,z,r,g,b)
    }

    private fun drawTriangles(vertices:FloatArray,x:Float,y:Float,z:Float,r:Float,g:Float,b:Float) {
        val buffer = ByteBuffer.allocateDirect(vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices); buffer.position(0)
        // Calculate a real geometric normal for every triangle (rather than
        // treating each vertex position as a normal, which caused incorrect lighting).
        val normals = FloatArray(vertices.size)
        var ni = 0
        while (ni + 8 < vertices.size) {
            val ax = vertices[ni]; val ay = vertices[ni+1]; val az = vertices[ni+2]
            val ux = vertices[ni+3]-ax; val uy = vertices[ni+4]-ay; val uz = vertices[ni+5]-az
            val vx = vertices[ni+6]-ax; val vy = vertices[ni+7]-ay; val vz = vertices[ni+8]-az
            var nx = uy*vz - uz*vy
            var ny = uz*vx - ux*vz
            var nz = ux*vy - uy*vx
            val length = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(0.0001f)
            nx /= length; ny /= length; nz /= length
            // Orient curved procedural surfaces outward from their local origin.
            val cx = (ax + vertices[ni+3] + vertices[ni+6]) / 3f
            val cy = (ay + vertices[ni+4] + vertices[ni+7]) / 3f
            val cz = (az + vertices[ni+5] + vertices[ni+8]) / 3f
            if (nx*cx + ny*cy + nz*cz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            for (v in 0..2) {
                val offset = ni + v*3
                normals[offset] = nx
                normals[offset+1] = ny
                normals[offset+2] = nz
            }
            ni += 9
        }
        val normalBuffer = ByteBuffer.allocateDirect(normals.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        normalBuffer.put(normals); normalBuffer.position(0)
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y,z)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glUniform4f(colorHandle,r,g,b,1f)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,buffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle,3,GLES20.GL_FLOAT,false,12,normalBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.size/3)
    }

    private fun drawCube(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float) {
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,sx,sy,sz)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glUniform4f(colorHandle,r,g,b,1f)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle,3,GLES20.GL_FLOAT,false,12,vertexBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,indices.size,GLES20.GL_UNSIGNED_SHORT,indexBuffer)
    }

    private fun compile(type:Int, source:String):Int {
        val shader=GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
        return shader
    }
}
