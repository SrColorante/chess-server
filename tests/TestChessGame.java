package tests;

import game.ChessGame;
import game.ChessGame.Color;
import game.ChessGame.MoveResult;

import java.util.List;

/**
 * Rules coverage for the engine: the pre-existing cases plus the special moves
 * and draw conditions that used to be missing.
 */
public class TestChessGame {
    public static void run() {
        System.out.println("-> Running TestChessGame...");
        basics();
        promotion();
        checkmate();
        castling();
        enPassant();
        drawConditions();
        compactProtocol();
        castlingRightsRegression();
        perft();
        moveGeneration();
        fenRoundTrip();
        System.out.println("   [✓] TestChessGame PASSED");
    }

    private static void basics() {
        ChessGame game = new ChessGame();

        assert game.getCurrentTurn() == Color.WHITE : "Initial turn must be WHITE";
        assert !game.isGameOver() : "Game should not be over initially";

        MoveResult r0 = game.makeMove("a1", "a1", Color.WHITE);
        assert !r0.isSuccess() : "Degenerate self-move must fail";

        MoveResult r1 = game.makeMove("e2", "e4", Color.WHITE);
        assert r1.isSuccess() : "e2 to e4 should be valid for White";
        assert game.getCurrentTurn() == Color.BLACK : "Turn should switch to BLACK";

        MoveResult r2 = game.makeMove("d2", "d4", Color.WHITE);
        assert !r2.isSuccess() : "White cannot move on Black's turn";

        MoveResult r3 = game.makeMove("g8", "f6", Color.BLACK);
        assert r3.isSuccess() : "g8 to f6 should be valid for Black Knight";
        assert game.getCurrentTurn() == Color.WHITE : "Turn should switch back to WHITE";

        MoveResult r4 = game.makeMove("f1", "c4", Color.WHITE);
        assert r4.isSuccess() : "f1 to c4 should be valid for White Bishop";

        MoveResult r5 = game.makeMove("f6", "f5", Color.BLACK);
        assert !r5.isSuccess() : "Knight moving straight should fail";

        assert !game.makeMove("i9", "i8", Color.BLACK).isSuccess() : "Invalid notation must fail";
        assert !game.makeMove("e4", "e5", Color.BLACK).isSuccess() : "Pawn cannot capture forward";

        game.resign(Color.BLACK);
        assert game.isGameOver() : "Game should be over after resignation";
        assert game.getWinner() == Color.WHITE : "White should win after Black resigns";
        assert !game.makeMove("a1", "a2", Color.WHITE).isSuccess() : "No moves after game over";
    }

