package com.alfeela.chess3d

import android.content.Context
import android.content.res.AssetManager
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

class ChessBoardView(context: Context) : GLSurfaceView(context) {
    val game = ChessGame()
    private val boardRenderer = BoardRenderer(game, context.assets)
    var localSide: Side = Side.WHITE
    var onMove: ((ChessMove) -> Unit)? = null
    var onSound: ((String) -> Unit)? = null
    var onCapturedPiecesChanged: ((List<ChessPiece>, List<ChessPiece>) -> Unit)? = null
    var onPromotionRequested: ((Side, (PieceType) -> Unit) -> Unit)? = null
    private var selected: Pair<Int, Int>? = null
    private var downSquare: Pair<Int, Int>? = null
    private var downX = 0f
    private var downY = 0f
    private var rotateGesture = false
    private var lastRotateX = 0f
    private var lastRotateY = 0f
    private var lastPointerSpan = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var oneFingerCameraDrag = false
    private var cameraGestureConsumed = false
    @Volatile var cameraTouchSensitivity: Float = 1.0f

    private fun pointerCenterX(event: MotionEvent, excludedIndex: Int = -1): Float {
        var total = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludedIndex) continue
            total += event.getX(i)
            count++
        }
        return if (count == 0) 0f else total / count
    }

    private fun pointerCenterY(event: MotionEvent, excludedIndex: Int = -1): Float {
        var total = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludedIndex) continue
            total += event.getY(i)
            count++
        }
        return if (count == 0) 0f else total / count
    }

    private fun pointerSpan(event: MotionEvent, excludedIndex: Int = -1): Float {
        val indexes = (0 until event.pointerCount).filter { it != excludedIndex }
        if (indexes.size < 2) return 0f
        val a = indexes[0]
        val b = indexes[1]
        val dx = event.getX(a) - event.getX(b)
        val dy = event.getY(a) - event.getY(b)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun rotateCamera(delta: Float) { boardRenderer.rotateBy(delta); requestRender() }
    fun zoomCamera(delta: Float) { boardRenderer.zoomBy(delta); requestRender() }
    fun tiltCamera(delta: Float) { boardRenderer.tiltBy(delta); requestRender() }
    fun resetCamera() { boardRenderer.resetCamera(); requestRender() }
    fun set2DMode(enabled: Boolean) { boardRenderer.set2DMode(enabled); requestRender() }
    fun is2DMode(): Boolean = boardRenderer.is2DMode()

    private fun squareAt(x: Float, y: Float): Pair<Int, Int>? =
        boardRenderer.squareAt(x, y, width, height)

    private fun animate(move: ChessMove) {
        // Continuous rendering already supplies animation frames; avoid
        // scheduling redundant callbacks on the UI thread.
        boardRenderer.animateMove(move)
        requestRender()
    }

    private fun notifyCapturedPieces() {
        onCapturedPiecesChanged?.invoke(game.capturedPieces(Side.WHITE), game.capturedPieces(Side.BLACK))
    }

    private fun completeMove(move: ChessMove) {
        val captured = game.pieceAt(move.toFile, move.toRank) != null
        if (game.makeMove(move)) {
            notifyMoveSound(captured)
            notifyCapturedPieces()
            selected = null
            boardRenderer.setSelection(null, emptySet())
            animate(move)
            onMove?.invoke(move)
        }
    }

    private fun notifyMoveSound(captured: Boolean) {
        val event = when {
            game.isCheckmate() -> "mate"
            game.isInCheck(game.sideToMove) -> "check"
            captured -> "capture"
            else -> "move"
        }
        onSound?.invoke(event)
    }

    private fun refreshSelection() {
        val moves = selected?.let { game.legalMovesFrom(it.first, it.second) }
            ?.map { it.toFile to it.toRank }?.toSet() ?: emptySet()
        boardRenderer.setSelection(selected, moves)
    }

    init {
        // Request a full 8-bit-per-channel framebuffer with a depth buffer.
        // The actual drawable resolution remains the device's native surface size.
        setEGLContextClientVersion(2)
        // Prefer a 4x multisampled EGL surface for smoother piece and board edges.
        // Fall back gracefully on GPUs that do not expose a multisampled config.
        setEGLConfigChooser(object : EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                fun choose(samples: Int): EGLConfig? {
                    val attrs = if (samples > 0) intArrayOf(
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 8,
                        EGL10.EGL_RENDERABLE_TYPE, 4,
                        EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples,
                        EGL10.EGL_NONE
                    ) else intArrayOf(
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 8,
                        EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE
                    )
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val count = IntArray(1)
                    return if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0)
                        configs[0] else null
                }
                return choose(4) ?: choose(0)
                    ?: throw IllegalArgumentException("No compatible OpenGL ES 2.0 EGL config")
            }
        })
        setPreserveEGLContextOnPause(true)
        setRenderer(boardRenderer)
        // Keep the scene rendering continuously so camera motion and piece
        // animation can use the display's refresh cadence instead of sparse redraws.
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun resetBoard() {
        game.reset()
        selected = null
        notifyCapturedPieces()
        boardRenderer.setSelection(null, emptySet())
        requestRender()
    }

    fun applySyncedMove(move: ChessMove): Boolean {
        val captured = game.pieceAt(move.toFile, move.toRank) != null
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); animate(move); notifyMoveSound(captured); notifyCapturedPieces() }
        return applied
    }

    fun applyComputerMove(move: ChessMove): Boolean {
        if (game.sideToMove == localSide) return false
        val captured = game.pieceAt(move.toFile, move.toRank) != null
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); animate(move); notifyMoveSound(captured); notifyCapturedPieces() }
        return applied
    }

    fun applyRemoteMove(move: ChessMove): Boolean {
        if (game.sideToMove == localSide) return false
        val captured = game.pieceAt(move.toFile, move.toRank) != null
        val applied = game.makeMove(move)
        if (applied) { selected = null; boardRenderer.setSelection(null, emptySet()); animate(move); notifyMoveSound(captured); notifyCapturedPieces() }
        return applied
    }

    fun setAppearance(whitePiece: Int, blackPiece: Int, lightSquare: Int, darkSquare: Int) {
        queueEvent {
            boardRenderer.setAppearance(whitePiece, blackPiece, lightSquare, darkSquare)
        }
        requestRender()
    }
    fun setSceneBackground(color: Int) {
        queueEvent { boardRenderer.setSceneBackground(color) }
        requestRender()
    }
    fun setPieceStyle(style: Int) {
        queueEvent { boardRenderer.setPieceStyle(style.coerceIn(0, 2)) }
        requestRender()
    }
    fun setPieceSet(alternate: Boolean) {
        queueEvent { boardRenderer.setPieceSet(alternate) }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downSquare = squareAt(event.x, event.y)
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                rotateGesture = false
                oneFingerCameraDrag = false
                cameraGestureConsumed = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                rotateGesture = true
                cameraGestureConsumed = true
                oneFingerCameraDrag = false
                lastRotateX = pointerCenterX(event)
                lastRotateY = pointerCenterY(event)
                lastPointerSpan = pointerSpan(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotateGesture && event.pointerCount >= 2) {
                    val currentX = pointerCenterX(event)
                    val currentY = pointerCenterY(event)
                    val currentSpan = pointerSpan(event)
                    // Normalize gesture deltas to the view size for consistent control on phones and tablets.
                    val dx = (currentX - lastRotateX).coerceIn(-width.toFloat(), width.toFloat())
                    val dy = (currentY - lastRotateY).coerceIn(-height.toFloat(), height.toFloat())
                    val minDimension = minOf(width, height).coerceAtLeast(1).toFloat()
                    if (!boardRenderer.is2DMode()) {
                        boardRenderer.rotateBy(dx * (1.65f / width.coerceAtLeast(1)) * cameraTouchSensitivity)
                        if (kotlin.math.abs(dy) > 0.5f) boardRenderer.tiltBy(dy * (27f / height.coerceAtLeast(1)) * cameraTouchSensitivity)
                    }
                    if (lastPointerSpan > 0f && currentSpan > 0f) {
                        boardRenderer.zoomBy((lastPointerSpan-currentSpan) * 0.045f * cameraTouchSensitivity)
                    }
                    lastRotateX = currentX
                    lastRotateY = currentY
                    lastPointerSpan = currentSpan
                    requestRender()
                } else if (event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val moved = kotlin.math.abs(event.x-downX) > 18f || kotlin.math.abs(event.y-downY) > 18f
                    // A drag anywhere on the board is a camera gesture. Piece movement
                    // remains reliable through tap-to-select, then tap the destination.
                    if (moved) {
                        oneFingerCameraDrag = true
                        cameraGestureConsumed = true
                        downSquare = null
                        // Avoid clipping normal swipe events; clipping caused fast drags to lose movement.
                        val dragDx = dx.coerceIn(-width.toFloat(), width.toFloat())
                        val dragDy = dy.coerceIn(-height.toFloat(), height.toFloat())
                        if (!boardRenderer.is2DMode()) {
                            boardRenderer.rotateBy(dragDx * (1.65f / width.coerceAtLeast(1)) * cameraTouchSensitivity)
                            if (kotlin.math.abs(dragDy) > 0.5f) boardRenderer.tiltBy(dragDy * (27f / height.coerceAtLeast(1)) * cameraTouchSensitivity)
                        }
                        requestRender()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Rebase the gesture after one finger leaves so the remaining
                // finger never causes a sudden camera jump.
                val lifted = event.actionIndex
                val remaining = event.pointerCount - 1
                downSquare = null
                cameraGestureConsumed = true
                if (remaining >= 2) {
                    lastRotateX = pointerCenterX(event, lifted)
                    lastRotateY = pointerCenterY(event, lifted)
                    lastPointerSpan = pointerSpan(event, lifted)
                    rotateGesture = true
                } else {
                    rotateGesture = false
                    oneFingerCameraDrag = true
                    if (remaining == 1) {
                        val index = if (lifted == 0) 1 else 0
                        lastTouchX = event.getX(index)
                        lastTouchY = event.getY(index)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (cameraGestureConsumed || rotateGesture || oneFingerCameraDrag) {
                    rotateGesture = false
                    oneFingerCameraDrag = false
                    cameraGestureConsumed = false
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
                            // Always have a valid queen-promotion fallback if the
                            // chooser is unavailable or returns an unexpected value.
                            val queenMove = candidates.firstOrNull {
                                it.promotion == PieceType.QUEEN
                            } ?: candidate
                            val chooser = onPromotionRequested
                            if (chooser != null) {
                                chooser(localSide) { choice ->
                                    val selectedMove = candidates.firstOrNull {
                                        it.promotion == choice
                                    } ?: queenMove
                                    completeMove(selectedMove)
                                }
                            } else {
                                completeMove(queenMove)
                            }
                        } else {
                            completeMove(candidate)
                        }
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
                oneFingerCameraDrag = false
                cameraGestureConsumed = false
                return true
            }
        }
        return true
    }
}

private class BoardRenderer(private val game: ChessGame, private val assets: AssetManager) : GLSurfaceView.Renderer {
    private data class GlbBoardMesh(val vertices: FloatBuffer, val normals: FloatBuffer, val indices: java.nio.ShortBuffer, val count: Int, val color: FloatArray)
    private data class GlbPiecePart(val vertices: FloatBuffer, val normals: FloatBuffer, val indices: java.nio.ShortBuffer, val count: Int, val gold: Boolean)
    private val glbPieceTemplates = HashMap<Pair<Side, PieceType>, List<GlbPiecePart>>()
    @Volatile private var glbPiecesReady = false
    private val glbBoardMeshes = ArrayList<GlbBoardMesh>()
    @Volatile private var glbBoardReady = false
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
    private var glowHandle = 0
    @Volatile private var selected: Pair<Int, Int>? = null
    @Volatile private var legalTargets: Set<Pair<Int, Int>> = emptySet()
    @Volatile private var animatedMove: ChessMove? = null
    @Volatile private var animationStart = 0L
    // Current camera values are eased toward touch-controlled targets on the GL thread.
    @Volatile private var boardRotation = 0f
    @Volatile private var cameraDistance = 17.0f
    @Volatile private var cameraElevation = 38f
    @Volatile private var targetBoardRotation = 0f
    @Volatile private var targetCameraDistance = 17.0f
    @Volatile private var targetCameraElevation = 38f
    @Volatile private var is2D = false
    private var lastCameraFrameNanos = 0L
    @Volatile private var whitePieceColor = 0xF0E6D2.toInt()
    @Volatile private var blackPieceColor = 0x09090B
    @Volatile private var lightSquareColor = 0xFFFFFF.toInt()
    @Volatile private var darkSquareColor = 0x858B95.toInt()
    @Volatile private var sceneBackgroundColor = android.graphics.Color.rgb(9, 10, 13)
    @Volatile private var pieceStyle = 0
    @Volatile private var pieceSetAssetName = "staunton-chess.glb"
    fun setSceneBackground(color: Int) { sceneBackgroundColor = color }
    fun setAppearance(whitePiece: Int, blackPiece: Int, lightSquare: Int, darkSquare: Int) {
        whitePieceColor = whitePiece
        blackPieceColor = blackPiece
        lightSquareColor = lightSquare
        darkSquareColor = darkSquare
    }
    fun setPieceStyle(style: Int) { pieceStyle = style.coerceIn(0, 2) }
    fun setPieceSet(alternate: Boolean) {
        pieceSetAssetName = if (alternate) "alternate-chess-set.glb" else "staunton-chess.glb"
        loadGlbPieces()
    }
    fun is2DMode(): Boolean = is2D
    fun set2DMode(enabled: Boolean) {
        is2D = enabled
        if (enabled) {
            targetBoardRotation = 0f
            targetCameraElevation = 89.5f
        } else {
            targetBoardRotation = 0f
            targetCameraElevation = 38f
            targetCameraDistance = 17f
        }
    }
    private fun red(color: Int) = android.graphics.Color.red(color) / 255f
    private fun green(color: Int) = android.graphics.Color.green(color) / 255f
    private fun blue(color: Int) = android.graphics.Color.blue(color) / 255f

    private val animationDuration = 300L
    private var activePieceLift = 0f
    private var activePieceGlow = 0f
    private var activePieceYaw = 0f
    private var activePieceScaleX = 1f
    private var activePieceScaleY = 1f
    private var activePieceScaleZ = 1f

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
        val distance = cameraDistance
        if (is2D) {
            val halfHeight = 5.15f * (distance / 17f).coerceIn(0.55f, 1.75f)
            Matrix.orthoM(p, 0, -halfHeight * aspect, halfHeight * aspect, -halfHeight, halfHeight, 0.1f, 60f)
            Matrix.setLookAtM(v, 0, 0f, distance, 0f, 0f, 0f, 0f, 0f, 0f, -1f)
        } else {
            Matrix.perspectiveM(p, 0, 36f, aspect, 0.1f, 60f)
            val elevation = Math.toRadians(cameraElevation.toDouble())
            Matrix.setLookAtM(v, 0, 0f,
                distance * kotlin.math.sin(elevation).toFloat(),
                distance * kotlin.math.cos(elevation).toFloat(),
                0f, 0.25f, 0f, 0f, 1f, 0f)
            Matrix.rotateM(v, 0, Math.toDegrees(boardRotation.toDouble()).toFloat(), 0f, 1f, 0f)
        }
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
    fun rotateBy(delta: Float) { if (!is2D) targetBoardRotation += delta }
    fun tiltBy(delta: Float) {
        if (!is2D) targetCameraElevation = (targetCameraElevation + delta).coerceIn(24f, 62f)
    }
    fun zoomBy(delta: Float) {
        targetCameraDistance = (targetCameraDistance + delta).coerceIn(8f, 30f)
    }
    fun resetCamera() {
        targetBoardRotation = 0f
        targetCameraDistance = 17.0f
        targetCameraElevation = 38f
    }

    // Frame-rate-independent damped interpolation removes touch-event jitter
    // while preserving responsive camera control on both 60 Hz and faster displays.
    private fun smoothCameraMotion() {
        val now = System.nanoTime()
        val elapsed = if (lastCameraFrameNanos == 0L) 16_000_000L
            else (now - lastCameraFrameNanos).coerceIn(1_000_000L, 50_000_000L)
        lastCameraFrameNanos = now
        // A shorter response constant reduces the feeling of the camera dragging
        // behind the finger while retaining frame-rate-independent easing.
        val alpha = (1.0 - kotlin.math.exp(-elapsed.toDouble() / 14_000_000.0)).toFloat()
        boardRotation += (targetBoardRotation - boardRotation) * alpha
        cameraDistance += (targetCameraDistance - cameraDistance) * alpha
        cameraElevation += (targetCameraElevation - cameraElevation) * alpha
        if (kotlin.math.abs(targetBoardRotation - boardRotation) < 0.00005f) boardRotation = targetBoardRotation
        if (kotlin.math.abs(targetCameraDistance - cameraDistance) < 0.001f) cameraDistance = targetCameraDistance
        if (kotlin.math.abs(targetCameraElevation - cameraElevation) < 0.005f) cameraElevation = targetCameraElevation
    }

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
        uniform float uGlow;
        varying vec3 vNormal;
        varying vec3 vPosition;
        void main(){
            vec3 n = normalize(vNormal);
            vec3 lightDir = normalize(vec3(-0.45, 0.85, 0.55));
            vec3 fillDir = normalize(vec3(0.65, 0.42, -0.55));
            float diffuse = max(dot(n, lightDir), 0.0);
            float fill = max(dot(n, fillDir), 0.0);
            vec3 viewDir = normalize(vec3(0.0, 1.2, 2.0) - vPosition);
            vec3 halfDir = normalize(lightDir + viewDir);
            vec3 fillHalf = normalize(fillDir + viewDir);
            float specular = pow(max(dot(n, halfDir), 0.0), 64.0);
            float fillSpecular = pow(max(dot(n, fillHalf), 0.0), 42.0);
            float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0) * 0.20;
            vec3 lit = uColor.rgb * (0.34 + diffuse * 0.72 + fill * 0.24 + rim)
                + vec3(1.0,0.96,0.88) * (specular * 0.62 + fillSpecular * 0.18)
                + vec3(1.0,0.58,0.12) * uGlow;
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
    // Unit corner normals keep cube lighting consistent instead of incorrectly
    // reusing raw position coordinates as normals.
    private val cubeNormalBuffer: FloatBuffer = ByteBuffer.allocateDirect(cube.size*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            for (i in cube.indices step 3) {
                val nx = cube[i]; val ny = cube[i+1]; val nz = cube[i+2]
                val length = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(0.0001f)
                put(nx/length); put(ny/length); put(nz/length)
            }
            position(0)
        }

    fun setSelection(value: Pair<Int, Int>?, targets: Set<Pair<Int, Int>>) {
        selected = value
        legalTargets = targets
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(red(sceneBackgroundColor), green(sceneBackgroundColor), blue(sceneBackgroundColor), 1f)
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
        glowHandle = GLES20.glGetUniformLocation(program,"uGlow")
        // Keep both sides visible while generated procedural meshes use per-face normals.
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        loadGlbBoard()
        loadGlbPieces()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0,0,width,height)
        viewportWidth = width
        viewportHeight = height
        updateProjection()
    }

    private fun updateProjection() {
        val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
        if (is2D) {
            val halfHeight = 5.15f * (cameraDistance / 17f).coerceIn(0.55f, 1.75f)
            Matrix.orthoM(projection, 0, -halfHeight * aspect, halfHeight * aspect, -halfHeight, halfHeight, 0.1f, 60f)
        } else Matrix.perspectiveM(projection, 0, 36f, aspect, 0.1f, 60f)
    }

    override fun onDrawFrame(gl: GL10?) {
        smoothCameraMotion()
        updateProjection()
        GLES20.glClearColor(red(sceneBackgroundColor), green(sceneBackgroundColor), blue(sceneBackgroundColor), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val distance = cameraDistance
        if (is2D) {
            Matrix.setLookAtM(view, 0, 0f, distance, 0f, 0f, 0f, 0f, 0f, 0f, -1f)
        } else {
            val elevation = Math.toRadians(cameraElevation.toDouble())
            Matrix.setLookAtM(view,0,0f,
                distance*kotlin.math.sin(elevation).toFloat(),
                distance*kotlin.math.cos(elevation).toFloat(),
                0f,0.25f,0f,0f,1f,0f)
            Matrix.rotateM(view,0,Math.toDegrees(boardRotation.toDouble()).toFloat(),0f,1f,0f)
        }
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        // Thick polished black plinth gives the board a real raised edge.
        drawCube(0f,-0.24f,0f,8.62f,0.42f,8.62f,0.025f,0.028f,0.032f)
        drawCube(0f,-0.015f,0f,8.48f,0.08f,8.48f,0.11f,0.105f,0.10f)
        // The approved GLB supplies the actual 64 square meshes when its asset
        // is present. Procedural squares remain the safe fallback on load errors.
        if (glbBoardReady) drawGlbBoard()
        val previousMove = game.lastMove
        val checkedKing = if (game.isInCheck(game.sideToMove)) {
            (0..7).asSequence().flatMap { r -> (0..7).asSequence().map { f -> f to r } }
                .firstOrNull { (f, r) -> game.pieceAt(f, r) == ChessPiece(game.sideToMove, PieceType.KING) }
        } else null
        for (rank in 0..7) for (file in 0..7) {
            activePieceLift = 0f
            activePieceYaw = 0f
            activePieceGlow = 0f
            val light = (file+rank)%2==0
            val square = file to rank
            val isSelected = selected == square
            val isTarget = legalTargets.contains(square)
            val isLastMove = previousMove?.let {                (it.fromFile == file && it.fromRank == rank) || (it.toFile == file && it.toRank == rank)
            } ?: false
            val isCheckedKing = checkedKing == square
            if (!glbBoardReady || isCheckedKing || isSelected || isTarget || isLastMove) {
                drawCube(file-3.5f,if (glbBoardReady) 0.075f else 0f,rank-3.5f,0.98f,if (glbBoardReady) 0.025f else 0.12f,0.98f,
                    if (isCheckedKing) 0.82f else if (isSelected) 0.72f else if (isTarget) 0.58f else if (isLastMove) 0.58f else red(if (light) lightSquareColor else darkSquareColor),
                    if (isCheckedKing) 0.12f else if (isSelected) 0.48f else if (isTarget) 0.48f else if (isLastMove) 0.38f else green(if (light) lightSquareColor else darkSquareColor),
                    if (isCheckedKing) 0.10f else if (isSelected) 0.16f else if (isTarget) 0.14f else if (isLastMove) 0.12f else blue(if (light) lightSquareColor else darkSquareColor))
            }
            if (isTarget && game.pieceAt(file, rank) == null) {
                drawCube(file-3.5f,0.075f,rank-3.5f,0.22f,0.035f,0.22f,0.95f,0.70f,0.28f)
            }
            val piece = game.pieceAt(file, rank) ?: continue
            when (pieceStyle) {
                1 -> { activePieceScaleX = 0.88f; activePieceScaleY = 1.04f; activePieceScaleZ = 0.88f }
                2 -> { activePieceScaleX = 1.08f; activePieceScaleY = 1.10f; activePieceScaleZ = 1.08f }
                else -> { activePieceScaleX = 1f; activePieceScaleY = 1f; activePieceScaleZ = 1f }
            }
            val white = piece.side == Side.WHITE
            // Turn each Staunton horse inward toward the center of the board.
            activePieceYaw = if (piece.type == PieceType.KNIGHT) {
                if (piece.side == Side.WHITE) 90f else -90f
            } else 0f
            // Reference finish: glossy piano-black versus warm ivory.
            val pieceColor = if (white) whitePieceColor else blackPieceColor
            val r = red(pieceColor)
            val g = green(pieceColor)
            val b = blue(pieceColor)
            var x = file-3.5f
            var z = rank-3.5f
            activePieceLift = 0f
            val moving = animatedMove
            if (moving != null) {
                val progress = ((android.os.SystemClock.uptimeMillis()-animationStart).toFloat()/animationDuration).coerceIn(0f,1f)
                val eased = progress * progress * progress * (progress * (progress * 6f - 15f) + 10f)
                // Lift the moving piece along a smooth arc to avoid scraping across
                // neighboring pieces while retaining exact square-to-square travel.
                val arc = kotlin.math.sin(Math.PI.toFloat() * eased) * 0.22f
                // Animate the moved piece from its source square to its destination.
                if (moving.toFile == file && moving.toRank == rank) {
                    x = (moving.fromFile-3.5f) + ((moving.toFile-moving.fromFile)*eased)
                    z = (moving.fromRank-3.5f) + ((moving.toRank-moving.fromRank)*eased)
                    activePieceLift = arc
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
                        activePieceLift = arc * 0.85f
                    }
                }
                if (progress >= 1f) animatedMove = null
            }
            // A subtle pulsing amber emissive highlight identifies the selected piece.
            activePieceGlow = if (isSelected) {
                0.45f + 0.22f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() * 0.006).toFloat()
            } else 0f
            // Use the approved imported Staunton geometry when available.
            if (glbPiecesReady && glbPieceTemplates.containsKey(piece.side to piece.type)) {
                drawGlbPiece(piece, file, x, z)
            } else {
                // Soft layered contact shadows visually ground each piece on its square.
                drawContactShadow(x, z)
                // Classic Staunton-inspired silhouettes based on the user's approved
            // glossy black and ivory reference photograph.
            val hi = (r * 1.13f).coerceAtMost(1f)
            val hg = (g * 1.13f).coerceAtMost(1f)
            val hb = (b * 1.13f).coerceAtMost(1f)
            // Shared broad, stepped, polished Staunton foot.
            drawCylinder(x,0.105f,z,0.35f,0.14f,r*0.82f,g*0.82f,b*0.82f)
            drawCylinder(x,0.185f,z,0.315f,0.045f,hi,hg,hb)
            drawCylinder(x,0.245f,z,0.255f,0.10f,r,g,b)
            activePieceYaw = if (piece.type == PieceType.KNIGHT) { if (white) 90f else -90f } else 0f
            when(piece.type) {
                PieceType.PAWN -> {
                    drawCylinder(x,0.49f,z,0.125f,0.43f,r,g,b)
                    drawCylinder(x,0.73f,z,0.17f,0.075f,hi,hg,hb)
                    drawCylinder(x,0.82f,z,0.105f,0.12f,r,g,b)
                    drawSphere(x,1.035f,z,0.155f,r,g,b)
                }
                PieceType.ROOK -> {
                    drawCylinder(x,0.55f,z,0.17f,0.52f,r,g,b)
                    drawCylinder(x,0.83f,z,0.235f,0.10f,hi,hg,hb)
                    drawCylinder(x,1.00f,z,0.245f,0.25f,r,g,b)
                    drawCylinder(x,1.14f,z,0.28f,0.08f,r,g,b)
                    // Four merlons form the unmistakable rook battlements.
                    for (dx in listOf(-0.17f,0.17f)) for (dz in listOf(-0.17f,0.17f))
                        drawCube(x+dx,1.245f,z+dz,0.105f,0.18f,0.105f,r,g,b)
                }
                PieceType.KNIGHT -> {
                    // Traditional Staunton knight: a recognizable raised horse head,
                    // long arched neck, swept mane, muzzle, ears and a visible eye.
                    drawCylinder(x,0.50f,z,0.20f,0.42f,r,g,b)
                    drawSphere(x+0.035f,0.76f,z,0.17f,r,g,b)
                    drawSphere(x-0.035f,1.02f,z,0.145f,r,g,b)
                    drawSphere(x-0.145f,0.99f,z-0.015f,0.105f,r,g,b)
                    // Sculpted tapered ears and a swept-back mane, angled in profile.
                    drawTriangles(floatArrayOf(
                        -0.13f,1.12f,-0.10f, -0.08f,1.12f,-0.10f, -0.105f,1.39f,-0.10f,
                        -0.105f,1.39f,-0.10f, -0.08f,1.12f,-0.10f, -0.015f,1.16f,-0.10f,
                        0.035f,1.12f,-0.10f, 0.12f,1.12f,-0.10f, 0.205f,1.39f,-0.10f,
                        0.205f,1.39f,-0.10f, 0.12f,1.12f,-0.10f, 0.075f,1.16f,-0.10f,
                        0.12f,1.03f,-0.12f, 0.24f,1.19f,-0.12f, 0.20f,0.83f,-0.12f,
                        0.20f,0.83f,-0.12f, 0.24f,1.19f,-0.12f, 0.12f,0.96f,-0.12f
                    ),x,0f,z,r*0.88f,g*0.88f,b*0.88f)
                    // Eye and nostril catch the highlights on the polished finish.
                    drawSphere(x-0.105f,1.075f,z-0.105f,0.027f,hi,hg,hb)
                    drawSphere(x-0.235f,0.985f,z-0.065f,0.018f,hi,hg,hb)
                }
                PieceType.BISHOP -> {
                    drawCylinder(x,0.52f,z,0.17f,0.44f,r,g,b)
                    drawCylinder(x,0.79f,z,0.22f,0.10f,hi,hg,hb)
                    drawSphere(x,1.055f,z,0.205f,r,g,b)
                    drawSphere(x,1.245f,z,0.105f,r,g,b)
                    drawSphere(x,1.365f,z,0.045f,hi,hg,hb)
                    // Traditional bishop diagonal slit.
                    drawCube(x+0.11f,1.12f,z-0.14f,0.035f,0.22f,0.035f,hi,hg,hb)
                }
                PieceType.QUEEN -> {
                    drawCylinder(x,0.51f,z,0.19f,0.42f,r,g,b)
                    drawCylinder(x,0.77f,z,0.23f,0.11f,hi,hg,hb)
                    drawCylinder(x,0.96f,z,0.245f,0.25f,r,g,b)
                    drawCylinder(x,1.105f,z,0.29f,0.10f,r,g,b)
                    // Open crown rim and five rounded points.
                    for(k in 0..4) {
                        val a=k*1.256637f-1.570796f
                        drawSphere(x+kotlin.math.cos(a)*0.225f,1.245f,z+kotlin.math.sin(a)*0.225f,0.055f,r,g,b)
                    }
                    drawSphere(x,1.22f,z,0.075f,hi,hg,hb)
                }
                PieceType.KING -> {
                    drawCylinder(x,0.52f,z,0.20f,0.44f,r,g,b)
                    drawCylinder(x,0.79f,z,0.24f,0.11f,hi,hg,hb)
                    drawCylinder(x,0.98f,z,0.25f,0.25f,r,g,b)
                    drawCylinder(x,1.12f,z,0.29f,0.10f,r,g,b)
                    // Classic cross finial.
                    drawCylinder(x,1.30f,z,0.055f,0.32f,r,g,b)
                    drawCube(x,1.34f,z,0.30f,0.075f,0.075f,r,g,b)
                }
            }
            activePieceYaw = 0f
            activePieceScaleX = 1f
            activePieceScaleY = 1f
            activePieceScaleZ = 1f
            }
        }
    }

    /**
     * Load the approved GLB board-square geometry into GLES20 buffers.
     * glTF/Blendstra uses Z-up; this renderer uses Y-up, so coordinates are
     * converted from (X,Y,Z) to (X,Z,Y) after the node transform.
     */
    private fun loadGlbBoard() {
        glbBoardMeshes.clear()
        glbBoardReady = false
        try {
            val bytes = assets.open("staunton-chess.glb").use { it.readBytes() }
            val scene = GlbAsset.parse(bytes)
            for (node in scene.nodes) {
                if (!node.name.startsWith("Board square", ignoreCase = true)) continue
                val mesh = scene.meshes.getOrNull(node.mesh) ?: continue
                for (primitive in mesh.primitives) {
                    if (primitive.positions.size < 9 || primitive.indices.size < 3 || primitive.indices.any { it !in 0 until primitive.positions.size / 3 }) continue
                    val transformed = FloatArray(primitive.positions.size)
                    val source = primitive.positions
                    for (i in source.indices step 3) {
                        val x = source[i]; val y = source[i + 1]; val z = source[i + 2]
                        val p = transformGlbPoint(node, x, y, z)
                        transformed[i] = p[0]
                        transformed[i + 1] = p[1] // glTF 2.0 is Y-up
                        transformed[i + 2] = -p[2] // mirror the source ranks to match ChessGame coordinates
                    }
                    val normals = FloatArray(transformed.size)
                    if (primitive.normals != null && primitive.normals.size == normals.size) {
                        for (i in primitive.normals.indices step 3) {
                            val nx = primitive.normals[i]; val ny = primitive.normals[i + 1]; val nz = primitive.normals[i + 2]
                            normals[i] = nx; normals[i + 1] = ny; normals[i + 2] = -nz
                        }
                    } else {
                        for (i in primitive.indices.indices step 3) {
                            if (i + 2 >= primitive.indices.size) break
                            val a = primitive.indices[i] * 3; val b = primitive.indices[i + 1] * 3; val c = primitive.indices[i + 2] * 3
                            val ux = transformed[b]-transformed[a]; val uy = transformed[b+1]-transformed[a+1]; val uz = transformed[b+2]-transformed[a+2]
                            val vx = transformed[c]-transformed[a]; val vy = transformed[c+1]-transformed[a+1]; val vz = transformed[c+2]-transformed[a+2]
                            var nx = uy*vz-uz*vy; var ny = uz*vx-ux*vz; var nz = ux*vy-uy*vx
                            val len = kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(0.00001f)
                            nx/=len; ny/=len; nz/=len
                            for (v in intArrayOf(a,b,c)) { normals[v]=nx; normals[v+1]=ny; normals[v+2]=nz }
                        }
                    }
                    if (primitive.indices.any { it > 65535 }) continue
                    val vb = ByteBuffer.allocateDirect(transformed.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(transformed); position(0) }
                    val nb = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(normals); position(0) }
                    val shorts = ShortArray(primitive.indices.size) { primitive.indices[it].toShort() }
                    val ib = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(shorts); position(0) }
                    var cx = 0f; var cz = 0f
                    for (i in transformed.indices step 3) { cx += transformed[i]; cz += transformed[i+2] }
                    val vertexCount = transformed.size / 3
                    cx /= vertexCount; cz /= vertexCount
                    val file = kotlin.math.floor(cx + 4f).toInt().coerceIn(0, 7)
                    val rank = kotlin.math.floor(cz + 4f).toInt().coerceIn(0, 7)
                    val isLight = (file + rank) % 2 == 0
                    val color = if (isLight) floatArrayOf(0.78f,0.70f,0.55f,1f) else floatArrayOf(0.075f,0.065f,0.055f,1f)
                    glbBoardMeshes.add(GlbBoardMesh(vb, nb, ib, shorts.size, color))
                }
            }
            glbBoardReady = glbBoardMeshes.size >= 56
            android.util.Log.i("3DChess", "GLB board loaded: ${glbBoardMeshes.size} square primitives")
        } catch (error: Exception) {
            glbBoardMeshes.clear()
            glbBoardReady = false
            android.util.Log.e("3DChess", "GLB board load failed; using procedural board", error)
        }
    }

    /**
     * Build one reusable model for each side/piece type from the 32 modeled
     * starting pieces in the approved GLB. Geometry is stored relative to its
     * source square so the same model can follow moves, captures and promotion.
     * glTF coordinates are Y-up; the board's rank axis is mirrored to match the
     * ChessGame renderer's rank 0 at z=-3.5.
     */
    private fun loadGlbPieces() {
        glbPieceTemplates.clear()
        glbPiecesReady = false
        try {
            val scene = GlbAsset.parse(assets.open("staunton-chess.glb").use { it.readBytes() })
            val assetName = pieceSetAssetName
            val scene = GlbAsset.parse(assets.open(assetName).use { it.readBytes() })
            if (assetName == "alternate-chess-set.glb") {
                loadNamedAlternateGlbPieces(scene)
                return
            }
            val squares = HashMap<Pair<Int, Int>, MutableList<GlbPiecePart>>()
            val backRank = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP,
                PieceType.QUEEN, PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK)

            for ((nodeIndex, node) in scene.nodes.withIndex()) {
                // The first 7 nodes are the plinth/frame and the next 64 are
                // board squares. Remaining mesh nodes are the 32 semantic pieces.
                if (nodeIndex < 71 || node.name.startsWith("Board square", ignoreCase = true)) continue
                val mesh = scene.meshes.getOrNull(node.mesh) ?: continue
                var sumX = 0.0
                var sumZ = 0.0
                var total = 0
                for (primitive in mesh.primitives) {
                    for (i in primitive.positions.indices step 3) {
                        val p = transformGlbPoint(node, primitive.positions[i], primitive.positions[i + 1], primitive.positions[i + 2])
                        sumX += p[0].toDouble()
                        sumZ += p[2].toDouble()
                        total++
                    }
                }
                if (total == 0) continue
                val centerX = (sumX / total).toFloat()
                val centerZ = (sumZ / total).toFloat()
                val file = kotlin.math.round(centerX + 3.5f).toInt()
                val rank = kotlin.math.round(3.5f - centerZ).toInt()
                if (file !in 0..7 || rank !in 0..7) continue
                val sourceSquareX = file - 3.5f
                val sourceSquareZ = 3.5f - rank
                val parts = squares.getOrPut(file to rank) { ArrayList() }

                for (primitive in mesh.primitives) {
                    val source = primitive.positions
                    if (source.size < 9 || primitive.indices.size < 3 ||
                        source.size / 3 > 65535 ||
                        primitive.indices.any { it !in 0 until source.size / 3 || it > 65535 }) continue
                    val local = FloatArray(source.size)
                    for (i in source.indices step 3) {
                        val p = transformGlbPoint(node, source[i], source[i + 1], source[i + 2])
                        local[i] = p[0] - sourceSquareX
                        local[i + 1] = p[1]
                        local[i + 2] = -(p[2] - sourceSquareZ)
                    }
                    val normalSource = primitive.normals
                    val normals = FloatArray(local.size)
                    if (normalSource != null && normalSource.size == normals.size) {
                        for (i in normalSource.indices step 3) {
                            normals[i] = normalSource[i]
                            normals[i + 1] = normalSource[i + 1]
                            normals[i + 2] = -normalSource[i + 2]
                        }
                    } else {
                        for (i in primitive.indices.indices step 3) {
                            if (i + 2 >= primitive.indices.size) break
                            val a = primitive.indices[i] * 3
                            val b = primitive.indices[i + 1] * 3
                            val d = primitive.indices[i + 2] * 3
                            val ux = local[b] - local[a]; val uy = local[b + 1] - local[a + 1]; val uz = local[b + 2] - local[a + 2]
                            val vx = local[d] - local[a]; val vy = local[d + 1] - local[a + 1]; val vz = local[d + 2] - local[a + 2]
                            var nx = uy * vz - uz * vy
                            var ny = uz * vx - ux * vz
                            var nz = ux * vy - uy * vx
                            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.00001f)
                            nx /= length; ny /= length; nz /= length
                            for (v in intArrayOf(a, b, d)) { normals[v] = nx; normals[v + 1] = ny; normals[v + 2] = nz }
                        }
                    }
                    val vertexBuffer = ByteBuffer.allocateDirect(local.size * 4).order(ByteOrder.nativeOrder())
                        .asFloatBuffer().apply { put(local); position(0) }
                    val normalBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder())                        .asFloatBuffer().apply { put(normals); position(0) }
                    val shortIndices = ShortArray(primitive.indices.size) { primitive.indices[it].toShort() }
                    val indexBuffer = ByteBuffer.allocateDirect(shortIndices.size * 2).order(ByteOrder.nativeOrder())                        .asShortBuffer().apply { put(shortIndices); position(0) }
                    val label = node.name.lowercase()
                    val gold = label.contains("brass") || label.contains("bead") ||
                        label.contains("merlon") || label.contains("mitre slit") ||
                        label.contains("crown point") || label.contains("crossbar") ||
                        label.contains("horse eye") || label.contains("horse ear")
                    parts.add(GlbPiecePart(vertexBuffer, normalBuffer, indexBuffer, shortIndices.size, gold))
                }
            }

            for ((square, parts) in squares) {
                val (file, rank) = square
                val side = if (rank <= 3) Side.BLACK else Side.WHITE
                val type = when (rank) {
                    0, 7 -> backRank[file]
                    1, 6 -> PieceType.PAWN
                    else -> null
                } ?: continue
                if (parts.isNotEmpty()) glbPieceTemplates.putIfAbsent(side to type, parts.toList())
            }
            glbPiecesReady = glbPieceTemplates.size == 12
            android.util.Log.i("3DChess", "GLB piece templates loaded: ${glbPieceTemplates.size}/12")
        } catch (error: Exception) {
            glbPieceTemplates.clear()
            glbPiecesReady = false
            android.util.Log.e("3DChess", "GLB piece load failed; using procedural pieces", error)
        }
    }

    private fun loadNamedAlternateGlbPieces(scene: GlbAsset.Scene) {
        glbPieceTemplates.clear()
        glbPiecesReady = false
        val pieceNodes = scene.nodes.filter { it.name.startsWith("piece_", ignoreCase = true) }
        if (pieceNodes.isEmpty()) throw IllegalArgumentException("The alternate GLB has no named piece nodes")
        val xPositions = pieceNodes.map { it.translation.getOrElse(0) { 0f } }.distinct().sorted()
        val zPositions = pieceNodes.map { it.translation.getOrElse(2) { 0f } }.distinct().sorted()
        val xStep = ((xPositions.last() - xPositions.first()) / 7f).coerceAtLeast(0.0001f)
        val zStep = ((zPositions.last() - zPositions.first()) / 7f).coerceAtLeast(0.0001f)
        val maxX = xPositions.last()
        val maxZ = zPositions.last()
        for (node in pieceNodes) {
            val name = node.name.lowercase()
            val side = when {
                name.contains("_white") -> Side.WHITE
                name.contains("_black") -> Side.BLACK
                else -> continue
            }
            val type = when {
                name.contains("pawn") -> PieceType.PAWN
                name.contains("rook") -> PieceType.ROOK
                name.contains("knight") -> PieceType.KNIGHT
                name.contains("bishop") -> PieceType.BISHOP
                name.contains("queen") -> PieceType.QUEEN
                name.contains("king") -> PieceType.KING
                else -> continue
            }
            val mesh = scene.meshes.getOrNull(node.mesh) ?: continue
            val centerX = node.translation.getOrElse(0) { 0f }
            val centerZ = node.translation.getOrElse(2) { 0f }
            val file = kotlin.math.round((maxX - centerX) / xStep).toInt()
            val rank = kotlin.math.round((maxZ - centerZ) / zStep).toInt()
            if (file !in 0..7 || rank !in 0..7) continue
            val parts = ArrayList<GlbPiecePart>()
            for (primitive in mesh.primitives) {
                val source = primitive.positions
                if (source.size < 9 || source.size / 3 > 65535 ||
                    primitive.indices.size < 3 || primitive.indices.any { it !in 0 until source.size / 3 || it > 65535 }) continue
                val local = FloatArray(source.size)
                for (i in source.indices step 3) {
                    val p = transformGlbPoint(node, source[i], source[i + 1], source[i + 2])
                    local[i] = (p[0] - centerX) / xStep
                    local[i + 1] = p[1] / xStep
                    local[i + 2] = -(p[2] - centerZ) / zStep
                }
                val normals = FloatArray(local.size)
                if (primitive.normals != null && primitive.normals.size == normals.size) {
                    for (i in primitive.normals.indices step 3) {
                        normals[i] = primitive.normals[i]
                        normals[i + 1] = primitive.normals[i + 1]
                        normals[i + 2] = -primitive.normals[i + 2]
                    }
                } else {
                    for (i in primitive.indices.indices step 3) {
                        if (i + 2 >= primitive.indices.size) break
                        val a = primitive.indices[i] * 3
                        val b = primitive.indices[i + 1] * 3
                        val d = primitive.indices[i + 2] * 3
                        val ux = local[b] - local[a]; val uy = local[b + 1] - local[a + 1]; val uz = local[b + 2] - local[a + 2]
                        val vx = local[d] - local[a]; val vy = local[d + 1] - local[a + 1]; val vz = local[d + 2] - local[a + 2]
                        var nx = uy * vz - uz * vy
                        var ny = uz * vx - ux * vz
                        var nz = ux * vy - uy * vx
                        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.00001f)
                        nx /= length; ny /= length; nz /= length
                        for (v in intArrayOf(a, b, d)) { normals[v] = nx; normals[v + 1] = ny; normals[v + 2] = nz }
                    }
                }
                val vb = ByteBuffer.allocateDirect(local.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(local); position(0) }
                val nb = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(normals); position(0) }
                val shortIndices = ShortArray(primitive.indices.size) { primitive.indices[it].toShort() }
                val ib = ByteBuffer.allocateDirect(shortIndices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(shortIndices); position(0) }
                parts.add(GlbPiecePart(vb, nb, ib, shortIndices.size, false))
            }
            if (parts.isNotEmpty()) glbPieceTemplates.putIfAbsent(side to type, parts)
        }
        glbPiecesReady = glbPieceTemplates.size == 12
        android.util.Log.i("3DChess", "Alternate GLB piece templates loaded: ${glbPieceTemplates.size}/12")
        if (!glbPiecesReady) android.util.Log.e("3DChess", "Alternate chess set incomplete: ${glbPieceTemplates.keys}")
    }

    private fun drawGlbPiece(piece: ChessPiece, file: Int, x: Float, z: Float) {
        val parts = glbPieceTemplates[piece.side to piece.type] ?: return
        val pieceColor = if (piece.side == Side.WHITE) whitePieceColor else blackPieceColor
        val body = floatArrayOf(red(pieceColor), green(pieceColor), blue(pieceColor), 1f)
        val gold = floatArrayOf(0.78f, 0.58f, 0.25f, 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, activePieceLift, z)
        Matrix.scaleM(model, 0, activePieceScaleX, activePieceScaleY, activePieceScaleZ)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform1f(glowHandle, activePieceGlow)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        for (part in parts) {
            val color = if (part.gold) gold else body
            GLES20.glUniform4f(colorHandle, color[0], color[1], color[2], color[3])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, part.vertices)
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 12, part.normals)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, part.count, GLES20.GL_UNSIGNED_SHORT, part.indices)
        }
    }

    private fun transformGlbPoint(node: GlbAsset.Node, x: Float, y: Float, z: Float): FloatArray {
        val matrix = node.matrix
        if (matrix != null && matrix.size == 16) {
            return floatArrayOf(
                matrix[0]*x + matrix[4]*y + matrix[8]*z + matrix[12],
                matrix[1]*x + matrix[5]*y + matrix[9]*z + matrix[13],
                matrix[2]*x + matrix[6]*y + matrix[10]*z + matrix[14]
            )
        }
        val sx = x * node.scale.getOrElse(0) { 1f }
        val sy = y * node.scale.getOrElse(1) { 1f }
        val sz = z * node.scale.getOrElse(2) { 1f }
        val qx = node.rotation.getOrElse(0) { 0f }; val qy = node.rotation.getOrElse(1) { 0f }
        val qz = node.rotation.getOrElse(2) { 0f }; val qw = node.rotation.getOrElse(3) { 1f }
        val ix = qw*sx + qy*sz - qz*sy
        val iy = qw*sy + qz*sx - qx*sz
        val iz = qw*sz + qx*sy - qy*sx
        val iw = -qx*sx - qy*sy - qz*sz
        val rx = ix*qw + iw*(-qx) + iy*(-qz) - iz*(-qy)
        val ry = iy*qw + iw*(-qy) + iz*(-qx) - ix*(-qz)
        val rz = iz*qw + iw*(-qz) + ix*(-qy) - iy*(-qx)
        return floatArrayOf(rx + node.translation.getOrElse(0) { 0f }, ry + node.translation.getOrElse(1) { 0f }, rz + node.translation.getOrElse(2) { 0f })
    }

    private fun drawGlbBoard() {
        GLES20.glUseProgram(program)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        for (mesh in glbBoardMeshes) {
            GLES20.glUniform4f(colorHandle, mesh.color[0], mesh.color[1], mesh.color[2], mesh.color[3])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, mesh.vertices)
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 12, mesh.normals)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.count, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        }
    }

    private fun drawContactShadow(x: Float, z: Float) {
        // Concentric translucent discs approximate soft PCF contact shadows
        // without a costly per-frame shadow-map pass on mobile GPUs.
        val rings = listOf(0.42f to 0.025f, 0.34f to 0.035f, 0.25f to 0.045f)
        GLES20.glDepthMask(false)
        for ((radius, alpha) in rings) {
            val vertices = FloatArray(32 * 9)
            var offset = 0
            for (i in 0 until 32) {
                val a = i * (2.0 * Math.PI / 32.0)
                val b = (i + 1) * (2.0 * Math.PI / 32.0)
                vertices[offset++] = 0f; vertices[offset++] = 0f; vertices[offset++] = 0f
                vertices[offset++] = (kotlin.math.cos(a) * radius).toFloat(); vertices[offset++] = 0f; vertices[offset++] = (kotlin.math.sin(a) * radius * 0.72f).toFloat()
                vertices[offset++] = (kotlin.math.cos(b) * radius).toFloat(); vertices[offset++] = 0f; vertices[offset++] = (kotlin.math.sin(b) * radius * 0.72f).toFloat()
            }
            drawTriangles(vertices, x, 0.067f, z, 0.012f, 0.010f, 0.009f, alpha)
        }
        GLES20.glDepthMask(true)
    }

    private fun drawCylinder(x:Float,y:Float,z:Float,radius:Float,height:Float,r:Float,g:Float,b:Float) {
        val vertices = ArrayList<Float>()
        val segments = 64
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
        val rings = 16
        val slices = 32
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

    private fun drawTriangles(vertices:FloatArray,x:Float,y:Float,z:Float,r:Float,g:Float,b:Float,alpha:Float = 1f) {
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
        Matrix.translateM(model,0,x,y + activePieceLift,z)
        if (activePieceYaw != 0f) Matrix.rotateM(model,0,activePieceYaw,0f,1f,0f)
        Matrix.scaleM(model,0,activePieceScaleX,activePieceScaleY,activePieceScaleZ)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glUniform1f(glowHandle,activePieceGlow)
        GLES20.glUniform4f(colorHandle,r,g,b,alpha.coerceIn(0f,1f))
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,buffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle,3,GLES20.GL_FLOAT,false,12,normalBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.size/3)
    }

    private fun drawCube(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float) {
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,x,y + activePieceLift,z); if (activePieceYaw != 0f) Matrix.rotateM(model,0,activePieceYaw,0f,1f,0f); Matrix.scaleM(model,0,activePieceScaleX,activePieceScaleY,activePieceScaleZ); Matrix.scaleM(model,0,sx,sy,sz)
        Matrix.multiplyMM(mvp,0,vp,0,model,0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glUniform1f(glowHandle,activePieceGlow)
        GLES20.glUniform4f(colorHandle,r,g,b,1f)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,12,vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle,3,GLES20.GL_FLOAT,false,12,cubeNormalBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,indices.size,GLES20.GL_UNSIGNED_SHORT,indexBuffer)
    }

    private fun compile(type:Int, source:String):Int {
        val shader=GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
        return shader
    }
}