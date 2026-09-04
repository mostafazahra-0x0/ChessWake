package com.mostafazahra.chesswake.chess

import kotlin.math.abs

/**
 * Legal move generation and the rules that decide whether a position is over.
 *
 * Design notes
 * ------------
 * Generation is split into two passes on purpose:
 *
 *  1. [pseudoLegalMoves] produces everything the pieces *could* do geometrically,
 *     ignoring whether the mover's own king would be left in check.
 *  2. [legalMoves] filters those by playing each move on a copied board and
 *     testing the king for attack.
 *
 * The "make the move, then look for checks" approach is slower than pinned-piece
 * bitboards, but it is impossible to get subtly wrong — which matters far more
 * here, because a wrong answer means an alarm the user cannot dismiss. Correctness
 * is pinned down by perft and legal-move vectors generated with python-chess
 * (see `tools/` and `app/src/test/resources/chess/`).
 */
object MoveGenerator {

    // Direction tables as (fileDelta, rankDelta) pairs.
    private val KNIGHT_DELTAS = listOf(
        1 to 2, 2 to 1, 2 to -1, 1 to -2,
        -1 to -2, -2 to -1, -2 to 1, -1 to 2,
    )
    private val KING_DELTAS = listOf(
        1 to 0, 1 to 1, 0 to 1, -1 to 1,
        -1 to 0, -1 to -1, 0 to -1, 1 to -1,
    )
    private val BISHOP_DIRECTIONS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val ROOK_DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val QUEEN_DIRECTIONS = BISHOP_DIRECTIONS + ROOK_DIRECTIONS

    /** Files the king lands on after short (king-side) and long (queen-side) castling. */
    private const val KING_SIDE_DESTINATION_FILE = 6
    private const val QUEEN_SIDE_DESTINATION_FILE = 2

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Every move the side to move may legally play, in a deterministic order. */
    fun legalMoves(position: Position): List<Move> {
        // A position with no king cannot be reasoned about; bail out rather than
        // declaring every move legal.
        if (position.board.kingSquare(position.sideToMove) == null) return emptyList()

        val moves = pseudoLegalMoves(position)
        if (moves.isEmpty()) return moves

        val enemy = position.sideToMove.opposite
        val legal = ArrayList<Move>(moves.size)
        for (move in moves) {
            val next = applyMove(position, move)
            val ownKing = next.board.kingSquare(position.sideToMove) ?: continue
            if (!isSquareAttacked(next.board, ownKing, enemy)) legal += move
        }
        return legal
    }

    /** True when [move] is one of [legalMoves] for this position. */
    fun isLegal(position: Position, move: Move): Boolean = legalMoves(position).contains(move)

    /**
     * Moves the pieces could make geometrically, without checking whether the
     * mover's king ends up in check. Castling is included only when the king is
     * not in check and neither transit square is attacked, since those are
     * rules about the move itself rather than about pins.
     */
    fun pseudoLegalMoves(position: Position): List<Move> {
        val moves = ArrayList<Move>(48)
        val board = position.board
        val us = position.sideToMove
        val them = us.opposite

        for (index in 0 until 64) {
            val from = Square(index)
            val piece = board.squares[index] ?: continue
            if (piece.color != us) continue
            when (piece.type) {
                PieceType.PAWN -> generatePawnMoves(position, from, moves)
                PieceType.KNIGHT -> generateStepMoves(board, from, us, KNIGHT_DELTAS, moves)
                PieceType.KING -> {
                    generateStepMoves(board, from, us, KING_DELTAS, moves)
                    generateCastling(position, from, moves)
                }

                PieceType.BISHOP -> generateSlideMoves(board, from, us, BISHOP_DIRECTIONS, moves)
                PieceType.ROOK -> generateSlideMoves(board, from, us, ROOK_DIRECTIONS, moves)
                PieceType.QUEEN -> generateSlideMoves(board, from, us, QUEEN_DIRECTIONS, moves)
            }
        }

        // A deterministic order keeps `legalMoves` stable across runs, which the
        // test vectors depend on.
        moves.sortWith(compareBy({ it.from.index }, { it.to.index }, { it.promotion?.ordinal ?: -1 }))
        return moves
    }

