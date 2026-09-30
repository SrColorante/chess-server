package client;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders each screen of the GUI offscreen to a PNG so the layout can be
 * inspected without someone looking at the screen. Uses the client's own
 * look-and-feel bootstrap and card switching, so what it draws is what the app
 * shows.
 */
public class GuiSnapshot {

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        outDir.mkdirs();

        SwingUtilities.invokeAndWait(() -> {
            ClientGUI.setUpLookAndFeel();
            ClientGUI gui = new ClientGUI();
            gui.setSize(1160, 820);

            try {
                shoot(gui, outDir, "AUTH", "01-auth");
                shoot(gui, outDir, "LOBBY", "02-lobby");
                shoot(gui, outDir, "SETTINGS", "03-settings");

                // Give the game screen a real position so the board is not empty.
                demoPosition(gui.boardComponent());
                shoot(gui, outDir, "GAME", "04-game");
                demoFlipped(gui.boardComponent());
                shoot(gui, outDir, "GAME", "05-game-flipped");
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("SNAPSHOTS DONE");
        });
        System.exit(0);
    }

    private static void shoot(ClientGUI gui, File dir, String card, String name) throws Exception {
        gui.showCard(card);
        gui.addNotify();
        gui.validate();

        BufferedImage img = new BufferedImage(gui.getWidth(), gui.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        gui.getLayeredPane().paint(g);
        g.dispose();

        File file = new File(dir, name + ".png");
        ImageIO.write(img, "png", file);
        System.out.println("wrote " + file.getAbsolutePath());
    }

    /** A middlegame position with a selection, legal hints and a last move. */
    private static void demoPosition(BoardPanel board) {
        String fen = "r2q1rk1/pp2bppp/2n1bn2/2pp4/3P4/2N1PN2/PPQ1BPPP/R1B2RK1 w - - 0 9";
        board.loadFenForDisplay(fen);
        board.setLastMove("d7", "d5");
        board.setSelection("c3", new java.util.HashSet<>(java.util.List.of("b5", "d5", "e4", "a4")),
                new java.util.HashSet<>(java.util.List.of("b5")));
        board.setCheckSquare(null);
    }

    private static void demoFlipped(BoardPanel board) {
        board.setFlipped(true);
        String fen = "r2q1rk1/pp2bppp/2n1bn2/2pp4/3P4/2N1PN2/PPQ1BPPP/R1B2RK1 w - - 0 9";
        board.loadFenForDisplay(fen);
        board.setCheckSquare("e8");
    }
}
