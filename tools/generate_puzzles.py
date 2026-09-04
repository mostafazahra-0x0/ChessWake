#!/usr/bin/env python3
"""
ChessWake puzzle pipeline.

Takes *positions* (never hand-written solutions), works out with python-chess
what kind of puzzle each one actually is, and emits two artefacts:

  * app/src/main/java/com/mostafazahra/chesswake/puzzle/data/BundledPuzzles.kt
  * app/src/test/resources/puzzles/bundled_puzzles.json

Every emitted puzzle is machine-verified:

  mate in one   -> the mating move exists and every mating move is recorded, so
                   the app can accept a different-but-equally-winning try.
  mate in two   -> the key move forces mate against *every* legal reply, not just
                   against a cooperative one. Replies are deliberately NOT
                   recorded: the app auto-plays whatever the opponent legally
                   plays and then accepts any move that mates.
  win material  -> the key move wins at least a piece against the opponent's best
                   defence, measured with a 3-ply material search.

Anything that fails verification is reported on stderr and dropped, so the
bundled set can never contain a puzzle the app cannot actually solve.

Usage:
    python3 tools/generate_puzzles.py             # regenerate both artefacts
    python3 tools/generate_puzzles.py --report    # print every verified puzzle
    python3 tools/generate_puzzles.py --check     # verify only, write nothing
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import random
import sys
from pathlib import Path

import chess

REPO_ROOT = Path(__file__).resolve().parent.parent
KOTLIN_OUT = REPO_ROOT / "app/src/main/java/com/mostafazahra/chesswake/puzzle/data/BundledPuzzles.kt"
JSON_OUT = REPO_ROOT / "app/src/test/resources/puzzles/bundled_puzzles.json"

MATE_SCORE = 100_000

CENTIPAWNS = {
    chess.PAWN: 100,
    chess.KNIGHT: 320,
    chess.BISHOP: 330,
    chess.ROOK: 500,
    chess.QUEEN: 900,
    chess.KING: 0,
}

MATERIAL_SEARCH_DEPTH = 2  # plies after the key move: their best reply, our best follow-up
MIN_MATERIAL_GAIN = 300    # a puzzle must win at least a minor piece
MAX_PER_SIGNATURE = 2      # how many puzzles may share a theme + piece multiset


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class Candidate:
    """A position offered to the pipeline, before verification."""

    fen: str
    name: str = ""
    hint: str = ""
    description: str = ""
    source: str = "curated"
    preferred: str = "mate"  # "mate" tries mates first, "material" tries tactics first


@dataclasses.dataclass
class Puzzle:
    """A verified puzzle, ready to be emitted."""

    id: str
    name: str
    theme: str
    goal: str
    difficulty: int
    fen: str
    solution: list[str]
    alternatives: list[str]
    hint: str
    description: str
    source: str
    mates_in: int = 0
    material_gain: int = 0


# ---------------------------------------------------------------------------
# Position sanity
# ---------------------------------------------------------------------------


def is_sound_position(board: chess.Board) -> bool:
    """Rejects positions that would make a nonsense puzzle."""
    if not board.is_valid():
        return False
    if board.king(chess.WHITE) is None or board.king(chess.BLACK) is None:
        return False
    if board.is_game_over(claim_draw=False):
        return False
    # A puzzle that starts with the solver already in check reads as "defend",
    # which is a different (and harder) thing to ask of someone at 6am.
    if board.is_check():
        return False
    if board.legal_moves.count() == 0:
        return False
    if chess.popcount(board.occupied_co[chess.WHITE]) < 2:
        return False
    if chess.popcount(board.occupied_co[chess.BLACK]) < 1:
        return False
    # Puzzles should be short: no pawn storms, no 30-piece middlegames.
    if chess.popcount(board.occupied) > 18:
        return False
    return True


def normalise_fen(board: chess.Board) -> str:
    """Keeps the four meaningful FEN fields and resets both move counters."""
    return " ".join(board.fen().split(" ")[:4]) + " 0 1"


CORNERS = {chess.A1, chess.H1, chess.A8, chess.H8}


def derive_mate_name(board: chess.Board, move: chess.Move) -> str:
    """
    Names a mating pattern from the position itself, so generated puzzles read
    like real ones instead of "Position 47".
    """
    piece = board.piece_at(move.from_square)
    if piece is None:
        return "Mate in One"
    piece_name = chess.piece_name(piece.piece_type).capitalize()

    loser = not board.turn
    king_square = board.king(loser)
    if king_square is None:
        return f"{piece_name} Mate"

    back_rank = 7 if loser == chess.BLACK else 0
    on_back_rank = chess.square_rank(king_square) == back_rank

    # Smothered: the king is in a corner and its own pieces take away the escapes.
    if king_square in CORNERS and piece.piece_type == chess.KNIGHT:
        king_file, king_rank = chess.square_file(king_square), chess.square_rank(king_square)
        own_neighbours = 0
        for file_delta in (-1, 0, 1):
            for rank_delta in (-1, 0, 1):
                if file_delta == 0 and rank_delta == 0:
                    continue
                file_index, rank_index = king_file + file_delta, king_rank + rank_delta
                if not (0 <= file_index <= 7 and 0 <= rank_index <= 7):
                    continue
                occupant = board.piece_at(chess.square(file_index, rank_index))
                if occupant is not None and occupant.color == loser:
                    own_neighbours += 1
        if own_neighbours >= 2:
            return "Smothered Mate"

    if piece.piece_type in (chess.ROOK, chess.QUEEN) and on_back_rank:
        if chess.square_rank(move.to_square) == back_rank:
            return "Back-Rank Mate"
        return f"{piece_name} and King Mate"

    if piece.piece_type == chess.PAWN:
        return "Pawn Mate"
    return f"{piece_name} Mate"


def derive_material_name(gain: int) -> str:
    """Names a tactic after what it actually wins."""
    if gain >= 900:
        return "Wins the Queen"
    if gain >= 500:
        return "Wins a Rook"
    if gain >= 330:
        return "Wins a Bishop"
    if gain >= 300:
        return "Wins a Knight"
    return "Wins Material"


def square_hint(board: chess.Board, move: chess.Move) -> str:
    piece = board.piece_at(move.from_square)
    piece_name = chess.piece_name(piece.piece_type) if piece else "piece"
    return f"The {piece_name} on {chess.square_name(move.from_square)} is the one to move."


def side_name(board: chess.Board) -> str:
    return "White" if board.turn == chess.WHITE else "Black"


def mating_moves(board: chess.Board) -> list[chess.Move]:
    found = []
    for move in board.legal_moves:
        board.push(move)
        if board.is_checkmate():
            found.append(move)
        board.pop()
    return found


# ---------------------------------------------------------------------------
# Mate in one
# ---------------------------------------------------------------------------


def verify_mate_in_one(candidate: Candidate, board: chess.Board) -> Puzzle | None:
    mates = mating_moves(board)
    if not mates:
        return None

    key = mates[0]
    return Puzzle(
        id="",
        name=candidate.name or derive_mate_name(board, key),
        theme="mate_in_one",
        goal="CHECKMATE",
        difficulty=1 if len(mates) == 1 and board.legal_moves.count() < 20 else 2,
        fen=normalise_fen(board),
        solution=[key.uci()],
        alternatives=sorted(m.uci() for m in mates),
        hint=candidate.hint or square_hint(board, key),
        description=candidate.description or f"{side_name(board)} to move. Find the mate in one.",
        source=candidate.source,
        mates_in=1,
    )


# ---------------------------------------------------------------------------
# Mate in two
# ---------------------------------------------------------------------------


def verify_mate_in_two(candidate: Candidate, board: chess.Board) -> Puzzle | None:
    """
    Finds a key move after which every legal reply can be met by a mate.

    Pruned hard: keys that leave the opponent more than MAX_REPLIES options are
    skipped, which is both a speed win and a quality filter (a puzzle whose
    opponent has twenty replies is not a two-mover in any meaningful sense).
    """
    if mating_moves(board):
        return None  # that is the easier mate-in-one puzzle

    max_replies = 8
    for key in board.legal_moves:
        board.push(key)
        replies = list(board.legal_moves)
        if not replies or len(replies) > max_replies:
            board.pop()
            continue

        forced = True
        sample_reply = None
        sample_mate = None
        for reply in replies:
            board.push(reply)
            mates = mating_moves(board)
            board.pop()
            if not mates:
                forced = False
                break
            if sample_reply is None:
                sample_reply, sample_mate = reply, mates[0]
        board.pop()

        if forced and sample_reply is not None and sample_mate is not None:
            line = [key.uci(), sample_reply.uci(), sample_mate.uci()]
            return Puzzle(
                id="",
                name=candidate.name or f"{derive_mate_name(board, key)} in Two",
                theme="mate_in_two",
                goal="CHECKMATE",
                difficulty=4 if len(replies) > 2 else 3,
                fen=normalise_fen(board),
                solution=line,
                alternatives=[key.uci()],
                hint=candidate.hint or square_hint(board, key),
                description=candidate.description
                or f"{side_name(board)} to move. Find the mate in two — it works against any defence.",
                source=candidate.source,
                mates_in=2,
            )
    return None


# ---------------------------------------------------------------------------
# Win material
# ---------------------------------------------------------------------------


def static_material(board: chess.Board) -> int:
    """Centipawn balance from the point of view of the side to move."""
    return _material(board, board.turn) - _material(board, not board.turn)


def _material(board: chess.Board, color: chess.Color) -> int:
    total = 0
    for piece_type in CENTIPAWNS:
        total += len(board.pieces(piece_type, color)) * CENTIPAWNS[piece_type]
    return total


def negamax_material(board: chess.Board, depth: int) -> int:
    """Material-only negamax; scores are from the side-to-move's perspective."""
    if board.is_checkmate():
        return -MATE_SCORE - depth
    if board.is_stalemate() or board.is_insufficient_material():
        return 0
    if depth <= 0:
        return static_material(board)

    best = -MATE_SCORE * 2
    for move in board.legal_moves:
        board.push(move)
        score = -negamax_material(board, depth - 1)
        board.pop()
        if score > best:
            best = score
    return best


