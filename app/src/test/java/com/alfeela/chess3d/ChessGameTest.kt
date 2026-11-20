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
}
