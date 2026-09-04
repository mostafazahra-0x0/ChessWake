package com.mostafazahra.chesswake.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-written rules tests.
 *
 * The golden vectors in [ChessEngineVectorTest] prove the generator agrees with
 * python-chess on a broad sample; these pin down the individual rules by name, so
 * a regression says "castling through check is broken" rather than "12 of 474
 * moves differ". Every position and count here was confirmed against python-chess
 * before being written down.
 */
class ChessRulesTest {

    @Test
    fun `both castlings are available and move king and rook`() {
        val position = position(CASTLE_BOTH)
        val moves = MoveGenerator.legalMoves(position).map { it.uci }

        assertEquals(26, moves.size)
        assertTrue("king-side castling should be legal", "e1g1" in moves)
        assertTrue("queen-side castling should be legal", "e1c1" in moves)

        val afterKingSide = MoveGenerator.applyMove(position, move("e1g1"))
        assertEquals(Piece.WHITE_KING, afterKingSide.pieceAt(square("g1")))
        assertEquals(Piece.WHITE_ROOK, afterKingSide.pieceAt(square("f1")))
        assertNull("a1 must be empty after O-O", afterKingSide.pieceAt(square("a1")))
        assertEquals(PieceColor.BLACK, afterKingSide.sideToMove)
        // White has castled; Black has not.
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", afterKingSide.fen)

        val afterQueenSide = MoveGenerator.applyMove(position, move("e1c1"))
        assertEquals(Piece.WHITE_KING, afterQueenSide.pieceAt(square("c1")))
        assertEquals(Piece.WHITE_ROOK, afterQueenSide.pieceAt(square("d1")))
    }

    @Test
    fun `castling through an attacked square is illegal`() {
        val position = position(CASTLE_THROUGH_CHECK)
        val moves = MoveGenerator.legalMoves(position).map { it.uci }

        assertEquals(23, moves.size)
        assertFalse("f1 is attacked by the f8 rook, so O-O is illegal", "e1g1" in moves)
        assertTrue("the queen side is untouched, so O-O-O is legal", "e1c1" in moves)
    }

    @Test
    fun `castling out of check is illegal`() {
        val position = position(CASTLE_IN_CHECK)

        assertTrue(MoveGenerator.isInCheck(position))
        val moves = MoveGenerator.legalMoves(position).map { it.uci }
        assertEquals(3, moves.size)
        assertFalse("e1g1" in moves)
        assertFalse("e1c1" in moves)
    }

    @Test
    fun `castling rights are consumed by moving the king or a rook`() {
        var position = position(CASTLE_BOTH)
        assertEquals(CastlingRights.START, position.castlingRights)

        // Moving a rook forfeits only that side's right on that colour.
        position = MoveGenerator.applyMove(position, move("a1a2"))
        assertEquals("Kkq", position.castlingRights.fen)
        position = MoveGenerator.applyMove(position, move("h8h7"))
        assertEquals("Kq", position.castlingRights.fen)
        assertTrue(position.castlingRights.whiteKingSide)
        assertFalse(position.castlingRights.whiteQueenSide)
        assertTrue(position.castlingRights.blackQueenSide)
        assertFalse(position.castlingRights.blackKingSide)

        // Moving a king forfeits both of its rights.
        position = MoveGenerator.applyMove(position, move("e1e2"))
        assertEquals("q", position.castlingRights.fen)
        position = MoveGenerator.applyMove(position, move("e8e7"))
        assertEquals(CastlingRights.NONE, position.castlingRights)
        assertEquals("-", position.castlingRights.fen)
    }

    @Test
    fun `en passant captures the pawn that just advanced two squares`() {
        val position = position(EN_PASSANT)
        val moves = MoveGenerator.legalMoves(position)

        assertEquals(31, moves.size)
        val capture = moves.single { it.uci == "e5f6" }
        assertTrue(MoveGenerator.isEnPassant(position, capture))
        assertTrue(MoveGenerator.isCapture(position, capture))

        val after = MoveGenerator.applyMove(position, capture)
        assertNull("the captured pawn on f5 is removed", after.pieceAt(square("f5")))
        assertEquals(Piece.WHITE_PAWN, after.pieceAt(square("f6")))
    }

    @Test
    fun `the en passant target is only set after a double push`() {
        val start = Position.start()
        assertNull(start.enPassantTarget)

        val afterDoublePush = MoveGenerator.applyMove(start, move("e2e4"))
        assertEquals(Square.parse("e3"), afterDoublePush.enPassantTarget)

        val afterSinglePush = MoveGenerator.applyMove(start, move("g1f3"))
        assertNull(afterSinglePush.enPassantTarget)
    }

