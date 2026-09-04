package com.mostafazahra.chesswake.puzzle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mostafazahra.chesswake.chess.Move
import com.mostafazahra.chesswake.chess.Piece
import com.mostafazahra.chesswake.chess.PieceColor
import com.mostafazahra.chesswake.chess.Position
import com.mostafazahra.chesswake.chess.Square
import com.mostafazahra.chesswake.ui.theme.ChessWakeThemeDefaults
import com.mostafazahra.chesswake.ui.theme.HighlightCheck
import com.mostafazahra.chesswake.ui.theme.HighlightLastMove
import com.mostafazahra.chesswake.ui.theme.HighlightLegalTarget
import com.mostafazahra.chesswake.ui.theme.HighlightSelected
import com.mostafazahra.chesswake.ui.theme.HighlightWrongMove

/** Everything the board draws on top of the plain squares. */
data class BoardHighlights(
    /** The square currently selected by the solver. */
    val selected: Square? = null,
    /** Squares the selected piece may legally move to. */
    val legalTargets: Set<Square> = emptySet(),
    /** The last move played, tinted on both of its squares. */
    val lastMove: Move? = null,
    /** A king in check, tinted red. */
    val checkedKing: Square? = null,
    /** A move that was just rejected, briefly tinted red. */
    val rejected: Move? = null,
) {
    companion object {
        val NONE = BoardHighlights()
    }
}

/**
 * An 8x8 chess board drawn entirely with Compose primitives.
 *
 * Pieces are Unicode glyphs rather than images: no bitmap assets to ship, they
 * scale to any screen, and they stay legible with the system font scale turned
 * up. The filled glyph set (U+265A..U+265F) is used for *both* colours because
 * the outline set renders inconsistently across Android font stacks; colour and a
 * contrasting shadow are what distinguish the sides.
 *
 * @param orientation which colour sits at the bottom. Puzzles are shown from the
 *   solver's point of view, which is not always White.
 * @param onSquareTap null makes the board read-only.
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    orientation: PieceColor = PieceColor.WHITE,
    highlights: BoardHighlights = BoardHighlights.NONE,
    showCoordinates: Boolean = true,
    onSquareTap: ((Square) -> Unit)? = null,
) {
    val boardColors = ChessWakeThemeDefaults.boardColors
    val density = LocalDensity.current

    // Files run left to right and ranks top to bottom *as displayed*, which is
    // reversed when the board is flipped for a Black-to-move puzzle.
    val files = remember(orientation) {
        if (orientation == PieceColor.WHITE) (0..7).toList() else (7 downTo 0).toList()
    }
    val ranks = remember(orientation) {
        if (orientation == PieceColor.WHITE) (7 downTo 0).toList() else (0..7).toList()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        // Deriving every size from one eighth of the board width keeps the grid
        // exact and the glyphs correctly scaled on any screen or font scale.
        val squareSize = maxWidth / 8
        val glyphSize: TextUnit = with(density) { (squareSize * GLYPH_RATIO).toPx().toSp() }
        val coordinateSize: TextUnit = with(density) { (squareSize * COORDINATE_RATIO).toPx().toSp() }
        val coordinatePadding = squareSize * COORDINATE_PADDING_RATIO
        val dotSize = squareSize * TARGET_DOT_RATIO
        val ringSize = squareSize * CAPTURE_RING_RATIO
        val ringWidth = squareSize * CAPTURE_RING_WIDTH_RATIO

        Column(modifier = Modifier.fillMaxSize()) {
            ranks.forEachIndexed { rowIndex, rank ->
                Row(
                    modifier = Modifier.size(width = squareSize * 8, height = squareSize),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    files.forEachIndexed { columnIndex, file ->
                        val square = Square.of(file, rank)
                        val piece = position.pieceAt(square)
                        val isLight = square.isLight
                        val baseColor = if (isLight) boardColors.light else boardColors.dark
                        val isBottomRow = rowIndex == ranks.lastIndex
                        val isLeftColumn = columnIndex == 0
                        val interactionSource = remember(square) { MutableInteractionSource() }

                        val lastMove = highlights.lastMove
                        val rejected = highlights.rejected
                        val tintedLast = lastMove != null && (lastMove.from == square || lastMove.to == square)
                        val tintedRejected = rejected != null && (rejected.from == square || rejected.to == square)

                        Box(
                            modifier = Modifier
                                .size(squareSize)
                                .background(baseColor)
                                .semantics { contentDescription = squareDescription(square, piece) }
                                .then(
                                    if (onSquareTap != null) {
                                        Modifier.clickable(
                                            interactionSource = interactionSource,
                                            // Ripple looks wrong on a chess board.
                                            indication = null,
                                            enabled = true,
                                        ) { onSquareTap(square) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            if (tintedLast) Tint(Modifier.fillMaxSize(), HighlightLastMove)
                            if (square == highlights.selected) Tint(Modifier.fillMaxSize(), HighlightSelected)
                            if (tintedRejected) Tint(Modifier.fillMaxSize(), HighlightWrongMove)
                            if (square == highlights.checkedKing) Tint(Modifier.fillMaxSize(), HighlightCheck)

                            // Coordinates are tucked into the corners of the outer
                            // squares, lichess-style, so they never cover a piece.
                            if (showCoordinates && isLeftColumn) {
                                Text(
                                    text = (rank + 1).toString(),
                                    fontSize = coordinateSize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isLight) boardColors.dark else boardColors.light,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(coordinatePadding),
                                )
                            }
                            if (showCoordinates && isBottomRow) {
                                Text(
                                    text = ('a' + file).toString(),
                                    fontSize = coordinateSize,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isLight) boardColors.dark else boardColors.light,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(coordinatePadding),
                                )
                            }

                            when {
                                piece != null -> PieceGlyph(
                                    piece = piece,
                                    fontSize = glyphSize,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                square in highlights.legalTargets -> Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(dotSize)
                                        .clip(CircleShape)
                                        .background(HighlightLegalTarget),
                                )
                            }

                            // A capture target gets a ring around the enemy piece.
                            if (piece != null && square in highlights.legalTargets) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(ringSize)
                                        .border(width = ringWidth, color = HighlightLegalTarget, shape = CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A translucent overlay covering one square. */