    /**
     * True when [target] is attacked by any piece of [byColor].
     *
     * This is the single primitive every check-related rule is built from:
     * legality filtering, "may I castle through here", and mate detection.
     */
    fun isSquareAttacked(board: Board, target: Square, byColor: PieceColor): Boolean {
        // Pawns: a pawn of `byColor` standing one rank *behind* the target (from the
        // target's point of view) attacks it. White pawns attack upwards, so for
        // White we look one rank below the target.
        val pawnRankDelta = if (byColor == PieceColor.WHITE) -1 else 1
        val pawn = Piece.of(PieceType.PAWN, byColor)
        for (fileDelta in intArrayOf(-1, 1)) {
            val square = target.offset(fileDelta, pawnRankDelta) ?: continue
            if (board.squares[square.index] == pawn) return true
        }

        // Knights.
        val knight = Piece.of(PieceType.KNIGHT, byColor)
        for ((fileDelta, rankDelta) in KNIGHT_DELTAS) {
            val square = target.offset(fileDelta, rankDelta) ?: continue
            if (board.squares[square.index] == knight) return true
        }

        // Kings. Needed so that two kings can never stand adjacent, and so that a
        // king cannot castle through a square guarded by the enemy king.
        val king = Piece.of(PieceType.KING, byColor)
        for ((fileDelta, rankDelta) in KING_DELTAS) {
            val square = target.offset(fileDelta, rankDelta) ?: continue
            if (board.squares[square.index] == king) return true
        }

        // Sliding pieces: walk each ray and look only at the first blocker.
        val rook = Piece.of(PieceType.ROOK, byColor)
        val queen = Piece.of(PieceType.QUEEN, byColor)
        val bishop = Piece.of(PieceType.BISHOP, byColor)

        for ((fileDelta, rankDelta) in ROOK_DIRECTIONS) {
            val blocker = firstBlocker(board, target, fileDelta, rankDelta) ?: continue
            if (blocker == rook || blocker == queen) return true
        }
        for ((fileDelta, rankDelta) in BISHOP_DIRECTIONS) {
            val blocker = firstBlocker(board, target, fileDelta, rankDelta) ?: continue
            if (blocker == bishop || blocker == queen) return true
        }

        return false
    }

    /** True when the side to move is in check. */
    fun isInCheck(position: Position): Boolean {
        val king = position.board.kingSquare(position.sideToMove) ?: return false
        return isSquareAttacked(position.board, king, position.sideToMove.opposite)
    }

    /** True when [color]'s king is under attack, regardless of whose turn it is. */
    fun isKingAttacked(position: Position, color: PieceColor): Boolean {
        val king = position.board.kingSquare(color) ?: return false
        return isSquareAttacked(position.board, king, color.opposite)
    }