def verify_win_material(candidate: Candidate, board: chess.Board) -> Puzzle | None:
    """
    Scores every legal first move by what it wins against best defence, measured
    [MATERIAL_SEARCH_DEPTH] plies later. Keeps the puzzle when the best move
    wins at least [MIN_MATERIAL_GAIN].
    """
    # A position with a mate available is a mate puzzle, not a tactic puzzle;
    # labelling it "win material" would make the hint text actively misleading.
    if mating_moves(board):
        return None

    solver = board.turn
    baseline = _material(board, solver) - _material(board, not solver)

    scored: list[tuple[int, chess.Move]] = []
    for move in board.legal_moves:
        board.push(move)
        # Opponent to move now, so negate to get the solver's view.
        score = -negamax_material(board, MATERIAL_SEARCH_DEPTH)
        board.pop()
        # Forcing mate is a different (and better) puzzle; keep it out of this theme.
        if score >= MATE_SCORE:
            continue
        scored.append((score, move))

    if not scored:
        return None

    best_score = max(score for score, _ in scored)
    gain = best_score - baseline
    if gain < MIN_MATERIAL_GAIN:
        return None

    winners = sorted((move for score, move in scored if score == best_score), key=lambda m: m.uci())
    key = winners[0]

    # Record a reference line so the UI can show a hint and replay the idea.
    line = [key.uci()]
    board.push(key)
    best_reply = None
    best_reply_score = None
    for reply in board.legal_moves:
        board.push(reply)
        score = negamax_material(board, MATERIAL_SEARCH_DEPTH - 1)
        board.pop()
        if best_reply_score is None or score < best_reply_score:
            best_reply, best_reply_score = reply, score
    if best_reply is not None:
        line.append(best_reply.uci())
        board.push(best_reply)
        best_follow = None
        best_follow_score = None
        for follow in board.legal_moves:
            board.push(follow)
            score = -negamax_material(board, 0)
            board.pop()
            if best_follow_score is None or score > best_follow_score:
                best_follow, best_follow_score = follow, score
        if best_follow is not None:
            line.append(best_follow.uci())
        board.pop()
    board.pop()

    piece_names = {100: "a pawn", 300: "a piece", 320: "a piece", 330: "a piece", 500: "a rook", 900: "a queen"}
    won = piece_names.get(gain, f"{gain // 100} pawns' worth")

    return Puzzle(
        id="",
        name=candidate.name or derive_material_name(gain),
        theme="win_material",
        goal="WIN_MATERIAL",
        difficulty=3 if gain >= 500 else 2,
        fen=normalise_fen(board),
        solution=line,
        # Deliberately empty: the opponent reply recorded above is only guaranteed
        # to be legal after *this* key move, so no alternative first move is offered.
        alternatives=[],
        hint=candidate.hint or square_hint(board, key),
        description=candidate.description or f"{side_name(board)} to move. Win {won}.",
        source=candidate.source,
        material_gain=gain,
    )


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------

