package client;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * The chessboard, drawn by hand in a single component.
 *
 * <p>This replaces a grid of 64 {@code JButton}s. That version cost one
 * heavyweight-ish component per square, rebuilt its background on every frame
 * the server sent, and could not show anything the button model did not already
 * know about. Painting the board here means one repaint of one component per
 * frame, and the same code can render highlights, check markers, the last move
 * and a promotion picker that no button grid could express well.
 *
 * <p>Hit-testing is done arithmetically against the current square size, so the
 * board stays crisp at any size and the coordinate gutters move with it. When
 * {@link #isFlipped()} is set the whole mapping is mirrored, which is what a
 * player holding the black pieces needs.
 */
public class BoardPanel extends JComponent {

    /** Board palette: two close greys keep the contrast low and modern. */
    private static final Color LIGHT_SQUARE = new Color(38, 38, 40);
    private static final Color DARK_SQUARE = new Color(24, 24, 26);
    private static final Color SELECTED = new Color(72, 110, 168);
    private static final Color LEGAL_MOVE = new Color(96, 148, 214);
    private static final Color LAST_MOVE = new Color(92, 80, 44);
    private static final Color CHECK = new Color(196, 68, 68);
    private static final Color HOVER = new Color(255, 255, 255, 26);
    private static final Color GRID_LINE = new Color(0, 0, 0, 70);
    private static final Color COORD_TEXT = new Color(126, 126, 130);

    private static final int GUTTER = 22;

    private final String[][] board = new String[8][8];
    private String selectedSquare;
    private final Set<String> legalTargets = new HashSet<>();
    private final Set<String> captures = new HashSet<>();
    private String lastMoveFrom;
    private String lastMoveTo;
    private String checkSquare;
    private boolean flipped = false;
    private boolean interactive = true;
    private String hoverSquare;

    private SquareListener listener;

    /** Notified when the player clicks a square; coordinates are algebraic. */
    public interface SquareListener {
        void onSquareClick(String square);
    }

    public BoardPanel() {
        setPreferredSize(new Dimension(600, 600));
        setOpaque(true);
        setFocusable(true);
        setToolTipText("");

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!interactive) return;
                String sq = squareAt(e.getX(), e.getY());
                if (sq != null) {
                    requestFocusInWindow();
                    if (listener != null) listener.onSquareClick(sq);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                String sq = squareAt(e.getX(), e.getY());
                if (!java.util.Objects.equals(sq, hoverSquare)) {
                    hoverSquare = sq;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverSquare != null) {
                    hoverSquare = null;
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setSquareListener(SquareListener listener) {
        this.listener = listener;
    }

    // ── state ───────────────────────────────────────────────────────────────

    /** Replaces the whole board. {@code state[row][file]} is a FEN letter or null. */
    public void setBoard(String[][] state) {
        for (int r = 0; r < 8; r++) {
            System.arraycopy(state[r], 0, board[r], 0, 8);
        }
        repaint();
    }

    /**
     * Loads a FEN straight into the board for display. Used by the snapshot tool
     * so a rendered screenshot shows a real position; the live client always
     * feeds the board from a server frame instead.
     */
    public void loadFenForDisplay(String fen) {
        String[] parts = fen.trim().split("\\s+");
        String[] ranks = parts[0].split("/", -1);
        for (int r = 0; r < 8; r++) {
            int f = 0;
            for (int i = 0; i < ranks[r].length(); i++) {
                char c = ranks[r].charAt(i);
                if (c >= '1' && c <= '8') {
                    f += c - '0';
                } else {
                    board[r][f] = String.valueOf(c);
                    f++;
                }
            }
        }
        repaint();
    }

    public void setSelection(String square, Set<String> legal, Set<String> captureSquares) {
        this.selectedSquare = square;
        legalTargets.clear();
        if (legal != null) legalTargets.addAll(legal);
        captures.clear();
        if (captureSquares != null) captures.addAll(captureSquares);
        repaint();
    }

    public void setLastMove(String from, String to) {
        this.lastMoveFrom = from;
        this.lastMoveTo = to;
        repaint();
    }

    public void setCheckSquare(String square) {
        this.checkSquare = square;
        repaint();
    }

    public void setFlipped(boolean flipped) {
        if (this.flipped != flipped) {
            this.flipped = flipped;
            repaint();
        }
    }

    public boolean isFlipped() {
        return flipped;
    }

    public void setInteractive(boolean interactive) {
        if (this.interactive != interactive) {
            this.interactive = interactive;
            setCursor(interactive
                    ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    : java.awt.Cursor.getDefaultCursor());
            repaint();
        }
    }

    public boolean isInteractive() {
        return interactive;
    }

    /** True when the player has selected a piece and may still be choosing a target. */
    public boolean hasSelection() {
        return selectedSquare != null;
    }

    // ── geometry ────────────────────────────────────────────────────────────

    private int squareSize() {
        int side = Math.min(getWidth(), getHeight());
        return Math.max(1, (side - GUTTER * 2) / 8);
    }

    private int boardOrigin() {
        return (Math.min(getWidth(), getHeight()) - squareSize() * 8) / 2;
    }

    /** Maps a component coordinate to an algebraic square, or null outside the grid. */
    public String squareAt(int x, int y) {
        int size = squareSize();
        int origin = boardOrigin();
        int boardX = x - origin;
        int boardY = y - origin;
        if (boardX < 0 || boardY < 0 || boardX >= size * 8 || boardY >= size * 8) {
            return null;
        }
        int col = boardX / size;
        int row = boardY / size;
        return flipped ? squareName(col, row) : squareName(col, 7 - row);
    }

    private int rowOf(String square) {
        return 7 - (square.charAt(1) - '1');
    }

    private int fileOf(String square) {
        return square.charAt(0) - 'a';
    }

    private static String squareName(int file, int rankIndex) {
        return "" + (char) ('a' + file) + (rankIndex + 1);
    }

    private int screenCol(String square) {
        int file = fileOf(square);
        return flipped ? 7 - file : file;
    }

    private int screenRow(String square) {
        int row = rowOf(square);
        return flipped ? row : 7 - row;
    }

    // ── painting ────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            int size = squareSize();
            int origin = boardOrigin();

            paintBoardBackground(g2, size, origin);
            paintSquares(g2, size, origin);
            paintCoordinates(g2, size, origin);
        } finally {
            g2.dispose();
        }
    }

    private void paintBoardBackground(Graphics2D g2, int size, int origin) {
        int boardSide = size * 8;
        g2.setColor(new Color(16, 16, 18));
        g2.fill(new RoundRectangle2D.Float(origin - 8, origin - 8,
                boardSide + 16, boardSide + 16, 12, 12));
    }

    private void paintSquares(Graphics2D g2, int size, int origin) {
        for (int screenRow = 0; screenRow < 8; screenRow++) {
            for (int screenCol = 0; screenCol < 8; screenCol++) {
                int file = flipped ? 7 - screenCol : screenCol;
                int rank = flipped ? screenRow : 7 - screenRow;
                String square = squareName(file, rank);

                int x = origin + screenCol * size;
                int y = origin + screenRow * size;

                boolean dark = ((rank + file) % 2 == 1);
                Color base = dark ? DARK_SQUARE : LIGHT_SQUARE;
                g2.setColor(base);
                g2.fillRect(x, y, size, size);

                if (square.equals(lastMoveFrom) || square.equals(lastMoveTo)) {
                    g2.setColor(LAST_MOVE);
                    g2.fillRect(x, y, size, size);
                }
                if (square.equals(selectedSquare)) {
                    g2.setColor(SELECTED);
                    g2.fillRect(x, y, size, size);
                }
                if (square.equals(checkSquare)) {
                    g2.setColor(CHECK);
                    // A ring reads as "your king is in trouble" without hiding the piece.
                    g2.setStroke(new BasicStroke(Math.max(3f, size * 0.07f)));
                    g2.drawRect(x + 2, y + 2, size - 4, size - 4);
                }
                if (square.equals(hoverSquare) && interactive && !square.equals(selectedSquare)) {
                    g2.setColor(HOVER);
                    g2.fillRect(x, y, size, size);
                }

                paintMoveHint(g2, size, x, y, square);
                paintPiece(g2, size, x, y, board[rank][file]);
            }
        }
    }

    /** Draws the legal-move marker: a ring for a capture, a dot otherwise. */
    private void paintMoveHint(Graphics2D g2, int size, int x, int y, String square) {
        if (!legalTargets.contains(square)) {
            return;
        }
        g2.setColor(LEGAL_MOVE);
        if (captures.contains(square) || board[rowOf(square)][fileOf(square)] != null) {
            float inset = size * 0.10f;
            float d = size - inset * 2;
            g2.setStroke(new BasicStroke(Math.max(2f, size * 0.055f)));
            g2.draw(new Ellipse2D.Float(x + inset, y + inset, d, d));
        } else {
            float r = size * 0.17f;
            float cx = x + size / 2f;
            float cy = y + size / 2f;
            g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }
    }

    /**
     * Solid chess glyphs, so the board reads as chess rather than as a grid of
     * letters. Both sides use the filled set and are told apart by tone: an
     * outline stroke was tried and rejected, because these glyphs are detailed
     * enough at board size that the stroke floods the fill and the black pieces
     * end up looking white.
     */
    private static final char GLYPH_KING = '♚';
    private static final char GLYPH_QUEEN = '♛';
    private static final char GLYPH_ROOK = '♜';
    private static final char GLYPH_BISHOP = '♝';
    private static final char GLYPH_KNIGHT = '♞';
    private static final char GLYPH_PAWN = '♟';

    private void paintPiece(Graphics2D g2, int size, int x, int y, String piece) {
        if (piece == null) {
            return;
        }
        boolean white = Character.isUpperCase(piece.charAt(0));
        char glyph = glyphFor(Character.toUpperCase(piece.charAt(0)));

        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size * 0.82f));
        g2.setFont(font);

        FontMetrics fm = g2.getFontMetrics();
        int glyphWidth = fm.charWidth(glyph);
        float glyphHeight = fm.getAscent() - fm.getDescent() / 2f;

        float tx = x + (size - glyphWidth) / 2f;
        float ty = y + (size - glyphHeight) / 2f + fm.getAscent() / 3f;
        String text = String.valueOf(glyph);

        if (white) {
            // A soft shadow lifts the white pieces off the pale squares.
            g2.setColor(new Color(0, 0, 0, 130));
            g2.drawString(text, tx + 1.2f, ty + 1.2f);
            g2.setColor(new Color(250, 250, 250));
            g2.drawString(text, tx, ty);
        } else {
            // Mid grey, no outline: readable on both square colours and clearly
            // darker than the white pieces.
            g2.setColor(new Color(0, 0, 0, 120));
            g2.drawString(text, tx + 1.2f, ty + 1.2f);
            g2.setColor(new Color(118, 118, 130));
            g2.drawString(text, tx, ty);
        }
    }

    private static char glyphFor(char pieceType) {
        switch (pieceType) {
            case 'K': return GLYPH_KING;
            case 'Q': return GLYPH_QUEEN;
            case 'R': return GLYPH_ROOK;
            case 'B': return GLYPH_BISHOP;
            case 'N': return GLYPH_KNIGHT;
            default: return GLYPH_PAWN;
        }
    }

    private void paintCoordinates(Graphics2D g2, int size, int origin) {
        g2.setColor(COORD_TEXT);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, Math.round(size * 0.26f))));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i < 8; i++) {
            // File letters under the board.
            int screenCol = flipped ? 7 - i : i;
            String file = String.valueOf((char) ('a' + i));
            int fx = origin + screenCol * size + (size - fm.stringWidth(file)) / 2;
            int fy = origin + size * 8 + 4;
            g2.drawString(file, fx, fy);

            // Rank numbers down the left edge.
            int screenRow = flipped ? i : 7 - i;
            String rank = String.valueOf(i + 1);
            int ry = origin + screenRow * size + (size + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(rank, 2, ry);
        }
    }
}
