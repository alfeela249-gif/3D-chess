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
    private var selected: Pair<Int, Int>? = null
    private var downSquare: Pair<Int, Int>? = null
    private var downX = 0f
    private var downY = 0f

    private fun squareAt(x: Float, y: Float): Pair<Int, Int>? {
        val size = minOf(width, height).toFloat().coerceAtLeast(1f)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        if (x < left || x >= left + size || y < top || y >= top + size) return null
        return ((x-left)/size*8).toInt().coerceIn(0,7) to ((y-top)/size*8).toInt().coerceIn(0,7)
    }

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downSquare = squareAt(event.x, event.y)
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val destination = squareAt(event.x, event.y) ?: run {
                    downSquare = null
                    return true
                }
                if (game.sideToMove != localSide) return true
                val dragged = downSquare
                val isDrag = kotlin.math.abs(event.x-downX) > 12f || kotlin.math.abs(event.y-downY) > 12f
                val source = if (isDrag) dragged else selected
                if (source != null) {
                    val candidate = game.legalMovesFrom(source.first, source.second)
                        .firstOrNull { it.toFile == destination.first && it.toRank == destination.second }
                    if (candidate != null && game.makeMove(candidate)) {
                        selected = null
                        boardRenderer.setSelection(null, emptySet())
                        animate(candidate)
                        onMove?.invoke(candidate)
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
    private var program = 0
    private var positionHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0
    @Volatile private var selected: Pair<Int, Int>? = null
    @Volatile private var legalTargets: Set<Pair<Int, Int>> = emptySet()
    @Volatile private var animatedMove: ChessMove? = null
    @Volatile private var animationStart = 0L
    private val animationDuration = 260L

    fun animateMove(move: ChessMove) { animatedMove = move; animationStart = android.os.SystemClock.uptimeMillis() }
    fun isAnimating(): Boolean = animatedMove != null

    private val vertexShader = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        void main(){ gl_Position = uMvp * vec4(aPosition,1.0); }
    """
    private val fragmentShader = """
        precision mediump float;
        uniform vec4 uColor;
        void main(){ gl_FragColor = uColor; }
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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0,0,width,height)
        val half = 4.48f
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val halfHeight = if (aspect < 1f) half / aspect else half
        val halfWidth = if (aspect < 1f) half else half * aspect
        Matrix.orthoM(projection,0,-halfWidth,halfWidth,-halfHeight,halfHeight,-30f,30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(view,0,0f,14f,0f,0f,0f,0f,0f,0f,-1f)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        drawCube(0f,-0.22f,0f,8.55f,0.38f,8.55f,0.035f,0.035f,0.04f)
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
            if (moving != null && moving.toFile == file && moving.toRank == rank) {
                val progress = ((android.os.SystemClock.uptimeMillis()-animationStart).toFloat()/animationDuration).coerceIn(0f,1f)
                val eased = progress*progress*(3f-2f*progress)
                x = (moving.fromFile-3.5f) + ((moving.toFile-moving.fromFile)*eased)
                z = (moving.fromRank-3.5f) + ((moving.toRank-moving.fromRank)*eased)
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
        val segments = 24
        for(i in 0 until segments) {
            val a = i * 2.0 * Math.PI / segments
            val c = (i+1) * 2.0 * Math.PI / segments
            val ax = kotlin.math.cos(a).toFloat()*radius
            val az = kotlin.math.sin(a).toFloat()*radius
            val bx = kotlin.math.cos(c).toFloat()*radius
            val bz = kotlin.math.sin(c).toFloat()*radius
            val bottom = -height/2f
            val top = height/2f
            floatArrayOf(ax,bottom,az,bx,bottom,bz,bx,top,bz,ax,bottom,az,bx,top,bz,ax,top,az,0f,bottom,0f,bx,bottom,bz,ax,bottom,az,0f,top,0f,ax,top,az,bx,top,bz).forEach { vertices.add(it) }
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
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y,z)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glUniform4f(colorHandle,r,g,b,1f)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,buffer)
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
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,indices.size,GLES20.GL_UNSIGNED_SHORT,indexBuffer)
    }

    private fun compile(type:Int, source:String):Int {
        val shader=GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
        return shader
    }
}
