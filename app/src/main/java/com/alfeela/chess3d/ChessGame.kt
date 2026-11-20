package com.alfeela.chess3d

/**
 * Offline chess rules engine. Coordinates use file/rank 0..7:
 * (0,0) is a8, (7,7) is h1. White moves toward decreasing ranks.
 */
enum class Side { WHITE, BLACK;
    fun opposite() = if (this == WHITE) BLACK else WHITE
}
enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
data class ChessPiece(val side: Side, val type: PieceType)
data class ChessMove(
    val fromFile: Int, val fromRank: Int,
    val toFile: Int, val toRank: Int,
    val promotion: PieceType? = null
)

class ChessGame {
    private val squares = Array(8) { arrayOfNulls<ChessPiece>(8) }
    var sideToMove: Side = Side.WHITE
        private set
    private var whiteKingSide = true
    private var whiteQueenSide = true
    private var blackKingSide = true
    private var blackQueenSide = true
    private var enPassant: Pair<Int, Int>? = null
    var lastMove: ChessMove? = null
        private set

    init { reset() }

    fun reset() {
        for (rank in 0..7) for (file in 0..7) squares[rank][file] = null
        val back = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP,
            PieceType.QUEEN, PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK)
        for (file in 0..7) {
            squares[0][file] = ChessPiece(Side.BLACK, back[file])
            squares[1][file] = ChessPiece(Side.BLACK, PieceType.PAWN)
            squares[6][file] = ChessPiece(Side.WHITE, PieceType.PAWN)
            squares[7][file] = ChessPiece(Side.WHITE, back[file])
        }
        sideToMove = Side.WHITE
        whiteKingSide = true; whiteQueenSide = true
        blackKingSide = true; blackQueenSide = true
        enPassant = null
        lastMove = null
    }

    fun pieceAt(file: Int, rank: Int): ChessPiece? =
        if (inside(file, rank)) squares[rank][file] else null

    fun legalMovesFrom(file: Int, rank: Int): List<ChessMove> {
        if (!inside(file, rank)) return emptyList()
        val piece = squares[rank][file] ?: return emptyList()
        if (piece.side != sideToMove) return emptyList()
        return legalMoves().filter { it.fromFile == file && it.fromRank == rank }
    }

    fun legalMoves(): List<ChessMove> {
        val candidates = mutableListOf<ChessMove>()
        for (rank in 0..7) for (file in 0..7) {
            val p = squares[rank][file] ?: continue
            if (p.side == sideToMove) candidates += pseudoMoves(file, rank, p)
        }
        return candidates.filter { move ->
            val copy = copyPosition()
            copy.applyUnchecked(move)
            !copy.isInCheck(sideToMove)
        }
    }

    fun makeMove(move: ChessMove): Boolean {
        if (move !in legalMoves()) return false
        applyUnchecked(move)
        sideToMove = sideToMove.opposite()
        lastMove = move
        return true
    }

    fun isInCheck(side: Side): Boolean {
        val king = findKing(side) ?: return true
        return isAttacked(king.first, king.second, side.opposite())
    }

    fun isCheckmate(): Boolean = isInCheck(sideToMove) && legalMoves().isEmpty()
    fun isStalemate(): Boolean = !isInCheck(sideToMove) && legalMoves().isEmpty()

    private fun pseudoMoves(file: Int, rank: Int, piece: ChessPiece): List<ChessMove> {
        val out = mutableListOf<ChessMove>()
        fun add(f: Int, r: Int) {
            if (!inside(f, r)) return
            val target = squares[r][f]
            if (target == null || target.side != piece.side) {
                if (target?.type != PieceType.KING) out += ChessMove(file, rank, f, r)
            }
        }
        when (piece.type) {
            PieceType.PAWN -> {
                val direction = if (piece.side == Side.WHITE) -1 else 1
                val start = if (piece.side == Side.WHITE) 6 else 1
                val one = rank + direction
                if (inside(file, one) && squares[one][file] == null) {
                    addPawnMove(out, file, rank, file, one, piece.side)
                    val two = rank + 2 * direction
                    if (rank == start && squares[two][file] == null) out += ChessMove(file, rank, file, two)
                }
                for (df in intArrayOf(-1, 1)) {
                    val f = file + df; val r = rank + direction
                    if (!inside(f, r)) continue
                    val target = squares[r][f]
                    if (target != null && target.side != piece.side && target.type != PieceType.KING) {
                        addPawnMove(out, file, rank, f, r, piece.side)
                    } else if (enPassant == Pair(f, r)) {
                        out += ChessMove(file, rank, f, r)
                    }
                }
            }
            PieceType.KNIGHT -> {
                val jumps = arrayOf(1 to 2, 2 to 1, 2 to -1, 1 to -2,
                    -1 to -2, -2 to -1, -2 to 1, -1 to 2)
                jumps.forEach { add(file + it.first, rank + it.second) }
            }
            PieceType.KING -> {
                for (dr in -1..1) for (df in -1..1) if (df != 0 || dr != 0) add(file + df, rank + dr)
                addCastles(out, piece.side)
            }
            PieceType.BISHOP -> addRays(out, file, rank, piece.side, listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1))
            PieceType.ROOK -> addRays(out, file, rank, piece.side, listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
            PieceType.QUEEN -> addRays(out, file, rank, piece.side,
                listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1, 1 to 0, -1 to 0, 0 to 1, 0 to -1))
        }
        return out
    }

    private fun addPawnMove(out: MutableList<ChessMove>, ff: Int, fr: Int, tf: Int, tr: Int, side: Side) {
        val lastRank = if (side == Side.WHITE) 0 else 7
        out += ChessMove(ff, fr, tf, tr, if (tr == lastRank) PieceType.QUEEN else null)
    }

    private fun addRays(out: MutableList<ChessMove>, file: Int, rank: Int, side: Side, dirs: List<Pair<Int, Int>>) {
        for ((df, dr) in dirs) {
            var f = file + df; var r = rank + dr
            while (inside(f, r)) {
                val target = squares[r][f]
                if (target == null) out += ChessMove(file, rank, f, r)
                else {
                    if (target.side != side && target.type != PieceType.KING) out += ChessMove(file, rank, f, r)
                    break
                }
                f += df; r += dr
            }
        }
    }

    private fun addCastles(out: MutableList<ChessMove>, side: Side) {
        val rank = if (side == Side.WHITE) 7 else 0
        val kingRight = if (side == Side.WHITE) whiteKingSide else blackKingSide
        val queenRight = if (side == Side.WHITE) whiteQueenSide else blackQueenSide
        val enemy = side.opposite()
        if (squares[rank][4] != ChessPiece(side, PieceType.KING) || isAttacked(4, rank, enemy)) return
        if (kingRight && squares[rank][7] == ChessPiece(side, PieceType.ROOK) &&
            squares[rank][5] == null && squares[rank][6] == null &&
            !isAttacked(5, rank, enemy) && !isAttacked(6, rank, enemy)) {
            out += ChessMove(4, rank, 6, rank)
        }
        if (queenRight && squares[rank][0] == ChessPiece(side, PieceType.ROOK) &&
            squares[rank][1] == null && squares[rank][2] == null && squares[rank][3] == null &&
            !isAttacked(3, rank, enemy) && !isAttacked(2, rank, enemy)) {
            out += ChessMove(4, rank, 2, rank)
        }
    }

    private fun applyUnchecked(move: ChessMove) {
        val piece = squares[move.fromRank][move.fromFile] ?: return
        val captured = squares[move.toRank][move.toFile]
        if (piece.type == PieceType.KING) {
            if (piece.side == Side.WHITE) { whiteKingSide = false; whiteQueenSide = false }
            else { blackKingSide = false; blackQueenSide = false }
            if (kotlin.math.abs(move.toFile - move.fromFile) == 2) {
                val rookFrom = if (move.toFile == 6) 7 else 0
                val rookTo = if (move.toFile == 6) 5 else 3
                squares[move.toRank][rookTo] = squares[move.toRank][rookFrom]
                squares[move.toRank][rookFrom] = null
            }
        }
        if (piece.type == PieceType.ROOK) revokeRookRight(piece.side, move.fromFile, move.fromRank)
        if (captured?.type == PieceType.ROOK) revokeRookRight(captured.side, move.toFile, move.toRank)

        if (piece.type == PieceType.PAWN && enPassant == Pair(move.toFile, move.toRank) &&
            move.fromFile != move.toFile && captured == null) {
            val capturedRank = move.toRank + if (piece.side == Side.WHITE) 1 else -1
            squares[capturedRank][move.toFile] = null
        }
        squares[move.toRank][move.toFile] = if (piece.type == PieceType.PAWN && move.toRank in listOf(0, 7))
            ChessPiece(piece.side, move.promotion ?: PieceType.QUEEN) else piece
        squares[move.fromRank][move.fromFile] = null

        enPassant = if (piece.type == PieceType.PAWN && kotlin.math.abs(move.toRank - move.fromRank) == 2)
            move.fromFile to ((move.fromRank + move.toRank) / 2) else null
    }

    private fun revokeRookRight(side: Side, file: Int, rank: Int) {
        if (side == Side.WHITE && rank == 7) {
            if (file == 0) whiteQueenSide = false
            if (file == 7) whiteKingSide = false
        } else if (side == Side.BLACK && rank == 0) {
            if (file == 0) blackQueenSide = false
            if (file == 7) blackKingSide = false
        }
    }

    private fun isAttacked(file: Int, rank: Int, by: Side): Boolean {
        val pawnSourceRank = rank + if (by == Side.WHITE) 1 else -1
        for (df in intArrayOf(-1, 1)) {
            val f = file + df
            if (inside(f, pawnSourceRank) && squares[pawnSourceRank][f] == ChessPiece(by, PieceType.PAWN)) return true
        }
        val knightJumps = arrayOf(1 to 2, 2 to 1, 2 to -1, 1 to -2,
            -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        if (knightJumps.any { (df, dr) -> pieceIs(file + df, rank + dr, by, PieceType.KNIGHT) }) return true
        for (dr in -1..1) for (df in -1..1) {
            if ((df != 0 || dr != 0) && pieceIs(file + df, rank + dr, by, PieceType.KING)) return true
        }
        val diagonal = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        val straight = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        if (rayAttacked(file, rank, by, diagonal, setOf(PieceType.BISHOP, PieceType.QUEEN))) return true
        return rayAttacked(file, rank, by, straight, setOf(PieceType.ROOK, PieceType.QUEEN))
    }

    private fun rayAttacked(file: Int, rank: Int, by: Side, dirs: List<Pair<Int, Int>>, types: Set<PieceType>): Boolean {
        for ((df, dr) in dirs) {
            var f = file + df; var r = rank + dr
            while (inside(f, r)) {
                val p = squares[r][f]
                if (p != null) {
                    if (p.side == by && p.type in types) return true
                    break
                }
                f += df; r += dr
            }
        }
        return false
    }

    private fun pieceIs(file: Int, rank: Int, side: Side, type: PieceType) =
        inside(file, rank) && squares[rank][file] == ChessPiece(side, type)

    private fun findKing(side: Side): Pair<Int, Int>? {
        for (r in 0..7) for (f in 0..7)
            if (squares[r][f] == ChessPiece(side, PieceType.KING)) return f to r
        return null
    }

    private fun copyPosition(): ChessGame {
        val copy = ChessGame()
        for (r in 0..7) for (f in 0..7) copy.squares[r][f] = squares[r][f]
        copy.sideToMove = sideToMove
        copy.whiteKingSide = whiteKingSide; copy.whiteQueenSide = whiteQueenSide
        copy.blackKingSide = blackKingSide; copy.blackQueenSide = blackQueenSide
        copy.enPassant = enPassant
        return copy
    }

    private fun inside(file: Int, rank: Int) = file in 0..7 && rank in 0..7
}