MATE_VERIFIERS = (verify_mate_in_one, verify_mate_in_two)
MATERIAL_VERIFIERS = (verify_win_material,)


def solve_candidate(candidate: Candidate) -> Puzzle | None:
    """Runs the verifiers in the candidate's preferred order."""
    board = chess.Board(candidate.fen)
    if not is_sound_position(board):
        return None

    order = (
        MATE_VERIFIERS + MATERIAL_VERIFIERS
        if candidate.preferred == "mate"
        else MATERIAL_VERIFIERS + MATE_VERIFIERS
    )
    for verifier in order:
        puzzle = verifier(candidate, board)
        if puzzle is not None:
            return puzzle
    return None


# ---------------------------------------------------------------------------
# Candidate pool — curated classics
# ---------------------------------------------------------------------------

CURATED: list[Candidate] = [
    # Back-rank family ------------------------------------------------------
    Candidate("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", "Back-Rank Mate",
              hint="The rook belongs on the eighth rank."),
    Candidate("6k1/5ppp/8/8/8/8/5PPP/1R4K1 w - - 0 1", "Back-Rank from the b-File"),
    Candidate("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", "Back-Rank from the d-File"),
    Candidate("6k1/5ppp/8/8/8/8/Q4PPP/6K1 w - - 0 1", "Queen Takes the Back Rank"),
    Candidate("6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1", "Rook Lift to the Eighth"),
    Candidate("3k3r/3ppppp/8/8/8/8/5PPP/R5K1 w - - 0 1", "Back-Rank Before It Happens", preferred="material"),
    Candidate("5k2/5ppp/8/8/8/8/5PPP/R3R1K1 w - - 0 1", "Two Rooks, One File"),
    Candidate("6k1/5p1p/6p1/8/8/8/5PPP/3R2K1 w - - 0 1", "Back-Rank Against a Fianchetto"),
    Candidate("5rk1/5ppp/8/8/8/8/5PPP/R3R1K1 w - - 0 1", "Rook Against Rook on the Back Rank"),

    # King and queen --------------------------------------------------------
    Candidate("k7/8/1K6/8/8/8/8/6Q1 w - - 0 1", "Queen and King Box Mate",
              hint="Cut off the eighth rank, close to the king."),
    Candidate("7k/8/6K1/8/8/8/8/5Q2 w - - 0 1", "Queen Mate on the Back Rank"),
    Candidate("7k/8/6K1/8/8/8/8/R7 w - - 0 1", "Rook Box Mate"),
    Candidate("k7/8/K7/8/8/8/8/1Q6 w - - 0 1", "Box Mate from the Corner"),
    Candidate("3k4/3K4/8/8/8/8/8/Q7 w - - 0 1", "Queen Drives the King Back"),
    Candidate("7k/5K2/8/8/8/8/8/1Q6 w - - 0 1", "King Supports the Queen"),

    # Two rooks -------------------------------------------------------------
    Candidate("7k/R7/8/8/8/8/1R6/7K w - - 0 1", "Ladder Mate",
              hint="One rook holds the seventh; the other finishes on the eighth."),
    Candidate("7k/8/8/8/8/8/R7/1R5K w - - 0 1", "Climbing the Ladder",
              hint="Put a rook on the seventh rank first."),
    Candidate("8/k7/8/8/8/8/1R6/R6K w - - 0 1", "Ladder in the Corner"),
    Candidate("7k/8/6R1/8/8/8/8/R6K w - - 0 1", "Ladder from the Sixth"),
    Candidate("8/8/8/8/8/R7/k7/R6K w - - 0 1", "Squeeze on the Edge"),

    # Knight and rook -------------------------------------------------------
    Candidate("7k/8/5N2/8/8/7R/8/6K1 w - - 0 1", "Arabian Mate",
              hint="The knight guards two squares; the rook needs the seventh."),
    Candidate("7k/8/5N2/8/8/8/R7/6K1 w - - 0 1", "Arabian Mate Approach"),
    Candidate("6k1/8/5N2/8/8/8/7R/6K1 w - - 0 1", "Knight and Rook Teamwork"),

    # Smothered mate --------------------------------------------------------
    Candidate("6rk/6pp/8/4N3/8/8/8/6K1 w - - 0 1", "Smothered Mate",
              hint="The king is buried by its own pieces. A knight does the work."),
    Candidate("5r1k/6pp/8/8/8/8/6PP/4N2K w - - 0 1", "Knight Round to Smother"),
    Candidate("6rk/7p/6p1/8/8/8/7P/4N1K1 w - - 0 1", "Buried Alive"),

    # Bishops and batteries -------------------------------------------------
    Candidate("6k1/5ppp/8/8/2B5/8/5PPP/3Q2K1 w - - 0 1", "Queen and Bishop Battery"),
    Candidate("r5k1/5ppp/8/8/8/8/5PPP/2B2RK1 w - - 0 1", "Bishop Pins, Rook Finishes"),
    Candidate("5rk1/5ppp/8/8/8/8/5PPP/1B2R1K1 w - - 0 1", "Rook to the Eighth Again"),
    Candidate("6k1/5ppp/8/8/8/1B6/5PPP/2Q3K1 w - - 0 1", "Battery on the Diagonal"),

    # Pawn mates and promotions ---------------------------------------------
    Candidate("4k3/4P3/4K3/8/8/8/8/8 w - - 0 1", "Pawn Promotion",
              hint="Promote with check, and the king has nowhere to go."),
    Candidate("7k/6pP/6K1/8/8/8/8/8 w - - 0 1", "Pawn Supported by the King"),
    Candidate("8/2k1P3/2K5/8/8/8/8/8 w - - 0 1", "Promotion on the Edge"),
    Candidate("8/3k4/3P4/3K4/8/8/8/8 w - - 0 1", "Pawn and King Finish"),

    # Tactics that win material ----------------------------------------------
    Candidate("r3k3/8/1P6/3N4/8/8/8/4K3 w - - 0 1", "Royal Fork", preferred="material",
              hint="One piece can attack the king and the rook at once."),
    Candidate("4k3/8/8/8/3N4/8/4q3/4K3 w - - 0 1", "Fork the King and Queen", preferred="material"),
    Candidate("r5k1/5ppp/8/8/8/5N2/5PPP/R3K3 w - - 0 1", "Knight Jump to the Fork", preferred="material"),
    Candidate("6k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1", "Queen Double Attack", preferred="material"),
    Candidate("2k5/8/8/3B4/8/8/2r3q1/4K3 w - - 0 1", "Bishop Battery", preferred="material"),
    Candidate("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1",
              "Scholar's Threat", preferred="material"),
    Candidate("r2qk2r/ppp2ppp/2np1n2/4p1B1/2B1P1b1/2NP1N2/PPP2PPP/R2QK2R w KQkq - 0 1",
              "Pinned Knight", preferred="material"),
    Candidate("6k1/5ppp/8/8/8/8/1r3PPP/1R4K1 w - - 0 1", "Rook Trade on the Second", preferred="material"),
    Candidate("3r2k1/5ppp/8/8/8/8/5PPP/R1Q3K1 w - - 0 1", "Skewer on the Back Rank", preferred="material"),
    Candidate("6k1/5ppp/8/3n4/8/8/5PPP/2B3K1 w - - 0 1", "Bishop Takes the Knight", preferred="material"),
]