@Composable
private fun Tint(modifier: Modifier, color: Color) {
    Box(modifier = modifier.background(color))
}

/**
 * One chess piece.
 *
 * The shadow is what makes a white glyph readable on a light square and a black
 * glyph readable on a dark one; without it the pieces vanish into the board.
 */
@Composable
private fun PieceGlyph(piece: Piece, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val isWhite = piece.isWhite
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Normal,
        color = if (isWhite) Color(0xFFFCFCFC) else Color(0xFF141414),
        shadow = Shadow(
            color = if (isWhite) Color(0xB3000000) else Color(0x66FFFFFF),
            offset = Offset(1.5f, 2.5f),
            blurRadius = 2f,
        ),
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = piece.type.unicodeGlyph, style = style, maxLines = 1)
    }
}

/** Accessible description of one square, e.g. `e4, white pawn` or `d5, empty`. */
private fun squareDescription(square: Square, piece: Piece?): String = if (piece == null) {
    "${square.name}, empty"
} else {
    val colour = piece.color.displayName.lowercase()
    val kind = piece.type.name.lowercase().replaceFirstChar { it.titlecase() }
    "${square.name}, $colour $kind"
}

/**
 * The board plus a one-line caption underneath.
 *
 * Split out so the alarm screen and the practice screen get identical board
 * treatment without duplicating layout code.
 */
@Composable
fun BoardWithCaption(
    position: Position,
    caption: String,
    modifier: Modifier = Modifier,
    orientation: PieceColor = PieceColor.WHITE,
    highlights: BoardHighlights = BoardHighlights.NONE,
    showCoordinates: Boolean = true,
    onSquareTap: ((Square) -> Unit)? = null,
    captionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    captionStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChessBoard(
            position = position,
            orientation = orientation,
            highlights = highlights,
            showCoordinates = showCoordinates,
            onSquareTap = onSquareTap,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = caption,
            style = captionStyle,
            color = captionColor,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private const val GLYPH_RATIO = 0.80f
private const val COORDINATE_RATIO = 0.20f
private const val COORDINATE_PADDING_RATIO = 0.05f
private const val TARGET_DOT_RATIO = 0.26f
private const val CAPTURE_RING_RATIO = 0.92f
private const val CAPTURE_RING_WIDTH_RATIO = 0.045f