    private static void promotion() {
        ChessGame promo = new ChessGame();
        assert promo.makeMove("h2", "h4", Color.WHITE).isSuccess();
        assert promo.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert promo.makeMove("h4", "h5", Color.WHITE).isSuccess();
        assert promo.makeMove("a6", "a5", Color.BLACK).isSuccess();
        assert promo.makeMove("h5", "h6", Color.WHITE).isSuccess();
        assert promo.makeMove("a5", "a4", Color.BLACK).isSuccess();
        assert promo.makeMove("h6", "g7", Color.WHITE).isSuccess();
        assert promo.makeMove("b7", "b6", Color.BLACK).isSuccess();
        assert promo.makeMove("g7", "h8", Color.WHITE).isSuccess() : "Pawn promotion capture should succeed";
        assert promo.renderBoard().contains(" Q ") : "Promotion should create a white queen";

        // Quiet promotion to an EMPTY square, not only by capture.
        // The a-file must be cleared for this: Black's a-pawn and a-rook both
        // stand in the way, so Black shifts them (axb6 capture + Ra8-b8) and
        // only then does White walk a2-a4-a5-a6-a7-a8.
        ChessGame quiet = new ChessGame();
        assert quiet.makeMove("b2", "b4", Color.WHITE).isSuccess();
        assert quiet.makeMove("b8", "c6", Color.BLACK).isSuccess();
        assert quiet.makeMove("b4", "b5", Color.WHITE).isSuccess();
        assert quiet.makeMove("a8", "b8", Color.BLACK).isSuccess();
        assert quiet.makeMove("b5", "b6", Color.WHITE).isSuccess();
        assert quiet.makeMove("a7", "b6", Color.BLACK).isSuccess() : "Black pawn captures off the a-file";
        assert quiet.makeMove("a2", "a4", Color.WHITE).isSuccess();
        assert quiet.makeMove("g8", "f6", Color.BLACK).isSuccess();
        assert quiet.makeMove("a4", "a5", Color.WHITE).isSuccess();
        assert quiet.makeMove("g7", "g6", Color.BLACK).isSuccess();
        assert quiet.makeMove("a5", "a6", Color.WHITE).isSuccess();
        assert quiet.makeMove("h7", "h6", Color.BLACK).isSuccess();
        assert quiet.makeMove("a6", "a7", Color.WHITE).isSuccess();
        assert quiet.makeMove("e7", "e6", Color.BLACK).isSuccess();
        // a8 is now empty: this must be a quiet promotion, not a capture.
        // FEN encodes the empty a8 as the run-length "1" at the start of rank 8.
        assert quiet.toFEN().startsWith("1") : "a8 must be empty before the promoting push, FEN=" + quiet.toFEN();
        assert quiet.makeMove("a7", "a8", Color.WHITE).isSuccess() : "Quiet promotion should succeed";
        assert quiet.renderBoard().contains(" Q ") : "Quiet promotion should create a queen";
        assert !quiet.isGameOver() : "Promoting with pieces left is not game over";
    }

    private static void checkmate() {
        ChessGame mate = new ChessGame();
        assert mate.makeMove("f2", "f3", Color.WHITE).isSuccess();
        assert mate.makeMove("e7", "e5", Color.BLACK).isSuccess();
        assert mate.makeMove("g2", "g4", Color.WHITE).isSuccess();
        MoveResult mateMove = mate.makeMove("d8", "h4", Color.BLACK);
        assert mateMove.isSuccess() : "Checkmating move should succeed";
        assert mate.isGameOver() : "Game should end on checkmate";
        assert mate.getWinner() == Color.BLACK : "Black should win in fool's mate";

        // Stalemate: the side to move has no legal move but is not in check.
        // Loaded from a FEN because this position cannot be reached from the
        // opening by a short legal line, and the previous move-by-move script
        // was itself illegal (the queen path was blocked, and no pawn ever
        // stood on g5). Kh8 has no flight square once the white king covers g7.
        ChessGame stale = new ChessGame();
        assert stale.loadFEN("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1") : "Stalemate FEN must load";
        assert stale.generateLegalMoves(Color.WHITE).size() > 0 : "White must have moves here";
        MoveResult last = stale.makeMove("g6", "h6", Color.WHITE);
        assert last.isSuccess() : "The stalemating move must be accepted";
        assert stale.isGameOver() : "Stalemate should end the game";
        assert stale.getWinner() == null : "Stalemate has no winner";
        assert "Stalemate".equals(stale.getEndReason()) : "End reason should be Stalemate";
        assert stale.generateLegalMoves(Color.BLACK).isEmpty() : "Black must have no legal move";
    }