    @Test
    fun `a pawn reaching the last rank promotes to any of four pieces`() {
        val position = position(PROMOTION)
        val moves = MoveGenerator.legalMoves(position).map { it.uci }.toSet()

        assertEquals(9, moves.size)
        assertEquals(
            setOf("a7a8q", "a7a8r", "a7a8b", "a7a8n"),
            moves.filter { it.startsWith("a7a8") }.toSet(),
        )

        val afterQueen = MoveGenerator.applyMove(position, move("a7a8q"))
        assertEquals(Piece.WHITE_QUEEN, afterQueen.pieceAt(square("a8")))
    }

    @Test
    fun `an absolutely pinned piece may only move along the pin`() {
        val position = position(ABSOLUTE_PIN)
        val queenMoves = MoveGenerator.legalMoves(position)
            .filter { it.from == Square.parse("e7") }
            .map { it.uci }
            .toSet()

        assertEquals(setOf("e7e1", "e7e2", "e7e3", "e7e4", "e7e5", "e7e6"), queenMoves)
        assertEquals(10, MoveGenerator.legalMoves(position).size)
    }

    @Test
    fun `a move that leaves your own king in check is not generated`() {
        val position = position(ABSOLUTE_PIN)
        // The black king is on e8 and the white rook on e1: nothing on the e-file
        // may step aside, and the king may not step onto the file.
        val illegal = listOf("e7d7", "e7f7", "e8d7", "e8f7")
        val legal = MoveGenerator.legalMoves(position).map { it.uci }
        illegal.forEach { assertFalse("$it must be illegal", it in legal) }
    }

    @Test
    fun `status reports checkmate stalemate and draws`() {
        assertEquals(GameStatus.CHECKMATE, MoveGenerator.status(position(SCHOLARS_MATE)))
        assertEquals(GameStatus.STALEMATE, MoveGenerator.status(position(STALEMATE)))
        assertEquals(GameStatus.CHECK, MoveGenerator.status(position(CASTLE_IN_CHECK)))
        assertEquals(GameStatus.ONGOING, MoveGenerator.status(Position.start()))
    }

    @Test
    fun `a mating move is reported with a hash in SAN`() {
        val position = position(BACK_RANK)
        val mate = move("a1a8")

        assertTrue(MoveGenerator.isLegal(position, mate))
        assertEquals("Ra8#", MoveNotation.san(position, mate))
        assertEquals(GameStatus.CHECKMATE, MoveGenerator.status(MoveGenerator.applyMove(position, mate)))
    }

    @Test
    fun `insufficient material follows the FIDE rule`() {
        listOf(KING_V_KING, KING_BISHOP_V_KING, KING_KNIGHT_V_KING, BISHOPS_SAME_COLOUR).forEach { fen ->
            assertEquals("$fen should be a draw", GameStatus.INSUFFICIENT_MATERIAL, MoveGenerator.status(position(fen)))
        }
        listOf(KING_ROOK_V_KING, BISHOPS_OPPOSITE_COLOUR, KNIGHT_AND_BISHOP_V_KING).forEach { fen ->
            assertTrue(
                "$fen is not automatically drawn",
                MoveGenerator.status(position(fen)) != GameStatus.INSUFFICIENT_MATERIAL,
            )
        }
    }

    @Test
    fun `the fifty move rule fires at a hundred plies without progress`() {
        val position = position(FIFTY_MOVE_RULE)

        assertEquals(100, position.halfmoveClock)
        assertEquals(GameStatus.FIFTY_MOVE_RULE, MoveGenerator.status(position))
        // One pawn move or capture resets the clock, which is what keeps the rule
        // from firing in a position that is still making progress.
        val progressed = position.copy(halfmoveClock = 99)
        assertEquals(GameStatus.ONGOING, MoveGenerator.status(progressed))
    }