# ---------------------------------------------------------------------------
# Candidate pool — generated from natural-looking templates
# ---------------------------------------------------------------------------


def template_candidates(rng: random.Random) -> list[Candidate]:
    """
    Builds positions from recognisable endgame shapes rather than at random, so
    the generated puzzles look like real ones. Every template is still verified
    before it is used; templates that produce nothing simply yield no candidates.
    """
    out: list[Candidate] = []

    def add(pieces: dict[str, str], turn: str, label: str, preferred: str = "mate") -> None:
        board = chess.Board(None)
        for square_name, piece_char in pieces.items():
            square = chess.parse_square(square_name)
            board.set_piece_at(square, chess.Piece.from_symbol(piece_char))
        board.turn = chess.WHITE if turn == "w" else chess.BLACK
        board.set_castling_fen("-")
        if not board.is_valid():
            return
        out.append(Candidate(fen=normalise_fen(board), name=label, source="generated", preferred=preferred))

    files = "abcdefgh"

    # 1. Back-rank mates: a king boxed in by its own pawns, attacker on a file.
    for _ in range(120):
        black_king_file = rng.choice(files[5:])  # f, g or h keeps it corner-ish
        attacker_file = rng.choice([f for f in files if f != black_king_file])
        attacker_rank = rng.randint(1, 4)
        attacker = rng.choice(["R", "R", "Q"])
        king_file = rng.choice([f for f in files if f != attacker_file])
        add(
            {
                f"{black_king_file}8": "k",
                "f7": "p", "g7": "p", "h7": "p",
                f"{attacker_file}{attacker_rank}": attacker,
                f"{king_file}1": "K",
                "f2": "P", "g2": "P", "h2": "P",
            },
            "w",
            "",
        )

    # 2. King and queen box mates on an edge.
    for _ in range(120):
        corner = rng.choice(["a8", "h8", "a1", "h1"])
        white_king = rng.choice(["b6", "c7", "g6", "f7", "b3", "c2", "g3", "f2"])
        queen_file = rng.choice(files)
        queen_rank = rng.randint(1, 6)
        add(
            {corner: "k", white_king: "K", f"{queen_file}{queen_rank}": "Q"},
            "w",
            "",
        )

    # 3. Two-rook ladder mates.
    for _ in range(120):
        black_king = rng.choice(["h8", "a8", "h7", "a7"])
        rank_a, rank_b = rng.sample([1, 2, 3, 4, 5, 6], 2)
        file_a, file_b = rng.sample(files[:7], 2)
        white_king = rng.choice(["h1", "g1", "a1", "b1", "h2", "g2"])
        add(
            {
                black_king: "k",
                f"{file_a}{rank_a}": "R",
                f"{file_b}{rank_b}": "R",
                white_king: "K",
            },
            "w",
            "",
        )

    # 4. Knight plus rook in front of a cornered king.
    for _ in range(100):
        black_king = rng.choice(["h8", "a8"])
        knight = rng.choice(["f6", "e7", "g6", "c6", "b7", "e6"])
        rook_file = rng.choice([f for f in files if f != "a"])
        add(
            {black_king: "k", knight: "N", f"{rook_file}{rng.randint(1, 6)}": "R", "g1": "K"},
            "w",
            "",
        )

    # 5. Smothered mates: king in the corner behind a rook and pawns.
    for _ in range(100):
        knight = rng.choice(["e5", "g5", "d6", "f5"])
        add(
            {"h8": "k", "g8": "r", "g7": "p", "h7": "p", knight: "N", "g1": "K"},
            "w",
            "",
        )

    # 6. Knight forks of a king and a loose piece (material puzzles).
    for _ in range(160):
        black_king = rng.choice(["e8", "d8", "e7", "g8", "c8"])
        loose_file = rng.choice(["a", "b", "h", "g"])
        loose = rng.choice(["r", "q", "b"])
        knight_from = rng.choice(["c3", "d4", "e4", "f3", "c4", "e5", "d5", "f5"])
        add(
            {
                black_king: "k",
                f"{loose_file}8": loose,
                knight_from: "N",
                "e1": "K",
                "a2": "P", "h2": "P",
            },
            "w",
            "",
            preferred="material",
        )

    # 7. Hanging pieces defended by a fork or a skewer.
    for _ in range(140):
        queen_file = rng.choice(files)
        add(
            {
                "g8": "k", "g7": "p", "h7": "p", "f7": "p",
                f"{rng.choice(files)}{rng.randint(4, 6)}": "r",
                f"{queen_file}{rng.randint(2, 4)}": "Q",
                "g1": "K", "f2": "P", "g2": "P", "h2": "P",
            },
            "w",
            "",
            preferred="material",
        )

    # 8. Ladder mate in two: both rooks still on the ground floor, so the first
    #    move has to climb to the seventh rank and the second delivers mate.
    for _ in range(200):
        black_king = rng.choice(["h8", "a8", "h1", "a1"])
        rank_a, rank_b = rng.sample([1, 2, 3], 2)
        file_a, file_b = rng.sample(files[:6], 2)
        white_king = rng.choice(["f1", "g2", "e1", "c1", "b2", "d1"])
        add(
            {
                black_king: "k",
                f"{file_a}{rank_a}": "R",
                f"{file_b}{rank_b}": "R",
                white_king: "K",
            },
            "w",
            "",
        )

    # 9. Queen and king at distance: the queen needs one tempo to get in range.
    for _ in range(200):
        black_king = rng.choice(["h8", "a8", "h1", "a1", "e8", "d8"])
        white_king = rng.choice(["f6", "c6", "f3", "c3", "e6", "d6", "e3", "d3", "g6", "b6"])
        queen_file = rng.choice(files)
        queen_rank = rng.randint(1, 3)
        add(
            {black_king: "k", white_king: "K", f"{queen_file}{queen_rank}": "Q"},
            "w",
            "",
        )

    # 10. Black to move: the same shapes mirrored, so the set is not all White.
    for _ in range(120):
        white_king = rng.choice(["g1", "f1", "h1"])
        attacker_file = rng.choice(files)
        attacker_rank = rng.randint(5, 7)
        attacker = rng.choice(["r", "r", "q"])
        add(
            {
                white_king: "K",
                "f2": "P", "g2": "P", "h2": "P",
                f"{attacker_file}{attacker_rank}": attacker,
                rng.choice(["c8", "d8", "e8"]): "k",
                "a7": "p", "b7": "p",
            },
            "b",
            "",
        )

    return out


