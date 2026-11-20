package com.alfeela.chess3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLSurfaceView.Renderer

class ChessBoardView(context: Context) : GLSurfaceView(context) {
    private val boardRenderer = BoardRenderer()
    init {
        setEGLContextClientVersion(2)
        setRenderer(boardRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
    fun resetBoard() { boardRenderer.reset() }
}

private class BoardRenderer : Renderer {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var program = 0
    private var angle = 0f
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
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(cube.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(cube); position(0) }
    private val indexBuffer = ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
    private var positionHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f,0.07f,0.09f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it,vs); GLES20.glAttachShader(it,fs); GLES20.glLinkProgram(it) }
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
        angle += 0.08f
        Matrix.setLookAtM(view,0,0f,9f,11f,0f,0f,0f,0f,1f,0f)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        drawCube(0f,-0.22f,0f,8.35f,0.35f,8.35f,0.18f,0.16f,0.18f)
        for (z in 0..7) for (x in 0..7) {
            val light = (x+z)%2==0
            drawCube(x-3.5f,0f,z-3.5f,0.98f,0.12f,0.98f,
                if(light) 0.78f else 0.19f, if(light) 0.69f else 0.25f, if(light) 0.52f else 0.31f)
        }
        // Stylized 3D pieces show the initial arrangement.
        for (x in 0..7) {
            drawCube(x-3.5f,0.22f,-2.5f,0.48f,0.42f,0.48f,0.86f,0.84f,0.76f)
            drawCube(x-3.5f,0.22f,2.5f,0.48f,0.42f,0.48f,0.12f,0.15f,0.19f)
        }
        for (x in 0..7) {
            drawCube(x-3.5f,0.48f,-3.5f,0.62f,0.82f,0.62f,0.9f,0.88f,0.8f)
            drawCube(x-3.5f,0.48f,3.5f,0.62f,0.82f,0.62f,0.08f,0.1f,0.13f)
        }
    }
    fun reset() { angle = 0f }
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
