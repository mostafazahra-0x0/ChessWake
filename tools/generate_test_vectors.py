#!/usr/bin/env python3
"""
Generates golden test vectors for the ChessWake Kotlin engine.

The Kotlin engine cannot be cross-checked against itself, so its behaviour is
pinned down by vectors produced with python-chess — a mature, heavily tested
library. `app/src/test/java/.../chess/*VectorTest.kt` loads these files and
asserts the Kotlin implementation agrees move-for-move, notation-for-notation
and node-for-node.

Outputs (all under app/src/test/resources/chess/):

  legal_moves.json    every legal move (UCI + SAN) and the resulting status for a
                      spread of positions, including the awkward ones: en-passant
                      pins, castling through check, underpromotion, and the
                      positions where a naive generator produces illegal moves.
  perft.json          node counts for the six standard perft positions. Perft is
                      the classic exhaustive move-generator test: a single wrong
                      rule shows up as a wrong node count.
  fen_roundtrip.json  FEN in, FEN out — catches serialisation drift.

Usage:
    pip install python-chess && python3 tools/generate_test_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

import chess

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "app/src/test/resources/chess"

# ---------------------------------------------------------------------------
# Positions
# ---------------------------------------------------------------------------

# Named positions chosen because each one breaks a different naive generator.
POSITIONS: dict[str, str] = {
    "start": chess.STARTING_FEN,
    # The standard perft stress position: every awkward rule at once.
    "kiwipete": "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "kiwipete_black": "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b KQkq - 0 1",
    # Pinned pawns, en passant, rook lifts.
    "position3": "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    # Promotions and a blocked centre.
    "position4": "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
    # A promotion captured mid-move; white has just promoted on d8.
    "position5": "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
    # Symmetric middlegame with both sides able to castle.
    "position6": "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
    # En passant available, and the classic "ep capture is illegal because it
    # exposes the king to a rook along the rank" case.
    "ep_available": "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    "ep_pin_horizontal": "8/8/8/8/k2Pp2Q/8/8/3K4 b - d3 0 1",
    # Castling: through check, out of check, with a blocker, and rights lost.
    "castle_both_available": "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
    "castle_blocked": "r3k2r/8/8/8/8/8/8/R2QK2R w KQkq - 0 1",
    "castle_through_check_kingside": "r3k2r/8/8/8/2b5/8/8/R3K2R w KQkq - 0 1",
    "castle_through_check_queenside": "r3k2r/8/8/8/6b1/8/8/R3K2R w KQkq - 0 1",
    "castle_in_check": "r3k2r/8/8/8/4q3/8/8/R3K2R w KQkq - 0 1",
    "castle_rights_lost": "r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1",
    # Promotion and underpromotion; the side to move must have all four choices.
    "promotion_white": "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
    "promotion_capture": "1n1rk3/P7/8/8/8/8/8/4K3 w - - 0 1",
    "promotion_black": "4k3/8/8/8/8/8/p7/4K3 b - - 0 1",
    # Checkmate, stalemate and insufficient material terminals.
    "checkmate_fools_mate": "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3",
    "stalemate": "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",
    "insufficient_kvkb": "8/8/8/4k3/8/8/2B1K3/8 w - - 0 1",
    # Pinned pieces may not move; the pinned knight is the only defender.
    "absolute_pin": "4k3/4q3/8/8/8/8/8/4R1K1 b - - 0 1",
    "knight_check": "4k3/8/8/8/8/3n4/8/4K3 w - - 0 1",
    "fork_check": "4k3/8/8/8/8/8/2n5/R3K3 w - - 0 1",
    # Lone king versus king and queen: lots of moves, none illegal.
    "kq_vs_k": "8/8/8/3k4/8/8/1Q6/3K4 b - - 0 1",
    # Two knights cannot force mate, but the generator must still be correct.
    "knn_vs_k": "8/8/8/3k4/8/8/1NN5/3K4 b - - 0 1",
}

# The six standard perft positions (see the Chess Programming Wiki) with the
# depths whose node counts are cheap enough to keep CI quick.
PERFT_POSITIONS: dict[str, tuple[str, int]] = {
    "start": (chess.STARTING_FEN, 4),
    "kiwipete": ("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 3),
    "position3": ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 4),
    "position4": ("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3),
    "position5": ("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3),
    "position6": ("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 3),
}


def perft(board: chess.Board, depth: int) -> int:
    if depth == 0:
        return 1
    if depth == 1:
        return board.legal_moves.count()
    nodes = 0
    for move in board.legal_moves:
        board.push(move)
        nodes += perft(board, depth - 1)
        board.pop()
    return nodes


def status_of(board: chess.Board) -> str:
    """Status vocabulary mirrors the Kotlin GameStatus enum."""
    if board.is_checkmate():
        return "CHECKMATE"
    if board.is_stalemate():
        return "STALEMATE"
    if board.is_insufficient_material():
        return "INSUFFICIENT_MATERIAL"
    # The Kotlin engine treats the 50-move rule (halfmove clock >= 100) as
    # terminal, so the vectors use the same threshold.
    if board.halfmove_clock >= 100:
        return "FIFTY_MOVE_RULE"
    return "CHECK" if board.is_check() else "ONGOING"


def build_legal_moves() -> dict:
    entries = {}
    for name, fen in POSITIONS.items():
        board = chess.Board(fen)
        moves = []
        for move in sorted(board.legal_moves, key=lambda m: m.uci()):
            moves.append({
                "uci": move.uci(),
                "san": board.san(move),
                "isCapture": board.is_capture(move),
                "isCastling": board.is_castling(move),
                "isEnPassant": board.is_en_passant(move),
                "isPromotion": move.promotion is not None,
                "givesCheck": board.gives_check(move),
            })
        entries[name] = {
            "fen": fen,
            "sideToMove": "WHITE" if board.turn == chess.WHITE else "BLACK",
            "isInCheck": board.is_check(),
            "status": status_of(board),
            "legalMoveCount": len(moves),
            "moves": moves,
        }
    return entries


def build_perft() -> dict:
    result = {}
    for name, (fen, max_depth) in PERFT_POSITIONS.items():
        board = chess.Board(fen)
        counts = {}
        for depth in range(1, max_depth + 1):
            counts[str(depth)] = perft(board, depth)
        result[name] = {"fen": fen, "depths": counts}
        print(f"  perft {name}: {counts}")
    return result


def build_fen_roundtrip() -> dict:
    """Every named position plus each bundled puzzle, FEN in -> FEN out."""
    entries = {}
    for name, fen in POSITIONS.items():
        entries[name] = {"input": fen, "expected": chess.Board(fen).fen()}

    bundled = REPO_ROOT / "app/src/test/resources/puzzles/bundled_puzzles.json"
    if bundled.exists():
        data = json.loads(bundled.read_text(encoding="utf-8"))
        for puzzle in data["puzzles"]:
            entries[puzzle["id"]] = {
                "input": puzzle["fen"],
                "expected": chess.Board(puzzle["fen"]).fen(),
            }
    return entries


def build_puzzle_checks() -> dict:
    """
    Per-puzzle expectations, so the Kotlin tests can assert not just "the engine
    agrees with python-chess" but "every shipped puzzle is actually solvable".
    """
    bundled = REPO_ROOT / "app/src/test/resources/puzzles/bundled_puzzles.json"
    if not bundled.exists():
        return {}

    data = json.loads(bundled.read_text(encoding="utf-8"))
    entries = {}
    for puzzle in data["puzzles"]:
        board = chess.Board(puzzle["fen"])
        line = []
        for uci in puzzle["solution"]:
            move = chess.Move.from_uci(uci)
            assert move in board.legal_moves, f"{puzzle['id']}: illegal {uci} in {board.fen()}"
            line.append({"uci": uci, "san": board.san(move)})
            board.push(move)

        entries[puzzle["id"]] = {
            "fen": puzzle["fen"],
            "goal": puzzle["goal"],
            "matesIn": puzzle["mates_in"],
            "solution": line,
            "alternatives": puzzle["alternatives"],
            "statusAfterSolution": status_of(board),
            "sideToMove": "WHITE" if chess.Board(puzzle["fen"]).turn == chess.WHITE else "BLACK",
        }
    return entries


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    legal = build_legal_moves()
    print("generating perft vectors (this is the slow part)...")
    perfts = build_perft()
    roundtrip = build_fen_roundtrip()
    puzzle_checks = build_puzzle_checks()

    outputs = {
        "legal_moves.json": {
            "_comment": "Golden legal-move vectors generated by tools/generate_test_vectors.py with python-chess.",
            "positions": legal,
        },
        "perft.json": {
            "_comment": "Standard perft node counts. Generated by tools/generate_test_vectors.py with python-chess.",
            "positions": perfts,
        },
        "fen_roundtrip.json": {
            "_comment": "FEN parse/serialise round-trip. Generated by tools/generate_test_vectors.py with python-chess.",
            "positions": roundtrip,
        },
        "puzzle_checks.json": {
            "_comment": "Per-puzzle expectations. Generated by tools/generate_test_vectors.py with python-chess.",
            "puzzles": puzzle_checks,
        },
    }

    for filename, payload in outputs.items():
        path = OUT_DIR / filename
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"wrote {path.relative_to(REPO_ROOT)}")

    print(f"{len(legal)} positions, {sum(len(v['moves']) for v in legal.values())} legal moves recorded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