    @Test
    fun `game tracks SAN and detects threefold repetition`() {
        val game = Game(Position.start())
        val played = game.playLine(listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"))

        assertEquals(8, played)
        assertEquals(8, game.plyCount)
        assertEquals(listOf("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8"), game.sanMoves)
        assertTrue(game.isThreefoldRepetition())
        assertEquals(3, game.repetitionCount())
        assertEquals(Position.start().fen, game.position.fen)
    }

    @Test
    fun `undo restores the previous position exactly`() {
        val game = Game(Position.start())

        game.makeMove(move("e2e4"))
        game.makeMove(move("e7e5"))
        assertEquals(2, game.plyCount)

        val undone = game.undo()
        assertEquals("e7e5", undone?.uci)
        assertEquals(1, game.plyCount)
        // Undoing e7e5 leaves the position after e2e4, ep target included.
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", game.position.fen)

        game.reset()
        assertEquals(0, game.plyCount)
        assertEquals(Position.start().fen, game.position.fen)
    }

    @Test
    fun `malformed FEN is rejected rather than half parsed`() {
        assertNull(Position.fromFen("not a fen"))
        assertNull(Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w - - 0 1"))
        // No kings at all is not a chess position.
        assertNull(Position.fromFen("8/8/8/8/8/8/8/8 w - - 0 1"))
    }

    @Test
    fun `square helpers agree with algebraic names`() {
        assertEquals(0, Square.parse("a1")?.index)
        assertEquals(63, Square.parse("h8")?.index)
        assertEquals("e4", Square.of(4, 3).name)
        assertTrue(Square.parse("b1")?.isLight == true)
        assertFalse(Square.parse("a1")?.isLight == true)
        // Offsets must not wrap from the h-file to the a-file.
        assertNull(Square.parse("h1")?.offset(1, 0))
        assertEquals("a2", Square.parse("h1")?.offset(-7, 1)?.name)
    }

    @Test
    fun `material balance counts centipawns per side`() {
        // Values follow the standard scale: pawn 100, knight 320, bishop 330,
        // rook 500, queen 900, king 0 (a king is never "won").
        assertEquals(100, PieceType.PAWN.centipawns)
        assertEquals(500, PieceType.ROOK.centipawns)
        assertEquals(900, PieceType.QUEEN.centipawns)
        assertEquals(0, PieceType.KING.centipawns)

        val startBoard = Position.start().board
        assertEquals(0, Material.balance(startBoard, PieceColor.WHITE))
        assertEquals(0, Material.balance(startBoard, PieceColor.BLACK))

        val whiteUpRook = position("4k3/8/8/8/8/8/8/R3K3 w - - 0 1").board
        assertEquals(500, Material.balance(whiteUpRook, PieceColor.WHITE))
        assertEquals(-500, Material.balance(whiteUpRook, PieceColor.BLACK))
        assertEquals(5, Material.balanceInPawns(whiteUpRook, PieceColor.WHITE))

        // The back-rank puzzle position is a rook up for White, which is what its
        // WIN_MATERIAL variants are graded against.
        val backRank = position(BACK_RANK).board
        assertEquals(800, Material.total(backRank, PieceColor.WHITE))
        assertEquals(300, Material.total(backRank, PieceColor.BLACK))
        assertEquals(500, Material.balance(backRank, PieceColor.WHITE))
    }

    private fun position(fen: String): Position =
        requireNotNull(Position.fromFen(fen)) { "test FEN did not parse: $fen" }

    private fun move(uci: String): Move = requireNotNull(Move.fromUci(uci)) { "bad UCI: $uci" }

    /** Square.parse is nullable; the squares used here are all real. */
    private fun square(name: String): Square =
        requireNotNull(Square.parse(name)) { "bad square name: $name" }

    private companion object {
        const val CASTLE_BOTH = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        const val CASTLE_THROUGH_CHECK = "4kr2/8/8/8/8/8/8/R3K2R w KQ - 0 1"
        const val CASTLE_IN_CHECK = "4k3/8/8/8/8/8/4r3/R3K2R w KQ - 0 1"
        const val EN_PASSANT = "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"
        const val PROMOTION = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"
        const val ABSOLUTE_PIN = "4k3/4q3/8/8/8/8/8/4R1K1 b - - 0 1"
        const val STALEMATE = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"
        const val SCHOLARS_MATE = "r1bqkbnr/pppp1Qpp/2n5/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
        const val BACK_RANK = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"
        const val FIFTY_MOVE_RULE = "4k3/8/8/8/8/8/8/R3K3 w - - 100 60"
        const val KING_V_KING = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        const val KING_BISHOP_V_KING = "4k3/8/8/8/8/8/8/3BK3 w - - 0 1"
        const val KING_KNIGHT_V_KING = "4k3/8/8/8/8/8/8/3NK3 w - - 0 1"
        const val KING_ROOK_V_KING = "4k3/8/8/8/8/8/8/3RK3 w - - 0 1"
        const val BISHOPS_SAME_COLOUR = "4k3/8/8/7b/8/8/8/3BK3 w - - 0 1"
        const val BISHOPS_OPPOSITE_COLOUR = "4k3/8/8/6b1/8/8/8/3BK3 w - - 0 1"
        const val KNIGHT_AND_BISHOP_V_KING = "4k3/8/8/8/8/2N5/8/3BK3 w - - 0 1"
    }
}
