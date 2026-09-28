package game;

import java.util.Arrays;

/**
 * ChessGame encapsulates a complete chess engine with board state,
 * move validation for all 6 pieces, turn management, and board rendering.
 */
public class ChessGame {

    public enum Color {
        WHITE, BLACK;
        public Color opposite() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    public enum PieceType {
        PAWN('P'), ROOK('R'), KNIGHT('N'), BISHOP('B'), QUEEN('Q'), KING('K');

        private final char symbol;
        PieceType(char symbol) { this.symbol = symbol; }
        public char getSymbol() { return symbol; }
    }

    public static class Piece {
        private final PieceType type;
        private final Color color;

        public Piece(PieceType type, Color color) {
            this.type = type;
            this.color = color;
        }

        public PieceType getType() { return type; }
        public Color getColor() { return color; }

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

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    // 8x8 board: board[rank][file] where rank 0 is row 8 (Black), rank 7 is row 1 (White)
    private final Piece[][] board = new Piece[8][8];
    private Color currentTurn = Color.WHITE;
    private boolean gameOver = false;
    private Color winner = null;
    private String endReason = "";

    public ChessGame() {
        initBoard();
    }

    private void initBoard() {
        // Clear board
        for (int r = 0; r < 8; r++) {
            Arrays.fill(board[r], null);
        }

        // Black pieces (Rank 8 -> index 0, Rank 7 -> index 1)
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

        // White pieces (Rank 2 -> index 6, Rank 1 -> index 7)
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
    }

    public Color getCurrentTurn() { return currentTurn; }
    public boolean isGameOver() { return gameOver; }
    public Color getWinner() { return winner; }
    public String getEndReason() { return endReason; }

    public synchronized void resign(Color playerColor) {
        if (!gameOver) {
            gameOver = true;
            winner = playerColor.opposite();
            endReason = playerColor + " resigned";
        }
    }

    /**
     * Executes move e.g. "e2" to "e4".
     */
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

        if (!isValidPieceMove(piece, fromR, fromF, toR, toF, target)) {
            return new MoveResult(false, "Illegal move for " + piece.getType());
        }

        // Execute move
        board[toR][toF] = piece;
        board[fromR][fromF] = null;

        // Check if King was captured
        if (target != null && target.getType() == PieceType.KING) {
            gameOver = true;
            winner = playerColor;
            endReason = "King captured at " + toStr;
            return new MoveResult(true, "Checkmate! " + playerColor + " wins! (" + endReason + ")");
        }

        // Switch turn
        currentTurn = currentTurn.opposite();
        return new MoveResult(true, "Move accepted: " + fromStr + " -> " + toStr);
    }

    private boolean isValidPieceMove(Piece piece, int fromR, int fromF, int toR, int toF, Piece target) {
        int dR = toR - fromR;
        int dF = toF - fromF;

        switch (piece.getType()) {
            case PAWN:
                int forward = piece.getColor() == Color.WHITE ? -1 : 1;
                int startRank = piece.getColor() == Color.WHITE ? 6 : 1;

                // Move forward 1 step
                if (dF == 0 && dR == forward && target == null) {
                    return true;
                }
                // Initial move 2 steps
                if (dF == 0 && fromR == startRank && dR == 2 * forward && target == null) {
                    if (board[fromR + forward][fromF] == null) {
                        return true;
                    }
                }
                // Capture diagonally
                if (Math.abs(dF) == 1 && dR == forward && target != null) {
                    return true;
                }
                return false;

            case KNIGHT:
                return (Math.abs(dR) == 1 && Math.abs(dF) == 2) || (Math.abs(dR) == 2 && Math.abs(dF) == 1);

            case BISHOP:
                if (Math.abs(dR) == Math.abs(dF)) {
                    return isPathClear(fromR, fromF, toR, toF);
                }
                return false;

            case ROOK:
                if (dR == 0 || dF == 0) {
                    return isPathClear(fromR, fromF, toR, toF);
                }
                return false;

            case QUEEN:
                if (dR == 0 || dF == 0 || Math.abs(dR) == Math.abs(dF)) {
                    return isPathClear(fromR, fromF, toR, toF);
                }
                return false;

            case KING:
                return Math.abs(dR) <= 1 && Math.abs(dF) <= 1;

            default:
                return false;
        }
    }

    private boolean isPathClear(int fromR, int fromF, int toR, int toF) {
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
        return new int[]{rank, file};
    }

    public String renderBoard() {
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
            sb.append(" [GAME OVER - Winner: ").append(winner).append(" (").append(endReason).append(")]");
        }
        return sb.toString();
    }
}