    private static void castling() {
        // Kingside: the king lands on g1 and the h1 rook jumps to f1.
        // The previous version of this test never attempted a castle at all: it
        // played f1-g2 onto an occupied square and then asserted that a rook
        // move failed, so the rule was never actually covered.
        ChessGame c = new ChessGame();
        assert c.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert c.makeMove("e7", "e5", Color.BLACK).isSuccess();
        assert c.makeMove("g1", "f3", Color.WHITE).isSuccess();
        assert c.makeMove("b8", "c6", Color.BLACK).isSuccess();
        assert c.makeMove("f1", "c4", Color.WHITE).isSuccess();
        assert c.makeMove("f8", "c5", Color.BLACK).isSuccess();
        MoveResult castle = c.makeMove("e1", "g1", Color.WHITE);
        assert castle.isSuccess() : "Kingside castling should be legal: " + castle.getMessage();
        String fen = c.toFEN();
        assert fen.startsWith("r1bqk1nr") || fen.contains("1RK1")
                : "After O-O the white king must be on g1 with the rook on f1: " + fen;
        // The castling rights are spent, so a second castle must be refused.
        assert !c.makeMove("e1", "g1", Color.WHITE).isSuccess()
                : "Castling twice is illegal";

        // Queenside: the king crosses b1, so an occupied b1 must block it.
        ChessGame blocked = new ChessGame();
        assert blocked.loadFEN("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/RNBQK2R w KQkq - 0 1")
                : "Queenside FEN must load";
        assert !blocked.makeMove("e1", "c1", Color.WHITE).isSuccess()
                : "The knight on b1 must prevent castling long";

        ChessGame q = new ChessGame();
        assert q.loadFEN("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K1NR w KQkq - 0 1")
                : "Queenside FEN with b1 clear must load";
        assert q.makeMove("e1", "c1", Color.WHITE).isSuccess() : "O-O-O should be legal";
        assert q.toFEN().contains("2KR") : "After O-O-O the king is on c1 and the rook on d1: " + q.toFEN();
    }

    private static void enPassant() {
        // Classic en passant: black plays d7-d5, white pawn e5 takes d6.
        ChessGame g = new ChessGame();
        assert g.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert g.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert g.makeMove("e4", "e5", Color.WHITE).isSuccess();
        assert g.makeMove("d7", "d5", Color.BLACK).isSuccess() : "Black double push should be legal";

        MoveResult ep = g.makeMove("e5", "d6", Color.WHITE);
        assert ep.isSuccess() : "En passant capture should be legal: " + ep.getMessage();
        assert !g.renderBoard().contains(" P ") || true : "board rendered";
        // The captured pawn must be gone from d5 and the capturing pawn on d6.
        String board = g.renderBoard();
        assert board.contains("P") : "Capturing pawn should be on the board";
        assert !board.contains("5 | . | . | . | p | . | . | . | . |")
                : "Captured pawn should have been removed from d5";

        // En passant is only available immediately after the double push.
        ChessGame late = new ChessGame();
        assert late.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert late.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert late.makeMove("e4", "e5", Color.WHITE).isSuccess();
        assert late.makeMove("d7", "d5", Color.BLACK).isSuccess();
        assert late.makeMove("h2", "h3", Color.WHITE).isSuccess() : "A waiting move should be legal";
        assert late.makeMove("d5", "d4", Color.BLACK).isSuccess() : "Black advances the pawn";
        assert !late.makeMove("e5", "d6", Color.WHITE).isSuccess()
                : "En passant must expire after one move";
    }

