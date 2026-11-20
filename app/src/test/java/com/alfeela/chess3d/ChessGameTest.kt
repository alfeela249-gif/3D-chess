package com.alfeela.chess3d

import org.junit.Assert.*
import org.junit.Test

class ChessGameTest {
    @Test fun initialPositionHasTwentyLegalMoves() {
        val game = ChessGame()
        assertEquals(20, game.legalMoves().size)
        assertEquals(Side.WHITE, game.sideToMove)
    }

    @Test fun acceptsLegalMovesAndAlternatesTurns() {
        val game = ChessGame()
        assertTrue(game.makeMove(ChessMove(4, 6, 4, 4))) // e2-e4
        assertEquals(Side.BLACK, game.sideToMove)
        assertEquals(ChessPiece(Side.WHITE, PieceType.PAWN), game.pieceAt(4, 4))
        assertFalse(game.makeMove(ChessMove(3, 6, 3, 4))) // White cannot move twice
    }

    @Test fun rejectsMovesThatLeaveKingInCheck() {
        val game = ChessGame()
        assertFalse(game.makeMove(ChessMove(4, 6, 4, 3)))
        assertEquals(20, game.legalMoves().size)
    }

    @Test fun detectsFoolsMateCheckmate() {
        val game = ChessGame()
        assertTrue(game.makeMove(ChessMove(5, 6, 5, 5))) // f2-f3
        assertTrue(game.makeMove(ChessMove(4, 1, 4, 3))) // e7-e5
        assertTrue(game.makeMove(ChessMove(6, 6, 6, 4))) // g2-g4
        assertTrue(game.makeMove(ChessMove(3, 0, 7, 4))) // Qd8-h4#
        assertTrue(game.isInCheck(Side.WHITE))
        assertTrue(game.isCheckmate())
        assertTrue(game.legalMoves().isEmpty())
    }

    @Test fun supportsEnPassantCapture() {
        val game = ChessGame()
        assertTrue(game.makeMove(ChessMove(4, 6, 4, 4))) // e2-e4
        assertTrue(game.makeMove(ChessMove(0, 1, 0, 2))) // a7-a6
        assertTrue(game.makeMove(ChessMove(4, 4, 4, 3))) // e4-e5
        assertTrue(game.makeMove(ChessMove(3, 1, 3, 3))) // d7-d5
        assertTrue(game.legalMovesFrom(4, 3).contains(ChessMove(4, 3, 3, 2)))
        assertTrue(game.makeMove(ChessMove(4, 3, 3, 2))) // e5xd6 e.p.
        assertNull(game.pieceAt(3, 3))
        assertEquals(ChessPiece(Side.WHITE, PieceType.PAWN), game.pieceAt(3, 2))
    }

    @Test fun computerSelectsALegalMove() {
        val game = ChessGame()
        val move = game.chooseComputerMove(depth = 1)
        assertNotNull(move)
        assertTrue(move in game.legalMoves())
    }

    @Test fun castlingMovesKingAndRookTogether() {
        val game = ChessGame()
        // Clear the king-side path with legal development moves.
        assertTrue(game.makeMove(ChessMove(4, 6, 4, 4))) // e4
        assertTrue(game.makeMove(ChessMove(4, 1, 4, 3))) // ...e5
        assertTrue(game.makeMove(ChessMove(6, 7, 5, 5))) // Nf3
        assertTrue(game.makeMove(ChessMove(1, 0, 2, 2))) // ...Nc6
        assertTrue(game.makeMove(ChessMove(5, 7, 2, 4))) // Bc4
        assertTrue(game.makeMove(ChessMove(6, 0, 5, 2))) // ...Nf6

        assertTrue(game.legalMovesFrom(4, 7).contains(ChessMove(4, 7, 6, 7)))
        assertTrue(game.makeMove(ChessMove(4, 7, 6, 7))) // O-O
        assertEquals(ChessPiece(Side.WHITE, PieceType.KING), game.pieceAt(6, 7))
        assertEquals(ChessPiece(Side.WHITE, PieceType.ROOK), game.pieceAt(5, 7))
        assertNull(game.pieceAt(4, 7))
        assertNull(game.pieceAt(7, 7))
    }

    @Test fun cannotCastleThroughAnAttackedSquare() {
        val game = ChessGame()
        // The initial position blocks castling; no castle is offered.
        assertFalse(game.legalMovesFrom(4, 7).any { kotlin.math.abs(it.toFile - it.fromFile) == 2 })
    }

    @Test fun pawnPromotionOffersAllFourPieceChoices() {
        val game = ChessGame()
        assertTrue(game.makeMove(ChessMove(0, 6, 0, 4))) // a2-a4
        assertTrue(game.makeMove(ChessMove(1, 1, 1, 3))) // ...b7-b5
        assertTrue(game.makeMove(ChessMove(0, 4, 1, 3))) // axb5
        assertTrue(game.makeMove(ChessMove(7, 1, 7, 2))) // ...h7-h6
        assertTrue(game.makeMove(ChessMove(1, 3, 1, 2))) // b5-b6
        assertTrue(game.makeMove(ChessMove(7, 2, 7, 3))) // ...h6-h5
        assertTrue(game.makeMove(ChessMove(1, 2, 1, 1))) // b6-b7
        assertTrue(game.makeMove(ChessMove(7, 3, 7, 4))) // ...h5-h4

        val promotions = game.legalMovesFrom(1, 1)
            .filter { it.toFile == 1 && it.toRank == 0 }
            .mapNotNull { it.promotion }.toSet()
        assertEquals(setOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT), promotions)
        assertTrue(game.makeMove(ChessMove(1, 1, 1, 0, PieceType.KNIGHT)))
        assertEquals(ChessPiece(Side.WHITE, PieceType.KNIGHT), game.pieceAt(1, 0))
    }
}