# ---------------------------------------------------------------------------
# Emission
# ---------------------------------------------------------------------------


def material_signature(fen: str) -> str:
    """
    The multiset of pieces in a position, e.g. "KQkpp".

    Used to stop the generator filling the set with the same idea over and over:
    two positions with an identical signature and the same theme are almost
    always the same puzzle with the pieces shifted a file across.
    """
    board = chess.Board(fen)
    return "".join(sorted(piece.symbol() for piece in board.piece_map().values()))


def assign_ids(puzzles: list[Puzzle]) -> None:
    """Stable, readable ids: cw-m1-001, cw-m2-004, cw-win-002."""
    counters: dict[str, int] = {}
    prefixes = {"mate_in_one": "m1", "mate_in_two": "m2", "win_material": "win"}
    for puzzle in puzzles:
        prefix = prefixes[puzzle.theme]
        counters[prefix] = counters.get(prefix, 0) + 1
        puzzle.id = f"cw-{prefix}-{counters[prefix]:03d}"


def puzzle_revision(puzzles: list[Puzzle]) -> int:
    """
    Content hash used as the Room seed revision.

    Adding or changing a puzzle bumps it, which is what makes the database
    re-seed on upgrade without a migration script. Python's str hash is
    salted per process, so hash the joined text instead of the tuples.
    """
    import hashlib

    blob = "\n".join(f"{p.theme}|{p.fen}|{','.join(p.solution)}" for p in puzzles)
    digest = int(hashlib.sha256(blob.encode("utf-8")).hexdigest(), 16)
    return (digest % 9000) + 1000


