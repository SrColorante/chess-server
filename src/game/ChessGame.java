package game;

import java.util.Arrays;

/**
 * ChessGame encapsulates board state, move validation, turn management, and board rendering.
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

        PieceType(char symbol) {
            this.symbol = symbol;
        }

        public char getSymbol() {
            return symbol;
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

    private final Piece[][] board = new Piece[8][8];
    private Color currentTurn = Color.WHITE;
    private boolean gameOver = false;
    private Color winner = null;
    private String endReason = "";

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

    public synchronized void resign(Color playerColor) {
        if (!gameOver) {
            gameOver = true;
            winner = playerColor.opposite();
            endReason = playerColor + " resigned";
        }
    }

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

        if (!isValidPieceMove(piece, fromR, fromF, toR, toF, target)) {
            return new MoveResult(false, "Illegal move for " + piece.getType());
        }

        Piece moved = board[fromR][fromF];
        Piece captured = board[toR][toF];
        board[toR][toF] = moved;
        board[fromR][fromF] = null;

        if (wouldPromotePawn(moved, toR)) {
            board[toR][toF] = new Piece(PieceType.QUEEN, moved.getColor());
        }

        if (isInCheck(playerColor)) {
            board[fromR][fromF] = moved;
            board[toR][toF] = captured;
            return new MoveResult(false, "Illegal move: your king would remain in check");
        }

        Color opponent = currentTurn.opposite();
        boolean opponentInCheck = isInCheck(opponent);
        boolean opponentHasLegalMove = hasAnyLegalMove(opponent);

        if (!opponentHasLegalMove) {
            gameOver = true;
            if (opponentInCheck) {
                winner = playerColor;
                endReason = "Checkmate";
                currentTurn = opponent;
                return new MoveResult(true, "Checkmate! " + playerColor + " wins.");
            }
            winner = null;
            endReason = "Stalemate";
            currentTurn = opponent;
            return new MoveResult(true, "Draw by stalemate.");
        }

        currentTurn = opponent;
        if (opponentInCheck) {
            return new MoveResult(true, "Move accepted: " + fromStr + " -> " + toStr + " (Check)");
        }
        return new MoveResult(true, "Move accepted: " + fromStr + " -> " + toStr);
    }

    private boolean wouldPromotePawn(Piece piece, int toRank) {
        if (piece.getType() != PieceType.PAWN) return false;
        return (piece.getColor() == Color.WHITE && toRank == 0)
                || (piece.getColor() == Color.BLACK && toRank == 7);
    }

    private boolean hasAnyLegalMove(Color color) {
        for (int fromR = 0; fromR < 8; fromR++) {
            for (int fromF = 0; fromF < 8; fromF++) {
                Piece piece = board[fromR][fromF];
                if (piece == null || piece.getColor() != color) continue;

                for (int toR = 0; toR < 8; toR++) {
                    for (int toF = 0; toF < 8; toF++) {
                        if (fromR == toR && fromF == toF) continue;
                        Piece target = board[toR][toF];
                        if (target != null && target.getColor() == color) continue;
                        if (target != null && target.getType() == PieceType.KING) continue;
                        if (!isValidPieceMove(piece, fromR, fromF, toR, toF, target)) continue;

                        Piece moved = board[fromR][fromF];
                        Piece captured = board[toR][toF];
                        board[toR][toF] = moved;
                        board[fromR][fromF] = null;

                        boolean legal = !isInCheck(color);

                        board[fromR][fromF] = moved;
                        board[toR][toF] = captured;

                        if (legal) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInCheck(Color color) {
        int[] king = locateKing(color);
        if (king == null) {
            return true;
        }

        Color opponent = color.opposite();
        for (int fromR = 0; fromR < 8; fromR++) {
            for (int fromF = 0; fromF < 8; fromF++) {
                Piece attacker = board[fromR][fromF];
                if (attacker == null || attacker.getColor() != opponent) continue;
                if (isValidAttackMove(attacker, fromR, fromF, king[0], king[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private int[] locateKing(Color color) {
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece p = board[r][f];
                if (p != null && p.getColor() == color && p.getType() == PieceType.KING) {
                    return new int[] {r, f};
                }
            }
        }
        return null;
    }

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

    private boolean isValidAttackMove(Piece piece, int fromR, int fromF, int toR, int toF) {
        int dR = toR - fromR;
        int dF = toF - fromF;
        if (dR == 0 && dF == 0) return false;

        switch (piece.getType()) {
            case PAWN:
                int forward = piece.getColor() == Color.WHITE ? -1 : 1;
                return dR == forward && Math.abs(dF) == 1;
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
}