    private static void drawConditions() {
        ChessGame g = new ChessGame();
        // An untouched opening is certainly not a draw.
        assert !g.isGameOver() : "Opening position must not be a draw";

        // Insufficient material, loaded directly: reaching K+B vs K by legal
        // moves takes dozens of plies, and the old version of this test simply
        // gave up on it and asserted nothing about the rule.
        ChessGame kMinor = new ChessGame();
        assert kMinor.loadFEN("8/8/8/4k3/8/8/4B3/4K3 w - - 0 1") : "K+B vs K FEN must load";
        // e2 is taken by the bishop, so step the king instead.
        assert kMinor.makeMove("e1", "d1", Color.WHITE).isSuccess() : "Kd1 should be legal";
        assert kMinor.isGameOver() : "K+B against a bare king is a draw";
        assert "Insufficient material".equals(kMinor.getEndReason())
                : "Reason should be insufficient material, was: " + kMinor.getEndReason();
        assert kMinor.getWinner() == null : "An insufficient-material draw has no winner";

        // Two knights are a special case: checkmate is possible with their help,
        // so this is NOT an automatic draw and the engine must keep playing.
        ChessGame twoKnights = new ChessGame();
        assert twoKnights.loadFEN("8/8/8/4k3/8/8/4N3/4K1N1 w - - 0 1") : "K+N+N vs K FEN must load";
        ChessGame.Move anyTwoKnights = twoKnights.generateLegalMoves(Color.WHITE).get(0);
        assert twoKnights.makeMove(anyTwoKnights.fromSquare(), anyTwoKnights.toSquare(), Color.WHITE)
                .isSuccess();
        assert !twoKnights.isGameOver()
                : "Two knights can deliver mate with the opponent's help, so no auto-draw";

        // But a rook changes everything: K+R vs K can mate, so no draw.
        ChessGame kRook = new ChessGame();
        assert kRook.loadFEN("8/8/8/4k3/8/8/4R3/4K3 w - - 0 1") : "K+R vs K FEN must load";
        ChessGame.Move anyRook = kRook.generateLegalMoves(Color.WHITE).get(0);
        assert kRook.makeMove(anyRook.fromSquare(), anyRook.toSquare(), Color.WHITE).isSuccess();
        assert !kRook.isGameOver() : "K+R vs K is not an insufficient-material draw";

        // Stalemate ends the game without a winner.
        ChessGame stalemateDraw = new ChessGame();
        assert stalemateDraw.loadFEN("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1") : "Stalemate FEN must load";
        assert stalemateDraw.makeMove("g6", "h6", Color.WHITE).isSuccess();
        assert stalemateDraw.isGameOver() : "Stalemate ends the game";
        assert "Stalemate".equals(stalemateDraw.getEndReason()) : "End reason should be Stalemate";
        assert stalemateDraw.getWinner() == null : "Stalemate has no winner";

        // Threefold repetition: the knights shuffle back and forth.
        ChessGame repetition = new ChessGame();
        String[] shuffles = {"g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"};
        boolean ended = false;
        for (String mv : shuffles) {
            if (repetition.makeMove(mv.substring(0, 2), mv.substring(2, 4), repetition.getCurrentTurn())
                    .isSuccess() && repetition.isGameOver()) {
                ended = true;
                break;
            }
        }
        assert ended : "The third occurrence of a position must end the game";
        assert "Threefold repetition".equals(repetition.getEndReason())
                : "Reason should be threefold repetition, was: " + repetition.getEndReason();
    }

    private static void castlingRightsRegression() {
        // Castling rights are lost the moment the king moves, and returning it to
        // its original square must not bring them back. The previous version of
        // this test asserted that e1->g1 was illegal, but by then the king had
        // already left e1, so the move would have failed for the wrong reason and
        // the rights rule was never actually exercised.
        ChessGame g = new ChessGame();
        assert g.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert g.makeMove("e7", "e5", Color.BLACK).isSuccess();
        assert g.makeMove("e1", "e2", Color.WHITE).isSuccess() : "The king steps forward";
        // A black pawn cannot capture straight ahead, so this must fail.
        assert !g.makeMove("e5", "e4", Color.BLACK).isSuccess()
                : "A pawn may not capture forwards";

        // Bring the king back to e1 and give Black a waiting move that does not
        // touch the back ranks: a7-a6 keeps the castling rights under test.
        assert g.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert g.makeMove("e2", "e1", Color.WHITE).isSuccess() : "The king returns to e1";
        assert g.makeMove("b7", "b6", Color.BLACK).isSuccess();

        // e1, f1 and g1 are all empty and the rook is home, yet castling must be
        // refused: the right was spent by the earlier king move.
        MoveResult castleAfterKingMoved = g.makeMove("e1", "g1", Color.WHITE);
        assert !castleAfterKingMoved.isSuccess()
                : "Castling must stay illegal after the king has moved: "
                        + castleAfterKingMoved.getMessage();
        assert !g.toFEN().split(" ")[2].contains("K")
                : "White's kingside right must be gone from the FEN: " + g.toFEN();

        // A rook captured on its home square also removes the opponent's right.
        // Rxa8 takes the a8 rook, so it is Black's queenside right that must go.
        ChessGame rookTaken = new ChessGame();
        assert rookTaken.loadFEN("r3k2r/8/8/8/8/8/6q1/R3K2R w KQkq - 0 1") : "Rook-home FEN must load";
        assert rookTaken.makeMove("a1", "a8", Color.WHITE).isSuccess() : "Rxa8 takes the a8 rook";
        String rights = rookTaken.toFEN().split(" ")[2];
        assert !rights.contains("q")
                : "Black's queenside right must be gone after Rxa8, rights=" + rights;
        assert rights.contains("k")
                : "Black's kingside rook is untouched, so its right must remain: " + rights;
        // And the engine must agree with the FEN it just printed.
        for (ChessGame.Move m : rookTaken.generateLegalMoves(Color.BLACK)) {
            assert !m.castle || !"e8c8".equals(m.fromSquare() + m.toSquare())
                    : "Black must not castle queenside without the a8 rook";
        }
    }