def kotlin_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\$")


THEME_ENUM = {
    "mate_in_one": "MATE_IN_ONE",
    "mate_in_two": "MATE_IN_TWO",
    "win_material": "WIN_MATERIAL",
}


def emit_kotlin(puzzles: list[Puzzle]) -> str:
    counts: dict[str, int] = {}
    for puzzle in puzzles:
        counts[puzzle.theme] = counts.get(puzzle.theme, 0) + 1
    summary = ", ".join(f"{counts.get(t, 0)} {t.replace('_', ' ')}" for t in
                        ("mate_in_one", "mate_in_two", "win_material"))

    lines = [
        "package com.mostafazahra.chesswake.puzzle.data",
        "",
        "import com.mostafazahra.chesswake.puzzle.domain.Puzzle",
        "import com.mostafazahra.chesswake.puzzle.domain.PuzzleGoal",
        "import com.mostafazahra.chesswake.puzzle.domain.PuzzleTheme",
        "",
        "// ---------------------------------------------------------------------------",
        "// GENERATED FILE — DO NOT EDIT BY HAND.",
        "//",
        "// Produced by tools/generate_puzzles.py. Every position was verified with",
        "// python-chess before being emitted here:",
        "//   * each mate-in-one really mates, and all mating moves are listed;",
        "//   * each mate-in-two is forced against every legal reply;",
        "//   * each win-material puzzle survives the opponent's best defence.",
        "//",
        "// Regenerate with:",
        "//     pip install python-chess && python3 tools/generate_puzzles.py",
        "// ---------------------------------------------------------------------------",
        "",
        "/**",
        f" * The {len(puzzles)} puzzles bundled with the app ({summary}).",
        " *",
        " * Seeded into Room on first launch and whenever [REVISION] changes, which is",
        " * what makes ChessWake work with no network access at all.",
        " */",
        "object BundledPuzzles {",
        "",
        "    /** Content hash of the set; bumping it triggers an automatic re-seed. */",
        f"    const val REVISION: Int = {puzzle_revision(puzzles)}",
        "",
        "    val ALL: List<Puzzle> = listOf(",
    ]

    for puzzle in puzzles:
        alternatives = ", ".join(f'"{uci}"' for uci in puzzle.alternatives)
        solution = ", ".join(f'"{uci}"' for uci in puzzle.solution)
        lines += [
            "        Puzzle(",
            f'            id = "{puzzle.id}",',
            f'            name = "{kotlin_escape(puzzle.name)}",',
            f"            theme = PuzzleTheme.{THEME_ENUM[puzzle.theme]},",
            f"            goal = PuzzleGoal.{puzzle.goal},",
            f"            difficulty = {puzzle.difficulty},",
            f'            fen = "{puzzle.fen}",',
            f"            solution = listOf({solution}),",
            f"            alternativeSolutions = setOf({alternatives}),",
            f"            matesIn = {puzzle.mates_in},",
            f"            materialGain = {puzzle.material_gain},",
            f'            hint = "{kotlin_escape(puzzle.hint)}",',
            f'            description = "{kotlin_escape(puzzle.description)}",',
            f'            source = "{puzzle.source}",',
            "        ),",
        ]

    lines += [
        "    )",
        "",
        "    /** Puzzles of one theme, for the practice screen's filter chips. */",
        "    fun byTheme(theme: PuzzleTheme): List<Puzzle> = ALL.filter { it.theme == theme }",
        "",
        "    /** Puzzles at or below [maxDifficulty] — keeps the 6am ones gentle. */",
        "    fun upToDifficulty(maxDifficulty: Int): List<Puzzle> = ALL.filter { it.difficulty <= maxDifficulty }",
        "",
        "    /** A single puzzle by id, or null when the id is unknown. */",
        "    fun byId(id: String): Puzzle? = ALL.firstOrNull { it.id == id }",
        "}",
        "",
    ]
    return "\n".join(lines)


