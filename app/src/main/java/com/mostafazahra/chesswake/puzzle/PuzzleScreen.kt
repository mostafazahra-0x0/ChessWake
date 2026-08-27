package com.mostafazahra.chesswake.puzzle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightSquare = Color(0xFFF0D9B5)
private val DarkSquare = Color(0xFFB58863)
private val SelectedSquare = Color(0xFF7FBF7F)

/**
 * An interactive chess board (Step 3). Tap a piece to select its square, then tap a
 * destination square to make a move. The move is compared against [puzzle.solution].
 * On a correct move, [onSolved] is invoked.
 */
@Composable
fun PuzzleScreen(
    puzzle: Puzzle,
    onSolved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = remember(puzzle) { Board.fromFen(puzzle.fen) }
    var selected by remember { mutableStateOf<Square?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var solved by remember { mutableStateOf(false) }

    fun onSquareTap(square: Square) {
        if (solved) return

        val current = selected
        when {
            current == null -> {
                if (board.pieceAt(square) != null) selected = square
            }
            current == square -> selected = null
            else -> {
                val move = Move(current, square)
                if (move == puzzle.solution) {
                    solved = true
                    feedback = "Correct! Well done."
                    onSolved()
                } else {
                    feedback = "Try again"
                    selected = null
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(puzzle.description, style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        ChessBoard(
            board = board,
            selected = selected,
            onSquareTap = ::onSquareTap,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = feedback
                ?: (selected?.let { "Selected: $it — now pick a destination" } ?: "Tap a piece to start"),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (feedback == "Correct! Well done.") FontWeight.Bold else FontWeight.Normal,
            color = when {
                feedback == "Correct! Well done." -> Color(0xFF2E7D32)
                feedback != null -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ChessBoard(
    board: Board,
    selected: Square?,
    onSquareTap: (Square) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (rank in 7 downTo 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (file in 0..7) {
                    val square = Square(file, rank)
                    val isLight = (file + rank) % 2 == 0
                    val background = when {
                        square == selected -> SelectedSquare
                        isLight -> LightSquare
                        else -> DarkSquare
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(background)
                            .clickable { onSquareTap(square) },
                        contentAlignment = Alignment.Center,
                    ) {
                        board.pieceAt(square)?.let { piece ->
                            Text(
                                text = pieceGlyph(piece),
                                fontSize = 24.sp,
                                color = if (piece.isUpperCase()) Color.Black else Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