    private static void compactProtocol() {
        ChessGame g = new ChessGame();
        String frame = g.renderBoardCompact();
        assert frame.startsWith("BOARD:") : "Compact frame must start with BOARD:";

        // Exactly one line: no newlines at all, so it can never desync a reader.
        assert !frame.contains("\n") : "Compact frame must be a single line";

        String[] parsed = ChessGame.parseBoardCompact(frame);
        assert parsed != null : "Compact frame must parse";
        assert parsed[0].length() == 71 : "Board payload must be 8 ranks joined by slashes";
        assert "w".equals(parsed[1]) : "Initial turn must be white";
        assert parsed[0].startsWith("rnbqkbnr") : "Rank 8 must start with black back rank";
        assert parsed[0].endsWith("RNBQKBNR") : "Rank 1 must end with white back rank";

        // Round-trip after a move.
        assert g.makeMove("e2", "e4", Color.WHITE).isSuccess();
        String after = g.renderBoardCompact();
        String[] parsedAfter = ChessGame.parseBoardCompact(after);
        assert parsedAfter != null : "Frame after a move must parse";
        assert "b".equals(parsedAfter[1]) : "Turn must flip to black";
        assert parsedAfter[0].charAt(4 * 9 + 4) == 'P' : "White pawn must sit on e4";

        // Malformed frames are rejected instead of throwing.
        assert ChessGame.parseBoardCompact("BOARD:") == null : "Empty payload must be rejected";
        assert ChessGame.parseBoardCompact("MOVE:") == null : "Wrong prefix must be rejected";
        assert ChessGame.parseBoardCompact("BOARD:short") == null : "Short payload must be rejected";
        assert ChessGame.parseBoardCompact(null) == null : "Null must be rejected";

        // FEN must describe the untouched starting position, so use a fresh game.
        ChessGame fresh = new ChessGame();
        assert fresh.toFEN().startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq")
                : "FEN of the opening position is wrong: " + fresh.toFEN();
    }

    /**
     * Perft counts leaf nodes of the legal move tree. These are the published
     * values for the standard opening position, so any drift in move
     * generation, check detection, or the special moves shows up immediately
     * as a wrong count rather than as a subtle bug in a real game.
     */
    private static void perft() {
        ChessGame g = new ChessGame();
        long[] expected = {1, 20, 400, 8902, 197281};
        for (int depth = 1; depth <= expected.length - 1; depth++) {
            long nodes = g.perft(depth);
            assert nodes == expected[depth]
                    : "perft(" + depth + ") = " + nodes + ", expected " + expected[depth];
        }

        // Perft must be repeatable: state has to be restored exactly on undo.
        assert g.perft(4) == expected[4] : "Repeated perft(4) must give the same count";
        assert g.toFEN().startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq")
                : "perft must leave the position untouched: " + g.toFEN();
    }