def emit_json(puzzles: list[Puzzle]) -> str:
    return json.dumps(
        {"revision": puzzle_revision(puzzles), "puzzles": [dataclasses.asdict(p) for p in puzzles]},
        indent=2,
        sort_keys=True,
    )


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


def build_puzzle_set(rng: random.Random, max_generated: int) -> tuple[list[Puzzle], list[Candidate]]:
    puzzles: list[Puzzle] = []
    seen_keys: set[tuple] = set()
    seen_fens: set[str] = set()
    signature_counts: dict[tuple, int] = {}
    rejected: list[Candidate] = []

    pool = list(CURATED) + template_candidates(rng)
    rng.shuffle(pool[len(CURATED):])  # curated first, generated in random order

    for candidate in pool:
        puzzle = solve_candidate(candidate)
        if puzzle is None:
            rejected.append(candidate)
            continue
        key = (puzzle.theme, puzzle.fen, tuple(puzzle.solution))
        if key in seen_keys or puzzle.fen in seen_fens:
            continue
        signature = (puzzle.theme, material_signature(puzzle.fen))
        if signature_counts.get(signature, 0) >= MAX_PER_SIGNATURE:
            continue
        signature_counts[signature] = signature_counts.get(signature, 0) + 1
        seen_keys.add(key)
        seen_fens.add(puzzle.fen)
        puzzles.append(puzzle)

    # Curated classics first, so the per-theme caps favour named patterns.
    puzzles.sort(key=lambda p: (p.source != "curated", p.difficulty, p.theme, p.name))

    # Cap each theme so the set stays balanced: an alarm app mostly needs fast
    # mate-in-ones, but a handful of two-movers and tactics keeps it interesting.
    caps = {"mate_in_one": 40, "mate_in_two": 14, "win_material": 16}
    capped: list[Puzzle] = []
    seen_per_theme: dict[str, int] = {}
    generated_per_theme: dict[str, int] = {}
    for puzzle in puzzles:
        used = seen_per_theme.get(puzzle.theme, 0)
        if used >= caps[puzzle.theme]:
            continue
        if puzzle.source != "curated":
            # Curated classics are never crowded out by generated positions.
            if generated_per_theme.get(puzzle.theme, 0) >= max_generated:
                continue
            generated_per_theme[puzzle.theme] = generated_per_theme.get(puzzle.theme, 0) + 1
        seen_per_theme[puzzle.theme] = used + 1
        capped.append(puzzle)
    puzzles = capped

    # Curated classics first, then by difficulty, so "give me something easy"
    # picks from the front of the list.
    puzzles.sort(key=lambda p: (p.source != "curated", p.difficulty, p.theme, p.name))
    assign_ids(puzzles)
    return puzzles, rejected


