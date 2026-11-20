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

    init {
        setEGLContextClientVersion(2)
        setRenderer(boardRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun resetBoard() {
        game.reset()
        selected = null
        boardRenderer.setSelection(null)
        requestRender()
    }

    fun applySyncedMove(move: ChessMove): Boolean {
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null); requestRender() }
        return applied
    }

    fun applyComputerMove(move: ChessMove): Boolean {\n        if (game.sideToMove == localSide) return false\n        val applied = game.makeMove(move)\n        if (applied) { selected = null; boardRenderer.setSelection(null); requestRender() }\n        return applied\n    }\n\n    fun applyRemoteMove(move: ChessMove): Boolean {
        if (game.sideToMove == localSide) return false
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null); requestRender() }
        return applied
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        // Board occupies the central area. A near-overhead camera keeps square mapping intuitive.
        val nx = ((event.x / width.coerceAtLeast(1) - 0.23f) / 0.54f).coerceIn(0f, 0.999f)
        val ny = ((event.y / height.coerceAtLeast(1) - 0.05f) / 0.90f).coerceIn(0f, 0.999f)
        val file = (nx * 8).toInt()
        val rank = (ny * 8).toInt()
        if (game.sideToMove != localSide) return true

        val current = selected
        if (current == null) {
            if (game.pieceAt(file, rank)?.side == localSide) selected = file to rank
        } else {
            val candidate = game.legalMovesFrom(current.first, current.second)
                .firstOrNull { it.toFile == file && it.toRank == rank }
            if (candidate != null && game.makeMove(candidate)) {
                selected = null
                onMove?.invoke(candidate)
            } else if (game.pieceAt(file, rank)?.side == localSide) {
                selected = file to rank
            } else {
                selected = null
            }
        }
        boardRenderer.setSelection(selected)
        requestRender()
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

    fun setSelection(value: Pair<Int, Int>?) { selected = value }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f,0.07f,0.09f,1f)
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
        Matrix.perspectiveM(projection,0,38f,width.toFloat()/height.coerceAtLeast(1),0.1f,100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setLookAtM(view,0,0f,12f,2.0f,0f,0f,0f,0f,1f,0f)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        drawCube(0f,-0.22f,0f,8.35f,0.35f,8.35f,0.12f,0.14f,0.17f)
        for (rank in 0..7) for (file in 0..7) {
            val light = (file+rank)%2==0
            val isSelected = selected == (file to rank)
            drawCube(file-3.5f,0f,rank-3.5f,0.98f,0.12f,0.98f,
                if (isSelected) 0.25f else if(light) 0.78f else 0.19f,
                if (isSelected) 0.78f else if(light) 0.69f else 0.25f,
                if (isSelected) 0.30f else if(light) 0.52f else 0.31f)
            val piece = game.pieceAt(file, rank) ?: continue
            val white = piece.side == Side.WHITE
            val r = if (white) 0.90f else 0.08f
            val g = if (white) 0.88f else 0.11f
            val b = if (white) 0.80f else 0.15f
            val x = file-3.5f
            val z = rank-3.5f
            // Layered geometric silhouettes distinguish piece classes while remaining lightweight.
            drawCube(x,0.18f,z,0.62f,0.16f,0.62f,r*0.82f,g*0.82f,b*0.82f)
            val height = when(piece.type) {
                PieceType.PAWN -> 0.48f
                PieceType.KNIGHT -> 0.68f
                PieceType.BISHOP -> 0.76f
                PieceType.ROOK -> 0.62f
                PieceType.QUEEN -> 0.92f
                PieceType.KING -> 1.02f
            }
            val width = when(piece.type) {
                PieceType.PAWN -> 0.32f
                PieceType.KNIGHT -> 0.40f
                PieceType.BISHOP -> 0.36f
                PieceType.ROOK -> 0.48f
                PieceType.QUEEN, PieceType.KING -> 0.42f
            }
            drawCube(x,0.25f+height/2f,z,width,height,width,r,g,b)
            if (piece.type == PieceType.KING || piece.type == PieceType.QUEEN) {
                drawCube(x,0.25f+height+0.07f,z,0.20f,0.14f,0.20f,0.95f,0.72f,0.22f)
            }
        }
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
