package com.mostafazahra.chesswake.chess

import com.mostafazahra.chesswake.testutil.TestVectors
import com.mostafazahra.chesswake.testutil.arr
import com.mostafazahra.chesswake.testutil.bool
import com.mostafazahra.chesswake.testutil.int
import com.mostafazahra.chesswake.testutil.obj
import com.mostafazahra.chesswake.testutil.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-vector tests for the move generator.
 *
 * Every expectation in `src/test/resources/chess` was produced by
 * `tools/generate_test_vectors.py` using python-chess, so this compares the Kotlin
 * engine against an independent implementation rather than against its own ideas.
 * The vectors cover the positions where hand-written generators break: pins,
 * castling rights and transit, en passant, promotions, and check evasion.
 */
class ChessEngineVectorTest {

    @Test
    fun `legal move sets match python-chess exactly`() {
        val failures = mutableListOf<String>()
        var checked = 0

        legalMovesVectors.forEach { (name, vector) ->
            val position = position(vector.getValue("fen").str)
            val expected = vector.getValue("moves").arr.map { it.obj.getValue("uci").str }.toSet()
            val actual = MoveGenerator.legalMoves(position).map { it.uci }.toSet()

            checked++
            if (expected != actual) {
                failures += "$name: missing=${expected - actual} unexpected=${actual - expected}"
            }
            val expectedCount = vector.getValue("legalMoveCount").int
            if (actual.size != expectedCount) {
                failures += "$name: expected $expectedCount legal moves, generated ${actual.size}"
            }
            val expectedCheck = vector.getValue("isInCheck").bool
            if (MoveGenerator.isInCheck(position) != expectedCheck) {
                failures += "$name: isInCheck expected $expectedCheck"
            }
        }

        assertTrue("checked $checked positions\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `move flags match python-chess`() {
        val failures = mutableListOf<String>()

        legalMovesVectors.forEach { (name, vector) ->
            val position = position(vector.getValue("fen").str)
            vector.getValue("moves").arr.forEach { element ->
                val expected = element.obj
                val move = Move.fromUci(expected.getValue("uci").str)
                assertNotNull("$name: could not parse ${expected.getValue("uci").str}", move)
                require(move != null)

                fun check(label: String, actual: Boolean) {
                    if (actual != expected.getValue(label).bool) {
                        failures += "$name ${move.uci}: $label expected ${expected.getValue(label).bool}, got $actual"
                    }
                }

                check("isCapture", MoveGenerator.isCapture(position, move))
                check("isCastling", MoveGenerator.isCastling(position, move))
                check("isEnPassant", MoveGenerator.isEnPassant(position, move))
                check("isPromotion", move.isPromotion)
                // After the move the sides swap, so "the opponent is in check" is
                // exactly what "this move gives check" means.
                check("givesCheck", MoveGenerator.isInCheck(MoveGenerator.applyMove(position, move)))
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `SAN matches python-chess`() {
        val failures = mutableListOf<String>()

        legalMovesVectors.forEach { (name, vector) ->
            val position = position(vector.getValue("fen").str)
            vector.getValue("moves").arr.forEach { element ->
                val expected = element.obj
                val move = Move.fromUci(expected.getValue("uci").str)
                requireNotNull(move)
                val san = MoveNotation.san(position, move)
                if (san != expected.getValue("san").str) {
                    failures += "$name ${move.uci}: expected ${expected.getValue("san").str}, got $san"
                }
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `perft node counts match python-chess`() {
        perftVectors.forEach { (name, vector) ->
            val position = position(vector.getValue("fen").str)
            vector.getValue("depths").obj.forEach { (depthText, expected) ->
                val depth = depthText.toInt()
                val nodes = MoveGenerator.perft(position, depth)
                assertEquals("$name perft($depth)", expected.str.toLong(), nodes)
            }
        }
    }

    @Test
    fun `FEN survives a parse and serialise round trip`() {
        fenVectors.forEach { (name, vector) ->
            val input = vector.getValue("input").str
            val position = Position.fromFen(input)
            assertNotNull("$name: could not parse $input", position)
            assertEquals("$name round trip", vector.getValue("expected").str, requireNotNull(position).fen)
        }
    }

    @Test
    fun `perft of the initial position matches the published numbers`() {
        // Hardcoded rather than read from the vectors: these five numbers are the
        // most widely reproduced figures in chess programming, so if the vector
        // file were ever regenerated incorrectly this test would still catch it.
        val start = Position.start()
        val published = listOf(20L, 400L, 8_902L, 197_281L)
        published.forEachIndexed { index, expected ->
            assertEquals("startpos perft(${index + 1})", expected, MoveGenerator.perft(start, index + 1))
        }
    }

    private fun position(fen: String): Position =
        requireNotNull(Position.fromFen(fen)) { "vector FEN did not parse: $fen" }

    private companion object {
        val legalMovesVectors: Map<String, JsonObject> =
            TestVectors.section(TestVectors.load("chess/legal_moves.json"), "positions")

        val perftVectors: Map<String, JsonObject> =
            TestVectors.section(TestVectors.load("chess/perft.json"), "positions")

        val fenVectors: Map<String, JsonObject> =
            TestVectors.section(TestVectors.load("chess/fen_roundtrip.json"), "positions")
    }
}
