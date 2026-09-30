package game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChessGame encapsulates board state, move validation, turn management, and board rendering.
 *
 * <p>Coordinate convention: {@code board[row][file]} where row 0 is rank 8 and
 * row 7 is rank 1 (so row decreases as White advances), file 0 is the a-file.
 * This matches the usual "black at the top" ASCII rendering.
 *
 * <p>All mutating and reading methods are synchronized: a single game instance is
 * shared by the two player threads in {@code ConnectionHandler}.
 *
 * <h2>Performance notes</h2>
 * <ul>
 *   <li>{@link #isSquareAttacked} walks rays outward from the target square
 *       instead of scanning all 64 pieces. Attack detection sits on the hot
 *       path: {@link #makeMove} runs it for the mover and then
 *       {@link #generateLegalMoves} runs it once per candidate reply.</li>
 *   <li>King squares are cached in {@link #kingSquare} and maintained by
 *       apply/undo, so locating a king is O(1) instead of a 64-square scan.</li>
 *   <li>Repetition uses 64-bit Zobrist keys in a hash map, making the threefold
 *       check O(1) instead of rebuilding a position string and scanning the
 *       whole move history on every move.</li>
 *   <li>The insufficient-material rule counts the whole board in one pass.</li>
 * </ul>
 */
public class ChessGame {

    public enum Color {
        WHITE, BLACK;

        public Color opposite() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    public enum PieceType {
        PAWN('P', 0), ROOK('R', 1), KNIGHT('N', 2), BISHOP('B', 3), QUEEN('Q', 4), KING('K', 5);

        private final char symbol;
        /** Stable index used by the Zobrist table and the material count. */
        final int index;

        PieceType(char symbol, int index) {
            this.symbol = symbol;
            this.index = index;
        }

        public char getSymbol() {
            return symbol;
        }

        public static PieceType fromSymbol(char c) {
            switch (Character.toUpperCase(c)) {
                case 'P': return PAWN;
                case 'R': return ROOK;
                case 'N': return KNIGHT;
                case 'B': return BISHOP;
                case 'Q': return QUEEN;
                case 'K': return KING;
                default: return null;
            }
        }
    }

    public static class Piece {
        private final PieceType type;
        private final Color color;

        public Piece(PieceType type, Color color) {
            this.type = type;
            this.color = color;
        }

        public PieceType getType() {
            return type;
        }

        public Color getColor() {
            return color;
        }

        @Override
        public String toString() {
            char s = type.getSymbol();
            return color == Color.WHITE ? String.valueOf(s) : String.valueOf(Character.toLowerCase(s));
        }
    }

    public static class MoveResult {
        private final boolean success;
        private final String message;

        public MoveResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * A fully legal move: validated against the rules and leaving the mover's
     * own king safe. Exposed so the GUI highlights real options instead of
     * re-deriving them from a second, drifting copy of the rules.
     */
    public static final class Move {
        public final int fromR;
        public final int fromF;
        public final int toR;
        public final int toF;
        public final boolean promotion;
        public final boolean enPassant;
        public final boolean castle;

        Move(int fromR, int fromF, int toR, int toF,
             boolean promotion, boolean enPassant, boolean castle) {
            this.fromR = fromR;
            this.fromF = fromF;
            this.toR = toR;
            this.toF = toF;
            this.promotion = promotion;
            this.enPassant = enPassant;
            this.castle = castle;
        }

        /** Algebraic source square, e.g. {@code "e2"}. */
        public String fromSquare() {
            return squareName(fromF, 7 - fromR);
        }

        /** Algebraic destination square, e.g. {@code "e4"}. */
        public String toSquare() {
            return squareName(toF, 7 - toR);
        }

        @Override
        public String toString() {
            return fromSquare() + "->" + toSquare();
        }
    }

    /**
     * A move that has been validated and is about to be applied. Keeping castling
     * and en passant in the same structure as ordinary moves is what allows
     * {@link #undoMove} to restore any position exactly, including the ones used
     * for check/checkmate detection.
     */
    private static class AppliedMove {
        final int fromR;
        final int fromF;
        final int toR;
        final int toF;
        /** Piece captured at the destination (normal capture or en passant victim). */
        final Piece captured;
        /** Square of an en passant victim, which is not the destination square. */
        final int capturedAtR;
        final int capturedAtF;
        final Piece moved;
        /** Rook that was relocated by a castle, so undo can put it back. */
        final Piece rookFrom;
        final int rookFromR;
        final int rookFromF;
        final int rookToR;
        final int rookToF;

        AppliedMove(int fromR, int fromF, int toR, int toF, Piece moved, Piece captured,
                    int capturedAtR, int capturedAtF,
                    Piece rookFrom, int rookFromR, int rookFromF, int rookToR, int rookToF) {
            this.fromR = fromR;
            this.fromF = fromF;
            this.toR = toR;
            this.toF = toF;
            this.moved = moved;
            this.captured = captured;
            this.capturedAtR = capturedAtR;
            this.capturedAtF = capturedAtF;
            this.rookFrom = rookFrom;
            this.rookFromR = rookFromR;
            this.rookFromF = rookFromF;
            this.rookToR = rookToR;
            this.rookToF = rookToF;
        }
    }

    // ── Zobrist keys ────────────────────────────────────────────────────────
    // One 64-bit key per (piece, square), plus keys for the side to move, the
    // four castling rights, and the en passant file. Generated from a fixed
    // seed so the values are identical on every run and every machine.
    private static final int Z_SQUARES = 64;
    private static final int Z_PIECES = 12;
    private static final int Z_SIDE = Z_SQUARES * Z_PIECES;
    private static final int Z_CASTLE = Z_SIDE + 1;
    private static final int Z_EP = Z_CASTLE + 4;
    private static final long[] ZOBRIST = new long[Z_EP + 8];

    static {
        long seed = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < ZOBRIST.length; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            ZOBRIST[i] = seed;
        }
    }

    /** 0..5 for White, 6..11 for Black, ordered by {@link PieceType#index}. */
    private static int zobristPieceIndex(Piece p) {
        return (p.getColor() == Color.WHITE ? 0 : 6) + p.getType().index;
    }

    private final Piece[][] board = new Piece[8][8];
    private Color currentTurn = Color.WHITE;
    private boolean gameOver = false;
    private Color winner = null;
    private String endReason = "";

    /** Cached king positions indexed by {@link Color#ordinal()}, or -1 when absent. */
    private final int[] kingSquare = {-1, -1};

    // Castling rights, tracked per color and side.
    private boolean whiteKingMoved = false;
    private boolean whiteRookAMoved = false;
    private boolean whiteRookHMoved = false;
    private boolean blackKingMoved = false;
    private boolean blackRookAMoved = false;
    private boolean blackRookHMoved = false;

    /** Square available for en passant, or -1. Row/file of the pawn that just moved two. */
    private int enPassantRow = -1;
    private int enPassantFile = -1;

    /** Half-move clock for the fifty-move rule. */
    private int halfMoveClock = 0;
    /** Occurrence count per Zobrist key, backing the threefold repetition rule. */
    private final Map<Long, Integer> repetitionCounts = new HashMap<>();

    // Offsets are flat {rowStep, fileStep} pairs to avoid sign surprises in
    // negative integer division.
    private static final int[] KNIGHT_DELTAS = {-2, -1, -1, -2, 1, -2, 2, -1, 2, 1, 1, 2, -1, 2, -2, 1};
    private static final int[] ROYAL_DELTAS = {-1, -1, -1, 0, -1, 1, 0, -1, 0, 1, 1, -1, 1, 0, 1, 1};
    private static final int[] ORTHOGONAL_DELTAS = {-1, 0, 1, 0, 0, -1, 0, 1};
    private static final int[] DIAGONAL_DELTAS = {-1, -1, -1, 1, 1, -1, 1, 1};

    /**
     * Compact wire format: 8 ranks of 8 squares joined by 7 slashes, then the side
     * to move. The turn character is the last of {@link #BOARD_PAYLOAD_LENGTH}
     * characters, at index {@link #BOARD_TURN_INDEX}. Naming both offsets keeps
     * the length check and the indexing in step, which is where this format
     * previously carried an off-by-one that threw on every valid frame.
     */
    private static final int BOARD_PAYLOAD_LENGTH = 8 * 8 + 7 + 1;
    private static final int BOARD_TURN_INDEX = BOARD_PAYLOAD_LENGTH - 1;

    public ChessGame() {
        initBoard();
    }

    private void initBoard() {
        for (int r = 0; r < 8; r++) {
            Arrays.fill(board[r], null);
        }

        board[0][0] = new Piece(PieceType.ROOK, Color.BLACK);
        board[0][1] = new Piece(PieceType.KNIGHT, Color.BLACK);
        board[0][2] = new Piece(PieceType.BISHOP, Color.BLACK);
        board[0][3] = new Piece(PieceType.QUEEN, Color.BLACK);
        board[0][4] = new Piece(PieceType.KING, Color.BLACK);
        board[0][5] = new Piece(PieceType.BISHOP, Color.BLACK);
        board[0][6] = new Piece(PieceType.KNIGHT, Color.BLACK);
        board[0][7] = new Piece(PieceType.ROOK, Color.BLACK);
        for (int f = 0; f < 8; f++) {
            board[1][f] = new Piece(PieceType.PAWN, Color.BLACK);
        }

        for (int f = 0; f < 8; f++) {
            board[6][f] = new Piece(PieceType.PAWN, Color.WHITE);
        }
        board[7][0] = new Piece(PieceType.ROOK, Color.WHITE);
        board[7][1] = new Piece(PieceType.KNIGHT, Color.WHITE);
        board[7][2] = new Piece(PieceType.BISHOP, Color.WHITE);
        board[7][3] = new Piece(PieceType.QUEEN, Color.WHITE);
        board[7][4] = new Piece(PieceType.KING, Color.WHITE);
        board[7][5] = new Piece(PieceType.BISHOP, Color.WHITE);
        board[7][6] = new Piece(PieceType.KNIGHT, Color.WHITE);
        board[7][7] = new Piece(PieceType.ROOK, Color.WHITE);

        currentTurn = Color.WHITE;
        gameOver = false;
        winner = null;
        endReason = "";
        whiteKingMoved = whiteRookAMoved = whiteRookHMoved = false;
        blackKingMoved = blackRookAMoved = blackRookHMoved = false;
        enPassantRow = -1;
        enPassantFile = -1;
        halfMoveClock = 0;
        kingSquare[Color.WHITE.ordinal()] = 7 * 8 + 4;
        kingSquare[Color.BLACK.ordinal()] = 0 * 8 + 4;
        repetitionCounts.clear();
        recordRepetition();
    }

    public synchronized Color getCurrentTurn() {
        return currentTurn;
    }

    public synchronized boolean isGameOver() {
        return gameOver;
    }

    public synchronized Color getWinner() {
        return winner;
    }

    public synchronized String getEndReason() {
        return endReason;
    }

    /** True when the side to move currently has its king attacked. */
    public synchronized boolean isCheck() {
        return isInCheck(currentTurn);
    }

    /** Algebraic square of the given side's king, or null when it is not on the board. */
    public synchronized String getKingSquare(Color color) {
        int sq = kingSquare[color.ordinal()];
        return sq < 0 ? null : squareName(sq % 8, 7 - sq / 8);
    }

    public synchronized void resign(Color playerColor) {
        if (!gameOver) {
            gameOver = true;
            winner = playerColor.opposite();
            endReason = playerColor + " resigned";
        }
    }

    // ── Zobrist position key ────────────────────────────────────────────────

    /**
     * 64-bit key covering piece placement, side to move, castling rights, and
     * the en passant file. Rebuilt in one 64-square pass: far cheaper than the
     * position string this replaced, and the hash map turns the threefold check
     * into a single lookup instead of a scan of the whole move history.
     */
    private long zobristKey() {
        long key = 0L;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                if (p != null) {
                    key ^= ZOBRIST[zobristPieceIndex(p) * Z_SQUARES + r * 8 + f];
                }
            }
        }
        if (currentTurn == Color.BLACK) {
            key ^= ZOBRIST[Z_SIDE];
        }
        if (!whiteKingMoved && !whiteRookHMoved) key ^= ZOBRIST[Z_CASTLE + 0];
        if (!whiteKingMoved && !whiteRookAMoved) key ^= ZOBRIST[Z_CASTLE + 1];
        if (!blackKingMoved && !blackRookHMoved) key ^= ZOBRIST[Z_CASTLE + 2];
        if (!blackKingMoved && !blackRookAMoved) key ^= ZOBRIST[Z_CASTLE + 3];
        if (enPassantFile >= 0) {
            key ^= ZOBRIST[Z_EP + enPassantFile];
        }
        return key;
    }

    private void recordRepetition() {
        repetitionCounts.merge(zobristKey(), 1, Integer::sum);
    }

    /** Occurrences of the current position, including the one just recorded. */
    private int repetitionCount() {
        return repetitionCounts.getOrDefault(zobristKey(), 0);
    }

    // ── apply / undo ────────────────────────────────────────────────────────

    /**
     * Applies a move to the board and returns the record needed by {@link #undoMove}.
     * The caller is responsible for validating the move first.
     */
    private AppliedMove applyMove(int fromR, int fromF, int toR, int toF, Color playerColor) {
        Piece moved = board[fromR][fromF];
        Piece captured = board[toR][toF];
        int capturedAtR = toR;
        int capturedAtF = toF;

        Piece rookFrom = null;
        int rookFromR = -1;
        int rookFromF = -1;
        int rookToR = -1;
        int rookToF = -1;

        // En passant: the captured pawn sits beside the destination, not on it.
        if (moved.getType() == PieceType.PAWN && toF != fromF && captured == null
                && toR == enPassantRow && toF == enPassantFile) {
            int victimR = playerColor == Color.WHITE ? toR + 1 : toR - 1;
            captured = board[victimR][toF];
            capturedAtR = victimR;
            capturedAtF = toF;
            board[victimR][toF] = null;
        }

        board[toR][toF] = moved;
        board[fromR][fromF] = null;

        // Castling: relocate the rook alongside the king.
        if (moved.getType() == PieceType.KING && Math.abs(toF - fromF) == 2) {
            if (toF > fromF) {
                rookFromR = toR;
                rookFromF = 7;
                rookToR = toR;
                rookToF = 5;
            } else {
                rookFromR = toR;
                rookFromF = 0;
                rookToR = toR;
                rookToF = 3;
            }
            rookFrom = board[rookFromR][rookFromF];
            board[rookToR][rookToF] = rookFrom;
            board[rookFromR][rookFromF] = null;
        }

        if (moved.getType() == PieceType.KING) {
            kingSquare[playerColor.ordinal()] = toR * 8 + toF;
        }

        return new AppliedMove(fromR, fromF, toR, toF, moved, captured,
                capturedAtR, capturedAtF,
                rookFrom, rookFromR, rookFromF, rookToR, rookToF);
    }

    private void undoMove(AppliedMove m) {
        board[m.fromR][m.fromF] = m.moved;
        board[m.toR][m.toF] = null;
        if (m.captured != null) {
            board[m.capturedAtR][m.capturedAtF] = m.captured;
        }
        if (m.rookFrom != null) {
            board[m.rookToR][m.rookToF] = null;
            board[m.rookFromR][m.rookFromF] = m.rookFrom;
        }
        if (m.moved.getType() == PieceType.KING) {
            kingSquare[m.moved.getColor().ordinal()] = m.fromR * 8 + m.fromF;
        }
    }

    // ── public move entry point ─────────────────────────────────────────────

    public synchronized MoveResult makeMove(String fromStr, String toStr, Color playerColor) {
        if (gameOver) {
            return new MoveResult(false, "Game is already over: " + endReason);
        }
        if (playerColor != currentTurn) {
            return new MoveResult(false, "It is not your turn. Current turn: " + currentTurn);
        }

        int[] from = parseSquare(fromStr);
        int[] to = parseSquare(toStr);
        if (from == null || to == null) {
            return new MoveResult(false, "Invalid square notation (use e.g. e2 e4)");
        }

        int fromR = from[0], fromF = from[1];
        int toR = to[0], toF = to[1];
        if (fromR == toR && fromF == toF) {
            return new MoveResult(false, "Source and destination squares must differ");
        }

        Piece piece = board[fromR][fromF];
        if (piece == null) {
            return new MoveResult(false, "No piece at " + fromStr);
        }
        if (piece.getColor() != playerColor) {
            return new MoveResult(false, "Cannot move opponent's piece");
        }

        Piece target = board[toR][toF];
        if (target != null && target.getColor() == playerColor) {
            return new MoveResult(false, "Cannot capture your own piece at " + toStr);
        }
        if (target != null && target.getType() == PieceType.KING) {
            return new MoveResult(false, "King capture is not a legal move");
        }

        boolean isEnPassant = isEnPassantCapture(piece, fromR, fromF, toR, toF, target);
        boolean isCastle = isCastlingMove(piece, fromR, fromF, toR, toF);

        if (!isEnPassant && !isCastle && !isValidPieceMove(piece, fromR, fromF, toR, toF, target)) {
            return new MoveResult(false, "Illegal move for " + piece.getType());
        }

        // Save the en passant window: a pawn double push sets the new one, every
        // other move clears it.
        int prevEpR = enPassantRow;
        int prevEpF = enPassantFile;

        AppliedMove applied = applyMove(fromR, fromF, toR, toF, playerColor);

        if (wouldPromotePawn(piece, toR)) {
            board[toR][toF] = new Piece(PieceType.QUEEN, playerColor);
        }

        if (isInCheck(playerColor)) {
            undoMove(applied);
            enPassantRow = prevEpR;
            enPassantFile = prevEpF;
            return new MoveResult(false, "Illegal move: your king would remain in check");
        }

        // The move is legal: update castling rights, en passant, and clocks.
        updateCastlingRights(piece, fromR, fromF, toR, toF);
        if (piece.getType() == PieceType.PAWN && Math.abs(toR - fromR) == 2) {
            enPassantRow = (fromR + toR) / 2;
            enPassantFile = fromF;
        } else {
            enPassantRow = -1;
            enPassantFile = -1;
        }

        if (piece.getType() == PieceType.PAWN || applied.captured != null) {
            halfMoveClock = 0;
        } else {
            halfMoveClock++;
        }

        Color opponent = playerColor.opposite();
        boolean opponentInCheck = isInCheck(opponent);
        boolean opponentHasLegalMove = !generateLegalMoves(opponent).isEmpty();

        String moveText = fromStr + " -> " + toStr;

        if (!opponentHasLegalMove) {
            currentTurn = opponent;
            recordRepetition();
            gameOver = true;
            if (opponentInCheck) {
                winner = playerColor;
                endReason = "Checkmate";
                return new MoveResult(true, "Checkmate! " + playerColor + " wins.");
            }
            winner = null;
            endReason = "Stalemate";
            return new MoveResult(true, "Draw by stalemate.");
        }

        if (isInsufficientMaterial()) {
            currentTurn = opponent;
            recordRepetition();
            gameOver = true;
            winner = null;
            endReason = "Insufficient material";
            return new MoveResult(true, "Draw: insufficient material to checkmate.");
        }

        if (halfMoveClock >= 100) {
            currentTurn = opponent;
            recordRepetition();
            gameOver = true;
            winner = null;
            endReason = "Fifty-move rule";
            return new MoveResult(true, "Draw by the fifty-move rule.");
        }

        currentTurn = opponent;
        recordRepetition();
        if (repetitionCount() >= 3) {
            gameOver = true;
            winner = null;
            endReason = "Threefold repetition";
            return new MoveResult(true, "Draw by threefold repetition.");
        }

        if (opponentInCheck) {
            return new MoveResult(true, "Move accepted: " + moveText + " (Check)");
        }
        return new MoveResult(true, "Move accepted: " + moveText);
    }

    /**
     * True when neither side has enough material left to ever deliver checkmate:
     * king vs king, king+minor vs king, king+minor vs king+minor of the same
     * class. A lone bishop and a lone knight can never force mate, and neither
     * can any position without at least one side holding a bishop or knight.
     *
     * <p>Counted in a single pass over the board rather than one scan per piece
     * type, since this runs after every move.
     */
    private boolean isInsufficientMaterial() {
        int[] counts = new int[Z_PIECES];
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                if (p != null) {
                    counts[zobristPieceIndex(p)]++;
                }
            }
        }

        int whitePawns = counts[PieceType.PAWN.index];
        int blackPawns = counts[6 + PieceType.PAWN.index];
        if (whitePawns > 0 || blackPawns > 0) return false;

        int whiteRooks = counts[PieceType.ROOK.index];
        int blackRooks = counts[6 + PieceType.ROOK.index];
        if (whiteRooks > 0 || blackRooks > 0) return false;

        int whiteQueens = counts[PieceType.QUEEN.index];
        int blackQueens = counts[6 + PieceType.QUEEN.index];
        if (whiteQueens > 0 || blackQueens > 0) return false;

        // Only kings and minors remain.
        int whiteMinors = counts[PieceType.KNIGHT.index] + counts[PieceType.BISHOP.index];
        int blackMinors = counts[6 + PieceType.KNIGHT.index] + counts[6 + PieceType.BISHOP.index];

        if (whiteMinors <= 1 && blackMinors <= 1) {
            // K vs K, K+minor vs K, or K+minor vs K+minor: never mate.
            return true;
        }

        // Two bishops on the same colour square with no other pieces can never mate.
        if (whiteMinors == 2 && blackMinors == 0 && sameColourBishops(Color.WHITE)) {
            return true;
        }
        if (blackMinors == 2 && whiteMinors == 0 && sameColourBishops(Color.BLACK)) {
            return true;
        }

        return false;
    }

    /** True when the side's only remaining pieces are bishops, all on one square colour. */
    private boolean sameColourBishops(Color color) {
        int colourBit = -1;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                if (p == null || p.getColor() != color || p.getType() != PieceType.BISHOP) {
                    continue;
                }
                int bit = (r + f) & 1;
                if (colourBit == -1) {
                    colourBit = bit;
                } else if (colourBit != bit) {
                    return false; // mixed square colours: mate is possible
                }
            }
        }
        return colourBit != -1;
    }

    private void updateCastlingRights(Piece piece, int fromR, int fromF, int toR, int toF) {
        if (piece.getType() == PieceType.KING) {
            if (piece.getColor() == Color.WHITE) whiteKingMoved = true;
            else blackKingMoved = true;
        }
        if (piece.getType() == PieceType.ROOK) {
            if (fromR == 7 && fromF == 0) whiteRookAMoved = true;
            if (fromR == 7 && fromF == 7) whiteRookHMoved = true;
            if (fromR == 0 && fromF == 0) blackRookAMoved = true;
            if (fromR == 0 && fromF == 7) blackRookHMoved = true;
        }
        // A rook captured on its home square also removes the opponent's right.
        if (toR == 7 && toF == 0) whiteRookAMoved = true;
        if (toR == 7 && toF == 7) whiteRookHMoved = true;
        if (toR == 0 && toF == 0) blackRookAMoved = true;
        if (toR == 0 && toF == 7) blackRookHMoved = true;
    }

    private boolean isEnPassantCapture(Piece piece, int fromR, int fromF, int toR, int toF, Piece target) {
        if (piece.getType() != PieceType.PAWN) return false;
        if (toF == fromF) return false;
        if (target != null) return false;
        if (enPassantRow < 0) return false;
        if (toR != enPassantRow || toF != enPassantFile) return false;
        int forward = piece.getColor() == Color.WHITE ? -1 : 1;
        return toR - fromR == forward;
    }

    private boolean isCastlingMove(Piece piece, int fromR, int fromF, int toR, int toF) {
        if (piece.getType() != PieceType.KING) return false;
        if (piece.getColor() == Color.WHITE && fromR != 7) return false;
        if (piece.getColor() == Color.BLACK && fromR != 0) return false;
        if (toR != fromR) return false;
        if (Math.abs(toF - fromF) != 2) return false;

        boolean kingSide = toF > fromF;
        int rookF = kingSide ? 7 : 0;
        boolean kingMoved = piece.getColor() == Color.WHITE ? whiteKingMoved : blackKingMoved;
        boolean rookMoved;
        if (piece.getColor() == Color.WHITE) {
            rookMoved = kingSide ? whiteRookHMoved : whiteRookAMoved;
        } else {
            rookMoved = kingSide ? blackRookHMoved : blackRookAMoved;
        }
        if (kingMoved || rookMoved) return false;

        Piece rook = board[fromR][rookF];
        if (rook == null || rook.getType() != PieceType.ROOK || rook.getColor() != piece.getColor()) {
            return false;
        }

        // All squares between king and rook must be empty.
        int step = kingSide ? 1 : -1;
        for (int f = fromF + step; f != rookF; f += step) {
            if (board[fromR][f] != null) return false;
        }

        // The king may not start, cross, or land on an attacked square.
        if (isSquareAttacked(fromR, fromF, piece.getColor().opposite())) return false;
        if (isSquareAttacked(fromR, fromF + step, piece.getColor().opposite())) return false;
        if (isSquareAttacked(toR, toF, piece.getColor().opposite())) return false;

        return true;
    }

    private boolean wouldPromotePawn(Piece piece, int toRank) {
        if (piece.getType() != PieceType.PAWN) return false;
        return (piece.getColor() == Color.WHITE && toRank == 0)
                || (piece.getColor() == Color.BLACK && toRank == 7);
    }

    // ── legality ────────────────────────────────────────────────────────────

    private boolean isValidPieceMove(Piece piece, int fromR, int fromF, int toR, int toF, Piece target) {
        int dR = toR - fromR;
        int dF = toF - fromF;

        if (dR == 0 && dF == 0) {
            return false;
        }

        switch (piece.getType()) {
            case PAWN:
                int forward = piece.getColor() == Color.WHITE ? -1 : 1;
                int startRank = piece.getColor() == Color.WHITE ? 6 : 1;
                if (dF == 0 && dR == forward && target == null) {
                    return true;
                }
                // Double push: the intermediate square must be empty too, and a
                // pawn already on the last rank cannot jump two squares.
                if (dF == 0 && fromR == startRank && dR == 2 * forward && target == null) {
                    return board[fromR + forward][fromF] == null;
                }
                return Math.abs(dF) == 1 && dR == forward && target != null;

            case KNIGHT:
                return (Math.abs(dR) == 1 && Math.abs(dF) == 2) || (Math.abs(dR) == 2 && Math.abs(dF) == 1);

            case BISHOP:
                return Math.abs(dR) == Math.abs(dF) && isPathClear(fromR, fromF, toR, toF);

            case ROOK:
                return (dR == 0 || dF == 0) && isPathClear(fromR, fromF, toR, toF);

            case QUEEN:
                return (dR == 0 || dF == 0 || Math.abs(dR) == Math.abs(dF)) && isPathClear(fromR, fromF, toR, toF);

            case KING:
                return Math.abs(dR) <= 1 && Math.abs(dF) <= 1;

            default:
                return false;
        }
    }

    private boolean isPathClear(int fromR, int fromF, int toR, int toF) {
        if (fromR == toR && fromF == toF) {
            return false;
        }

        int stepR = Integer.compare(toR, fromR);
        int stepF = Integer.compare(toF, fromF);

        int currR = fromR + stepR;
        int currF = fromF + stepF;
        while (currR != toR || currF != toF) {
            if (board[currR][currF] != null) {
                return false;
            }
            currR += stepR;
            currF += stepF;
        }
        return true;
    }

    /**
     * Whether {@code (targetR, targetF)} is attacked by any piece of {@code byColor}.
     *
     * <p>Rays are walked outward from the target rather than scanning all 64
     * pieces and re-deriving a path for each. A full scan called once per
     * candidate reply dominated move validation; this version only touches the
     * squares that can possibly attack and returns at the first one found.
     */
    private boolean isSquareAttacked(int targetR, int targetF, Color byColor) {
        // A White pawn on (targetR+1, f±1) attacks the target; a Black pawn
        // attacks downward, so it must sit on (targetR-1, f±1).
        int pawnRow = byColor == Color.WHITE ? targetR + 1 : targetR - 1;
        for (int df = -1; df <= 1; df += 2) {
            int f = targetF + df;
            if (f < 0 || f > 7 || pawnRow < 0 || pawnRow > 7) continue;
            Piece p = board[pawnRow][f];
            if (p != null && p.getColor() == byColor && p.getType() == PieceType.PAWN) {
                return true;
            }
        }

        if (isFixedLeaperAttacked(targetR, targetF, byColor, KNIGHT_DELTAS, PieceType.KNIGHT)) {
            return true;
        }
        if (isFixedLeaperAttacked(targetR, targetF, byColor, ROYAL_DELTAS, PieceType.KING)) {
            return true;
        }
        if (rayAttack(targetR, targetF, byColor, ORTHOGONAL_DELTAS, true, false)) {
            return true;
        }
        return rayAttack(targetR, targetF, byColor, DIAGONAL_DELTAS, false, true);
    }

    /** Checks the eight fixed offsets around the target for one leaper type. */
    private boolean isFixedLeaperAttacked(int targetR, int targetF, Color byColor,
                                          int[] deltas, PieceType type) {
        for (int i = 0; i < deltas.length; i += 2) {
            int r = targetR + deltas[i];
            int f = targetF + deltas[i + 1];
            if (r < 0 || r > 7 || f < 0 || f > 7) continue;
            Piece p = board[r][f];
            if (p != null && p.getColor() == byColor && p.getType() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks each given direction outward. The first non-empty square on a ray
     * decides: a matching rook/queen (or bishop/queen) means the target is
     * attacked, and the ray stops there either way.
     */
    private boolean rayAttack(int targetR, int targetF, Color byColor,
                              int[] deltas, boolean rookLike, boolean bishopLike) {
        for (int i = 0; i < deltas.length; i += 2) {
            int r = targetR + deltas[i];
            int f = targetF + deltas[i + 1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                Piece p = board[r][f];
                if (p != null) {
                    if (p.getColor() == byColor) {
                        PieceType t = p.getType();
                        if (t == PieceType.QUEEN
                                || (rookLike && t == PieceType.ROOK)
                                || (bishopLike && t == PieceType.BISHOP)) {
                            return true;
                        }
                    }
                    break; // any piece blocks this ray, friendly or not
                }
                r += deltas[i];
                f += deltas[i + 1];
            }
        }
        return false;
    }

    private boolean isInCheck(Color color) {
        int sq = kingSquare[color.ordinal()];
        if (sq < 0) {
            // A missing king counts as check so no caller treats it as safe.
            return true;
        }
        return isSquareAttacked(sq / 8, sq % 8, color.opposite());
    }

    /**
     * All legal moves for {@code color} in the current position.
     *
     * <p>A move is included only when it leaves the mover's own king safe, so
     * checkmate detection and the GUI's move hints share exactly one definition
     * of legality instead of keeping two copies of the rules in sync.
     */
    public synchronized List<Move> generateLegalMoves(Color color) {
        List<Move> moves = new ArrayList<>(48);
        for (int fromR = 0; fromR < 8; fromR++) {
            for (int fromF = 0; fromF < 8; fromF++) {
                Piece piece = board[fromR][fromF];
                if (piece == null || piece.getColor() != color) continue;
                collectPieceMoves(piece, fromR, fromF, color, moves);
            }
        }
        return moves;
    }

    /**
     * Enumerates the destinations a single piece could plausibly reach.
     *
     * <p>Walking every one of the 64 target squares for every piece meant 4096
     * candidate pairs per position, the vast majority of them geometrically
     * impossible. Each piece type now yields only its own candidates: knights
     * and kings get their fixed offsets, sliders walk their rays, pawns get
     * pushes and captures. The full rules check still runs on each candidate, so
     * the resulting move list is identical, just reached without the dead work.
     */
    private void collectPieceMoves(Piece piece, int fromR, int fromF, Color color, List<Move> out) {
        switch (piece.getType()) {
            case PAWN:
                collectPawnMoves(piece, fromR, fromF, color, out);
                break;
            case KNIGHT:
                collectLeaperMoves(piece, fromR, fromF, color, out, KNIGHT_DELTAS);
                break;
            case KING:
                collectLeaperMoves(piece, fromR, fromF, color, out, ROYAL_DELTAS);
                // Castling is the one king move that is not a single step.
                for (int side = 0; side < 2; side++) {
                    int destF = side == 0 ? fromF + 2 : fromF - 2;
                    addIfLegal(piece, fromR, fromF, fromR, destF, color, out);
                }
                break;
            case BISHOP:
                collectSliderMoves(piece, fromR, fromF, color, out, DIAGONAL_DELTAS, false, true);
                break;
            case ROOK:
                collectSliderMoves(piece, fromR, fromF, color, out, ORTHOGONAL_DELTAS, true, false);
                break;
            case QUEEN:
                collectSliderMoves(piece, fromR, fromF, color, out, ORTHOGONAL_DELTAS, true, false);
                collectSliderMoves(piece, fromR, fromF, color, out, DIAGONAL_DELTAS, false, true);
                break;
            default:
                break;
        }
    }

    private void collectLeaperMoves(Piece piece, int fromR, int fromF, Color color,
                                    List<Move> out, int[] deltas) {
        for (int i = 0; i < deltas.length; i += 2) {
            addIfLegal(piece, fromR, fromF, fromR + deltas[i], fromF + deltas[i + 1], color, out);
        }
    }

    /** Walks each ray until it leaves the board or meets a piece, offering every square passed. */
    private void collectSliderMoves(Piece piece, int fromR, int fromF, Color color,
                                    List<Move> out, int[] deltas, boolean rookLike, boolean bishopLike) {
        for (int i = 0; i < deltas.length; i += 2) {
            int dR = deltas[i];
            int dF = deltas[i + 1];
            int r = fromR + dR;
            int f = fromF + dF;
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                boolean occupied = board[r][f] != null;
                addIfLegal(piece, fromR, fromF, r, f, color, out);
                if (occupied) {
                    break; // cannot slide past a piece, whatever its colour
                }
                r += dR;
                f += dF;
            }
        }
    }

    private void collectPawnMoves(Piece piece, int fromR, int fromF, Color color, List<Move> out) {
        int forward = color == Color.WHITE ? -1 : 1;
        int startRow = color == Color.WHITE ? 6 : 1;

        // Single and double push.
        addIfLegal(piece, fromR, fromF, fromR + forward, fromF, color, out);
        if (fromR == startRow) {
            addIfLegal(piece, fromR, fromF, fromR + 2 * forward, fromF, color, out);
        }
        // Captures, including en passant which lands on an empty square.
        addIfLegal(piece, fromR, fromF, fromR + forward, fromF - 1, color, out);
        addIfLegal(piece, fromR, fromF, fromR + forward, fromF + 1, color, out);
    }

    /**
     * Runs the full legality pipeline for one candidate destination and records
     * it when the mover's own king survives. Every rule check the engine has
     * lives here, so pseudo-legal generation above can stay purely geometric.
     */
    private void addIfLegal(Piece piece, int fromR, int fromF, int toR, int toF,
                            Color color, List<Move> out) {
        if (toR < 0 || toR > 7 || toF < 0 || toF > 7) return;
        if (fromR == toR && fromF == toF) return;

        Piece target = board[toR][toF];
        if (target != null && target.getColor() == color) return;
        if (target != null && target.getType() == PieceType.KING) return;

        boolean ep = isEnPassantCapture(piece, fromR, fromF, toR, toF, target);
        boolean castle = isCastlingMove(piece, fromR, fromF, toR, toF);
        if (!ep && !castle && !isValidPieceMove(piece, fromR, fromF, toR, toF, target)) {
            return;
        }

        boolean promotes = wouldPromotePawn(piece, toR);
        AppliedMove applied = applyMove(fromR, fromF, toR, toF, color);
        if (promotes) {
            board[toR][toF] = new Piece(PieceType.QUEEN, color);
        }

        boolean legal = !isInCheck(color);

        undoMove(applied);

        if (legal) {
            out.add(new Move(fromR, fromF, toR, toF, promotes, ep, castle));
        }
    }

    /** Legal moves for the side to move that originate from one algebraic square. */
    public synchronized List<Move> legalMovesFrom(String square) {
        List<Move> moves = new ArrayList<>(8);
        int[] idx = parseSquare(square);
        if (idx == null) {
            return moves;
        }
        for (Move m : generateLegalMoves(currentTurn)) {
            if (m.fromR == idx[0] && m.fromF == idx[1]) {
                moves.add(m);
            }
        }
        return moves;
    }

    // ── perft ───────────────────────────────────────────────────────────────

    /**
     * Counts leaf nodes of the legal move tree to {@code depth}: the standard
     * correctness and performance probe for a move generator. From the standard
     * opening position the expected values are 20, 400, 8902, and 197281 for
     * depths 1 to 4.
     */
    public synchronized long perft(int depth) {
        if (depth <= 0) {
            return 1;
        }
        return perftRec(depth);
    }

    /**
     * Perft deliberately skips the repetition bookkeeping: it saves and restores
     * only the state that legality depends on, and lets apply/undo restore the
     * board, so walking hundreds of thousands of nodes allocates no strings.
     */
    private long perftRec(int depth) {
        List<Move> moves = generateLegalMoves(currentTurn);
        if (depth == 1) {
            return moves.size();
        }

        long nodes = 0;
        for (Move m : moves) {
            PerftUndo undo = applyLegal(m);
            nodes += perftRec(depth - 1);
            undoLegal(undo);
        }
        return nodes;
    }

    /** Scalar state captured before a perft move, so it can be restored exactly. */
    private static final class PerftUndo {
        Piece moved;
        Color turn;
        boolean whiteKingMoved, whiteRookAMoved, whiteRookHMoved;
        boolean blackKingMoved, blackRookAMoved, blackRookHMoved;
        int enPassantRow, enPassantFile, halfMoveClock;
        AppliedMove applied;
    }

    /** Applies a move already known to be legal. Caller holds the monitor. */
    private PerftUndo applyLegal(Move m) {
        PerftUndo undo = new PerftUndo();
        undo.moved = board[m.fromR][m.fromF];
        Color color = undo.moved.getColor();
        undo.turn = currentTurn;
        undo.whiteKingMoved = whiteKingMoved;
        undo.whiteRookAMoved = whiteRookAMoved;
        undo.whiteRookHMoved = whiteRookHMoved;
        undo.blackKingMoved = blackKingMoved;
        undo.blackRookAMoved = blackRookAMoved;
        undo.blackRookHMoved = blackRookHMoved;
        undo.enPassantRow = enPassantRow;
        undo.enPassantFile = enPassantFile;
        undo.halfMoveClock = halfMoveClock;

        undo.applied = applyMove(m.fromR, m.fromF, m.toR, m.toF, color);
        if (m.promotion) {
            board[m.toR][m.toF] = new Piece(PieceType.QUEEN, color);
        }

        updateCastlingRights(undo.moved, m.fromR, m.fromF, m.toR, m.toF);
        if (undo.moved.getType() == PieceType.PAWN && Math.abs(m.toR - m.fromR) == 2) {
            enPassantRow = (m.fromR + m.toR) / 2;
            enPassantFile = m.fromF;
        } else {
            enPassantRow = -1;
            enPassantFile = -1;
        }
        halfMoveClock = (undo.moved.getType() == PieceType.PAWN || undo.applied.captured != null)
                ? 0 : undo.halfMoveClock + 1;
        currentTurn = color.opposite();
        return undo;
    }

    private void undoLegal(PerftUndo undo) {
        undoMove(undo.applied);
        currentTurn = undo.turn;
        whiteKingMoved = undo.whiteKingMoved;
        whiteRookAMoved = undo.whiteRookAMoved;
        whiteRookHMoved = undo.whiteRookHMoved;
        blackKingMoved = undo.blackKingMoved;
        blackRookAMoved = undo.blackRookAMoved;
        blackRookHMoved = undo.blackRookHMoved;
        enPassantRow = undo.enPassantRow;
        enPassantFile = undo.enPassantFile;
        halfMoveClock = undo.halfMoveClock;
    }

    // ── parsing and rendering ───────────────────────────────────────────────

    /** Algebraic square name; {@code rankIndex} is 0 for rank 1 and 7 for rank 8. */
    public static String squareName(int file, int rankIndex) {
        return "" + (char) ('a' + file) + (rankIndex + 1);
    }

    private int[] parseSquare(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        if (s.length() != 2) return null;
        char fileChar = s.charAt(0);
        char rankChar = s.charAt(1);
        if (fileChar < 'a' || fileChar > 'h') return null;
        if (rankChar < '1' || rankChar > '8') return null;

        int file = fileChar - 'a';
        int rank = 8 - (rankChar - '0');
        return new int[] {rank, file};
    }

    /**
     * Serializes the board into a single line: {@code BOARD:rnbqkbnr/pppppppp/.../RNBQKBNRw}.
     *
     * <p>Multi-line rendering was the source of a real bug: the old format shipped the
     * ASCII grid inside one {@code println}, so the client's line reader and its board
     * reader raced each other on the same socket and corrupted the stream. A
     * single-line frame removes the interleaving entirely.
     */
    public synchronized String renderBoardCompact() {
        StringBuilder sb = new StringBuilder(70);
        sb.append("BOARD:");
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                sb.append(p != null ? p.toString() : ".");
            }
            if (r < 7) sb.append('/');
        }
        sb.append(currentTurn == Color.WHITE ? 'w' : 'b');
        return sb.toString();
    }

    /**
     * Parses a line produced by {@link #renderBoardCompact()}.
     * Returns null when the line is not a well-formed board frame.
     */
    public static String[] parseBoardCompact(String line) {
        if (line == null || !line.startsWith("BOARD:")) return null;
        String body = line.substring(6);
        // 8 ranks separated by 7 slashes, plus the turn character. Checked before
        // any indexing: an empty or short payload ("BOARD:") must be rejected here
        // rather than throwing further down.
        if (body.length() != BOARD_PAYLOAD_LENGTH) return null;
        for (int r = 0; r < 8; r++) {
            int base = r * 9;
            if (r < 7 && body.charAt(base + 8) != '/') return null;
            for (int f = 0; f < 8; f++) {
                char c = body.charAt(base + f);
                boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.';
                if (!valid) return null;
            }
        }
        char turn = body.charAt(BOARD_TURN_INDEX);
        if (turn != 'w' && turn != 'b') return null;
        return new String[] {body.substring(0, BOARD_TURN_INDEX), String.valueOf(turn)};
    }

    public synchronized String renderBoard() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  +---+---+---+---+---+---+---+---+\n");
        for (int r = 0; r < 8; r++) {
            sb.append(8 - r).append(" |");
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                sb.append(" ").append(p != null ? p.toString() : ".").append(" |");
            }
            sb.append("\n  +---+---+---+---+---+---+---+---+\n");
        }
        sb.append("    a   b   c   d   e   f   g   h\n");
        sb.append("Current Turn: ").append(currentTurn);
        if (gameOver) {
            sb.append(" [GAME OVER - ");
            if (winner == null) {
                sb.append("Draw");
            } else {
                sb.append("Winner: ").append(winner);
            }
            sb.append(" (").append(endReason).append(")]");
        }
        return sb.toString();
    }

    /**
     * Serializes the position to standard algebraic FEN, used by tests and by any
     * future notation or PGN export.
     */
    public synchronized String toFEN() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                if (p == null) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(p.toString());
                }
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        sb.append(' ').append(currentTurn == Color.WHITE ? "w" : "b");
        sb.append(' ').append(castlingRights());
        sb.append(' ').append(enPassantRow >= 0
                ? "" + (char) ('a' + enPassantFile) + (8 - enPassantRow)
                : "-");
        sb.append(' ').append(halfMoveClock);
        return sb.toString();
    }

    /**
     * Castling rights as FEN letters, derived from whether the king and rook are
     * actually still on their home squares.
     *
     * <p>Deriving them from the moved-flags alone produced a wrong FEN: after
     * Rxa8 the black h-side rook was gone and the engine correctly refused to
     * castle, yet the FEN still advertised a "k" right. The flags only record
     * that a piece moved, not that it is still there, so the board is the
     * authority here.
     */
    private String castlingRights() {
        StringBuilder sb = new StringBuilder();
        if (!whiteKingMoved && !whiteRookHMoved && hasPiece(7, 4, PieceType.KING, Color.WHITE)
                && hasPiece(7, 7, PieceType.ROOK, Color.WHITE)) {
            sb.append('K');
        }
        if (!whiteKingMoved && !whiteRookAMoved && hasPiece(7, 4, PieceType.KING, Color.WHITE)
                && hasPiece(7, 0, PieceType.ROOK, Color.WHITE)) {
            sb.append('Q');
        }
        if (!blackKingMoved && !blackRookHMoved && hasPiece(0, 4, PieceType.KING, Color.BLACK)
                && hasPiece(0, 7, PieceType.ROOK, Color.BLACK)) {
            sb.append('k');
        }
        if (!blackKingMoved && !blackRookAMoved && hasPiece(0, 4, PieceType.KING, Color.BLACK)
                && hasPiece(0, 0, PieceType.ROOK, Color.BLACK)) {
            sb.append('q');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private boolean hasPiece(int row, int file, PieceType type, Color color) {
        Piece p = board[row][file];
        return p != null && p.getType() == type && p.getColor() == color;
    }

    /**
     * Replaces the current position with the one described by a FEN string.
     *
     * <p>Tests need to describe exact positions (a stalemate, a bare king pair)
     * that cannot be reached from the opening by a short legal line. Returns
     * false and leaves the game untouched when the FEN is malformed.
     */
    public synchronized boolean loadFEN(String fen) {
        if (fen == null || fen.isBlank()) {
            return false;
        }
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            return false;
        }

        Piece[][] parsed = new Piece[8][8];
        String[] ranks = parts[0].split("/", -1);
        if (ranks.length != 8) {
            return false;
        }

        for (int r = 0; r < 8; r++) {
            int f = 0;
            for (int i = 0; i < ranks[r].length(); i++) {
                char c = ranks[r].charAt(i);
                if (c >= '1' && c <= '8') {
                    f += c - '0';
                } else {
                    PieceType type = PieceType.fromSymbol(c);
                    if (type == null || f > 7) {
                        return false;
                    }
                    parsed[r][f] = new Piece(type,
                            Character.isUpperCase(c) ? Color.WHITE : Color.BLACK);
                    f++;
                }
            }
            if (f != 8) {
                return false;
            }
        }

        Color turn;
        if ("w".equals(parts[1])) {
            turn = Color.WHITE;
        } else if ("b".equals(parts[1])) {
            turn = Color.BLACK;
        } else {
            return false;
        }

        String castling = parts[2];

        int halfMove = 0;
        if (parts.length >= 5) {
            try {
                halfMove = Integer.parseInt(parts[4]);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        int epRow = -1;
        int epFile = -1;
        if (!"-".equals(parts[3]) && parts[3].length() == 2) {
            char fileChar = Character.toLowerCase(parts[3].charAt(0));
            char rankChar = parts[3].charAt(1);
            if (fileChar >= 'a' && fileChar <= 'h' && rankChar >= '1' && rankChar <= '8') {
                epFile = fileChar - 'a';
                epRow = 8 - (rankChar - '0');
            }
        }

        // Everything parsed: commit.
        for (int r = 0; r < 8; r++) {
            System.arraycopy(parsed[r], 0, board[r], 0, 8);
        }
        currentTurn = turn;
        gameOver = false;
        winner = null;
        endReason = "";

        whiteKingMoved = !(castling.indexOf('K') >= 0 || castling.indexOf('Q') >= 0);
        whiteRookHMoved = castling.indexOf('K') < 0;
        whiteRookAMoved = castling.indexOf('Q') < 0;
        blackKingMoved = !(castling.indexOf('k') >= 0 || castling.indexOf('q') >= 0);
        blackRookHMoved = castling.indexOf('k') < 0;
        blackRookAMoved = castling.indexOf('q') < 0;

        enPassantRow = epRow;
        enPassantFile = epFile;
        halfMoveClock = halfMove;

        kingSquare[Color.WHITE.ordinal()] = -1;
        kingSquare[Color.BLACK.ordinal()] = -1;
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                if (board[r][f] != null && board[r][f].getType() == PieceType.KING) {
                    kingSquare[board[r][f].getColor().ordinal()] = r * 8 + f;
                }
            }
        }

        repetitionCounts.clear();
        recordRepetition();
        return true;
    }
}