    /**
     * Produces the position after [move], updating the board, castling rights,
     * the en-passant target and both move clocks.
     *
     * [move] is assumed to be legal; callers must not pass arbitrary input. The
     * side to move is flipped, so the result is always "the opponent to play".
     */
    fun applyMove(position: Position, move: Move): Position {
        val board = position.board
        val mover = board.pieceAt(move.from) ?: return position
        val us = mover.color
        val newBoard = board.copy()

        val capturedPiece = board.pieceAt(move.to)
        var wasCapture = capturedPiece != null

        // En-passant capture: the captured pawn is not on `move.to`, it sits beside it.
        val isEnPassant = mover.type.isPawn &&
            position.enPassantTarget != null &&
            move.to == position.enPassantTarget &&
            capturedPiece == null
        if (isEnPassant) {
            val capturedPawnSquare = Square.of(move.to.file, move.from.rank)
            newBoard.set(capturedPawnSquare, null)
            wasCapture = true
        }

        newBoard.set(move.from, null)
        val placedPiece = if (mover.type.isPawn && move.promotion != null) {
            Piece.of(move.promotion, us)
        } else {
            mover
        }
        newBoard.set(move.to, placedPiece)

        // Castling: the king moved two files, so the rook jumps over it.
        val isCastling = mover.type == PieceType.KING && abs(move.to.file - move.from.file) == 2
        if (isCastling) {
            val rank = move.from.rank
            if (move.to.file == KING_SIDE_DESTINATION_FILE) {
                newBoard.set(Square.of(5, rank), board.pieceAt(Square.of(7, rank)))
                newBoard.set(Square.of(7, rank), null)
            } else {
                newBoard.set(Square.of(3, rank), board.pieceAt(Square.of(0, rank)))
                newBoard.set(Square.of(0, rank), null)
            }
        }

        var rights = position.castlingRights
        if (mover.type == PieceType.KING) {
            rights = rights.withoutColor(us)
        }
        // Clearing on both `from` and `to` covers "my rook moved" and "I captured
        // the enemy rook on its home square" with the same four lines.
        if (move.from == Square.A1 || move.to == Square.A1) rights = rights.copy(whiteQueenSide = false)
        if (move.from == Square.H1 || move.to == Square.H1) rights = rights.copy(whiteKingSide = false)
        if (move.from == Square.A8 || move.to == Square.A8) rights = rights.copy(blackQueenSide = false)
        if (move.from == Square.H8 || move.to == Square.H8) rights = rights.copy(blackKingSide = false)

        val isDoublePawnPush = mover.type.isPawn && abs(move.to.rank - move.from.rank) == 2
        val newEnPassantTarget = if (isDoublePawnPush) {
            Square.of(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else {
            null
        }

        val halfmoveClock = if (mover.type.isPawn || wasCapture) 0 else position.halfmoveClock + 1
        val fullmoveNumber = if (us == PieceColor.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber

        return Position(
            board = newBoard,
            sideToMove = us.opposite,
            castlingRights = rights,
            enPassantTarget = newEnPassantTarget,
            halfmoveClock = halfmoveClock,
            fullmoveNumber = fullmoveNumber,
        )
    }

    /** Classifies a position as ongoing, check, mate, stalemate or one of the draws. */
    fun status(position: Position): GameStatus {
        val hasMoves = legalMoves(position).isNotEmpty()
        val inCheck = isInCheck(position)
        return when {
            !hasMoves && inCheck -> GameStatus.CHECKMATE
            !hasMoves -> GameStatus.STALEMATE
            hasInsufficientMaterial(position.board) -> GameStatus.INSUFFICIENT_MATERIAL
            position.halfmoveClock >= 100 -> GameStatus.FIFTY_MOVE_RULE
            inCheck -> GameStatus.CHECK
            else -> GameStatus.ONGOING
        }
    }

    /** True when [move] takes an enemy piece (including en passant). */
    fun isCapture(position: Position, move: Move): Boolean {
        if (position.board.isOccupied(move.to)) return true
        val mover = position.board.pieceAt(move.from) ?: return false
        return mover.type.isPawn &&
            move.to == position.enPassantTarget &&
            move.from.file != move.to.file
    }

    /** True when [move] is a king moving two files, i.e. castling. */
    fun isCastling(position: Position, move: Move): Boolean {
        val mover = position.board.pieceAt(move.from) ?: return false
        return mover.type == PieceType.KING && abs(move.to.file - move.from.file) == 2
    }

    /** True when [move] is an en-passant pawn capture. */
    fun isEnPassant(position: Position, move: Move): Boolean {
        val mover = position.board.pieceAt(move.from) ?: return false
        return mover.type.isPawn &&
            move.to == position.enPassantTarget &&
            position.board.isEmpty(move.to)
    }

    /**
     * FIDE-style insufficient material: no pawns, rooks or queens remain, and
     * neither side can mate the other even with unlimited help from the loser.
     *
     * Covers king vs king, king + one minor vs king, and king + bishop vs
     * king + bishop with both bishops on the same square colour.
     */
    fun hasInsufficientMaterial(board: Board): Boolean {
        for (index in 0 until 64) {
            val type = board.squares[index]?.type ?: continue
            if (type == PieceType.PAWN || type == PieceType.ROOK || type == PieceType.QUEEN) return false
        }

        val whiteMinors = board.squaresOf(PieceColor.WHITE, PieceType.BISHOP) +
            board.squaresOf(PieceColor.WHITE, PieceType.KNIGHT)
        val blackMinors = board.squaresOf(PieceColor.BLACK, PieceType.BISHOP) +
            board.squaresOf(PieceColor.BLACK, PieceType.KNIGHT)

        return when {
            whiteMinors.size <= 1 && blackMinors.isEmpty() -> true
            blackMinors.size <= 1 && whiteMinors.isEmpty() -> true
            whiteMinors.size == 1 && blackMinors.size == 1 -> {
                val whiteBishop = board.squaresOf(PieceColor.WHITE, PieceType.BISHOP).singleOrNull()
                val blackBishop = board.squaresOf(PieceColor.BLACK, PieceType.BISHOP).singleOrNull()
                // Two knights (or knight + bishop) can still mate with help, so only
                // the "lone bishops on the same colour" case counts as a draw.
                whiteBishop != null && blackBishop != null && whiteBishop.isLight == blackBishop.isLight
            }

            else -> false
        }
    }

    /**
     * Counts leaf nodes at [depth] — the standard move-generator correctness test.
     *
     * `perft(start, 1..5)` must equal 20, 400, 8902, 197281, 4865609.
     */
    fun perft(position: Position, depth: Int): Long {
        if (depth <= 0) return 1L
        val moves = legalMoves(position)
        if (depth == 1) return moves.size.toLong()
        var nodes = 0L
        for (move in moves) nodes += perft(applyMove(position, move), depth - 1)
        return nodes
    }

    // ---------------------------------------------------------------------
    // Generation helpers
    // ---------------------------------------------------------------------

    private fun generatePawnMoves(position: Position, from: Square, out: MutableList<Move>) {
        val board = position.board
        val us = position.sideToMove
        val direction = us.pawnDirection
        val promotionRank = us.promotionRank

        // Single push (and the double push from the starting rank).
        val oneAhead = from.offset(0, direction)
        if (oneAhead != null && board.isEmpty(oneAhead)) {
            if (oneAhead.rank == promotionRank) {
                addPromotions(from, oneAhead, out)
            } else {
                out += Move(from, oneAhead)
                if (from.rank == us.pawnStartRank) {
                    val twoAhead = from.offset(0, direction * 2)
                    if (twoAhead != null && board.isEmpty(twoAhead)) {
                        out += Move(from, twoAhead)
                    }
                }
            }
        }

        // Captures, including en passant.
        for (fileDelta in intArrayOf(-1, 1)) {
            val target = from.offset(fileDelta, direction) ?: continue
            val occupant = board.pieceAt(target)
            when {
                occupant != null && occupant.color != us ->
                    if (target.rank == promotionRank) addPromotions(from, target, out) else out += Move(from, target)

                occupant == null && position.enPassantTarget == target ->
                    // Legality of the resulting position (the "en-passant pin" case)
                    // is settled by legalMoves(), so it is safe to emit here.
                    out += Move(from, target)
            }
        }
    }

    private fun addPromotions(from: Square, to: Square, out: MutableList<Move>) {
        for (type in PieceType.promotionChoices) out += Move(from, to, type)
    }

    private fun generateStepMoves(
        board: Board,
        from: Square,
        us: PieceColor,
        deltas: List<Pair<Int, Int>>,
        out: MutableList<Move>,
    ) {
        for ((fileDelta, rankDelta) in deltas) {
            val target = from.offset(fileDelta, rankDelta) ?: continue
            val occupant = board.pieceAt(target)
            if (occupant == null || occupant.color != us) out += Move(from, target)
        }
    }

    private fun generateSlideMoves(
        board: Board,
        from: Square,
        us: PieceColor,
        directions: List<Pair<Int, Int>>,
        out: MutableList<Move>,
    ) {
        for ((fileDelta, rankDelta) in directions) {
            var target = from.offset(fileDelta, rankDelta)
            while (target != null) {
                val occupant = board.pieceAt(target)
                when {
                    occupant == null -> out += Move(from, target)
                    occupant.color != us -> {
                        out += Move(from, target)
                        target = null // blocked by the capture
                    }

                    else -> target = null // blocked by our own piece
                }
                if (target != null) target = target.offset(fileDelta, rankDelta)
            }
        }
    }

    private fun generateCastling(position: Position, kingFrom: Square, out: MutableList<Move>) {
        val us = position.sideToMove
        val board = position.board
        val them = us.opposite
        val rank = us.backRank

        // Only the king on its home square may castle, and never out of check.
        if (kingFrom.file != 4 || kingFrom.rank != rank) return
        if (isSquareAttacked(board, kingFrom, them)) return

        val rook = Piece.of(PieceType.ROOK, us)

        // King side: rook on the h-file, f- and g-file empty, e/f/g safe.
        if (position.castlingRights.canCastle(us, kingSide = true)) {
            val rookSquare = Square.of(7, rank)
            val through = Square.of(5, rank)
            val destination = Square.of(KING_SIDE_DESTINATION_FILE, rank)
            if (board.pieceAt(rookSquare) == rook &&
                board.isEmpty(through) &&
                board.isEmpty(destination) &&
                !isSquareAttacked(board, through, them) &&
                !isSquareAttacked(board, destination, them)
            ) {
                out += Move(kingFrom, destination)
            }
        }

        // Queen side: rook on the a-file, b/c/d empty, e/d/c safe. The b-file only
        // has to be *empty*; the king never travels across it.
        if (position.castlingRights.canCastle(us, kingSide = false)) {
            val rookSquare = Square.of(0, rank)
            val bFile = Square.of(1, rank)
            val through = Square.of(3, rank)
            val destination = Square.of(QUEEN_SIDE_DESTINATION_FILE, rank)
            if (board.pieceAt(rookSquare) == rook &&
                board.isEmpty(bFile) &&
                board.isEmpty(through) &&
                board.isEmpty(destination) &&
                !isSquareAttacked(board, through, them) &&
                !isSquareAttacked(board, destination, them)
            ) {
                out += Move(kingFrom, destination)
            }
        }
    }

    /** First piece encountered walking from [from] along a ray, or null if the ray exits the board. */
    private fun firstBlocker(board: Board, from: Square, fileDelta: Int, rankDelta: Int): Piece? {
        var square = from.offset(fileDelta, rankDelta)
        while (square != null) {
            val piece = board.pieceAt(square)
            if (piece != null) return piece
            square = square.offset(fileDelta, rankDelta)
        }
        return null
    }
}