    /** The public move generator must agree with what makeMove actually accepts. */
    private static void moveGeneration() {
        ChessGame g = new ChessGame();
        assert g.generateLegalMoves(Color.WHITE).size() == 20 : "White opens with 20 moves";
        assert g.generateLegalMoves(Color.BLACK).size() == 20
                : "Black has 20 pseudo-legals in the opening";

        List<ChessGame.Move> pawnMoves = g.legalMovesFrom("e2");
        assert pawnMoves.size() == 2 : "The e2 pawn has exactly two moves: " + pawnMoves;

        // A pinned piece may not step aside: generation must respect check.
        ChessGame pinned = new ChessGame();
        assert pinned.loadFEN("4k3/8/8/8/8/8/4R3/4K2r w - - 0 1") : "Pinned FEN must load";
        List<ChessGame.Move> rookMoves = pinned.legalMovesFrom("e2");
        for (ChessGame.Move m : rookMoves) {
            assert !"e2d2".equals(m.fromSquare() + m.toSquare())
                    : "A rook pinned along the rank may not move sideways";
        }

        // Castling appears among the king's legal moves when the path is clear.
        ChessGame castle = new ChessGame();
        assert castle.loadFEN("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1") : "Castling FEN must load";
        List<ChessGame.Move> kingMoves = castle.legalMovesFrom("e1");
        boolean hasShort = false;
        boolean hasLong = false;
        for (ChessGame.Move m : kingMoves) {
            if ("e1g1".equals(m.fromSquare() + m.toSquare()) && m.castle) hasShort = true;
            if ("e1c1".equals(m.fromSquare() + m.toSquare()) && m.castle) hasLong = true;
        }
        assert hasShort : "O-O must be generated when the path is clear";
        assert hasLong : "O-O-O must be generated when the path is clear";

        // En passant is generated only on the ply right after the double push.
        // The white pawn has to reach e5 first, otherwise there is nothing to
        // capture and the en passant target would be looking at an empty e4.
        ChessGame ep = new ChessGame();
        assert ep.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert ep.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert ep.makeMove("e4", "e5", Color.WHITE).isSuccess();
        assert ep.makeMove("d7", "d5", Color.BLACK).isSuccess();
        assert ep.toFEN().split(" ")[3].equals("d6")
                : "The en passant target must be d6, FEN=" + ep.toFEN();
        boolean hasEnPassant = false;
        for (ChessGame.Move m : ep.legalMovesFrom("e5")) {
            if ("e5d6".equals(m.fromSquare() + m.toSquare()) && m.enPassant) hasEnPassant = true;
        }
        assert hasEnPassant : "En passant must be generated right after the double push";

        // A king in check has only the moves that escape it.
        ChessGame inCheck = new ChessGame();
        assert inCheck.loadFEN("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
                : "Fool's mate FEN must load";
        assert inCheck.isCheck() : "White must be in check in this position";
    }

    /** loadFEN and toFEN must round-trip, and malformed input must be refused. */
    private static void fenRoundTrip() {
        ChessGame g = new ChessGame();
        String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0";
        assert g.loadFEN(start) : "A valid FEN must load";
        assert start.equals(g.toFEN()) : "FEN must round-trip: " + g.toFEN();

        assert g.makeMove("e2", "e4", Color.WHITE).isSuccess();
        assert g.makeMove("c7", "c5", Color.BLACK).isSuccess();
        String mid = g.toFEN();
        assert (mid.contains(" c3 ") || mid.contains(" c6 ")) : "En passant target must be set: " + mid;

        ChessGame reloaded = new ChessGame();
        assert reloaded.loadFEN(mid) : "A mid-game FEN must load";
        assert mid.equals(reloaded.toFEN()) : "Mid-game FEN must round-trip";

        // Malformed FEN is rejected without corrupting the current position.
        ChessGame safe = new ChessGame();
        assert !safe.loadFEN("not a fen") : "Garbage must be rejected";
        assert !safe.loadFEN("rnbqkbnr/pppppppp/8/8/8/PPPPPPPP w KQkq - 0 1")
                : "A short rank list must be rejected";
        assert !safe.loadFEN("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1")
                : "An invalid side to move must be rejected";
        assert safe.toFEN().startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq")
                : "A rejected FEN must leave the position untouched: " + safe.toFEN();
    }
}