def report(puzzles: list[Puzzle]) -> None:
    for puzzle in puzzles:
        print(
            f"{puzzle.id}  d{puzzle.difficulty}  {puzzle.theme:12s} "
            f"{puzzle.fen}  sol={'+'.join(puzzle.solution)}  alt={len(puzzle.alternatives)}",
            file=sys.stderr,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate and verify the ChessWake puzzle set.")
    parser.add_argument("--check", action="store_true", help="verify only, do not write files")
    parser.add_argument("--report", action="store_true", help="print every verified puzzle")
    parser.add_argument("--generated", type=int, default=16, help="max generated puzzles per theme")
    parser.add_argument("--seed", type=int, default=20260904, help="RNG seed for generated positions")
    args = parser.parse_args()

    puzzles, rejected = build_puzzle_set(random.Random(args.seed), args.generated)
    if not puzzles:
        print("no puzzles verified — refusing to write an empty set", file=sys.stderr)
        return 1

    counts: dict[str, int] = {}
    for puzzle in puzzles:
        counts[puzzle.theme] = counts.get(puzzle.theme, 0) + 1
    print(f"verified {len(puzzles)} puzzles, rejected {len(rejected)} candidates", file=sys.stderr)
    for theme in ("mate_in_one", "mate_in_two", "win_material"):
        print(f"  {theme:14s} {counts.get(theme, 0)}", file=sys.stderr)

    if args.report:
        report(puzzles)

    if args.check:
        return 0

    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text(emit_kotlin(puzzles), encoding="utf-8")
    JSON_OUT.parent.mkdir(parents=True, exist_ok=True)
    JSON_OUT.write_text(emit_json(puzzles) + "\n", encoding="utf-8")
    print(f"wrote {KOTLIN_OUT.relative_to(REPO_ROOT)}", file=sys.stderr)
    print(f"wrote {JSON_OUT.relative_to(REPO_ROOT)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
