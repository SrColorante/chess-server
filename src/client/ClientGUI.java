package client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class ClientGUI extends JFrame {
    
    // Modern UI Constants
    private static final Color BG_COLOR = new Color(30, 30, 34);
    private static final Color SURFACE_COLOR = new Color(42, 42, 48);
    private static final Color PRIMARY_COLOR = new Color(74, 144, 226);
    private static final Color TEXT_PRIMARY = new Color(240, 240, 240);
    private static final Color BOARD_LIGHT = new Color(238, 238, 210);
    private static final Color BOARD_DARK = new Color(118, 150, 86);
    
    private CardLayout cardLayout;
    private JPanel mainContainer;
    
    // Server state
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String myColor = "";
    private LineListener listener;
    
    // Chess state
    private JButton[][] boardSquares = new JButton[8][8];
    private String selectedSquare = null;
    private Map<String, String> pieceUnicodeMap = new HashMap<>();

    public ClientGUI() {
        super("Modern Chess Client");
        
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {}

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 750);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_COLOR);
        
        initUnicodeMap();
        
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setBackground(BG_COLOR);
        mainContainer.setOpaque(true);
        
        mainContainer.add(createAuthPanel(), "AUTH");
        mainContainer.add(createLobbyPanel(), "LOBBY");
        mainContainer.add(createGamePanel(), "GAME");
        
        add(mainContainer);
        cardLayout.show(mainContainer, "AUTH");
        
        connectToServer();
    }
    
    private void initUnicodeMap() {
        pieceUnicodeMap.put("P", "♙"); pieceUnicodeMap.put("N", "♘");
        pieceUnicodeMap.put("B", "♗"); pieceUnicodeMap.put("R", "♖");
        pieceUnicodeMap.put("Q", "♕"); pieceUnicodeMap.put("K", "♔");
        pieceUnicodeMap.put("p", "♟"); pieceUnicodeMap.put("n", "♞");
        pieceUnicodeMap.put("b", "♝"); pieceUnicodeMap.put("r", "♜");
        pieceUnicodeMap.put("q", "♛"); pieceUnicodeMap.put("k", "♚");
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 6700);
                socket.setSoTimeout(0);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                
                String welcome = in.readLine();
                if (welcome == null) throw new IOException("Server returned null on welcome");
                
                listener = new LineListener(in, this::handleServerLine, () -> {
                    SwingUtilities.invokeLater(() -> {
                        showErrorPopup("Disconnected from server");
                        System.exit(0);
                    });
                });
                listener.start("GUI-Listener");
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    showErrorPopup("Could not connect to server: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void showErrorPopup(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
    
    private void send(String cmd) {
        if (out != null) {
            out.println(cmd);
        } else {
            showErrorPopup("Not connected to server!");
        }
    }

    private void handleServerLine(String line) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (line.startsWith("AUTH_OK:")) {
                    cardLayout.show(mainContainer, "LOBBY");
                } else if (line.startsWith("AUTH_ERROR:")) {
                    showErrorPopup(line.substring(11));
                } else if (line.startsWith("ROOM_CREATED:") || line.startsWith("JOIN_OK:")) {
                    cardLayout.show(mainContainer, "GAME");
                } else if (line.startsWith("ASSIGNED_COLOR:")) {
                    myColor = line.substring(15).trim();
                    setTitle("Modern Chess - Playing as " + myColor);
                } else if (line.startsWith("BOARD:")) {
                    updateBoardFromNetwork();
                } else if (line.startsWith("MOVE_ERROR:")) {
                    showErrorPopup(line.substring(11));
                } else if (line.startsWith("GAME_OVER:")) {
                    JOptionPane.showMessageDialog(this, line.substring(10), "Game Over", JOptionPane.INFORMATION_MESSAGE);
                    cardLayout.show(mainContainer, "LOBBY");
                } else if ("PING".equals(line)) {
                    send("PONG");
                }
            } catch (Exception ex) {
                showErrorPopup("Error processing server message: " + ex.getMessage());
            }
        });
    }
    
    private void updateBoardFromNetwork() {
        try {
            for (int r = 0; r < 8; r++) {
                String row = in.readLine().substring(2); 
                String[] pieces = row.trim().split("\\s+");
                for (int c = 0; c < 8; c++) {
                    String p = pieces[c];
                    JButton btn = boardSquares[7 - r][c];
                    if (p.equals(".")) {
                        btn.setText("");
                    } else {
                        btn.setText(pieceUnicodeMap.getOrDefault(p, p));
                        btn.setForeground(Character.isUpperCase(p.charAt(0)) ? Color.WHITE : Color.BLACK);
                    }
                }
            }
        } catch (IOException e) {
            showErrorPopup("Error reading board: " + e.getMessage());
        }
    }

    private JPanel createAuthPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG_COLOR);
        panel.setOpaque(true);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(SURFACE_COLOR);
        card.setOpaque(true);
        card.setBorder(new EmptyBorder(40, 40, 40, 40));
        
        JLabel title = new JLabel("Chess Server");
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        title.setForeground(TEXT_PRIMARY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JTextField userField = createModernTextField("Username");
        JPasswordField passField = new JPasswordField();
        passField.setPreferredSize(new Dimension(300, 40));
        passField.setMaximumSize(new Dimension(300, 40));
        
        JButton btnLogin = createModernButton("Login", PRIMARY_COLOR);
        btnLogin.addActionListener(e -> send("LOGIN:" + userField.getText() + ":" + new String(passField.getPassword())));
        
        JButton btnGuest = createModernButton("Play as Guest", new Color(100, 100, 100));
        btnGuest.addActionListener(e -> send("GUEST:Guest_" + (int)(Math.random()*1000)));
        
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 30)));
        card.add(userField);
        card.add(Box.createRigidArea(new Dimension(0, 10)));
        card.add(passField);
        card.add(Box.createRigidArea(new Dimension(0, 20)));
        card.add(btnLogin);
        card.add(Box.createRigidArea(new Dimension(0, 10)));
        card.add(btnGuest);
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(card, gbc);
        return panel;
    }

    private JPanel createLobbyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG_COLOR);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(SURFACE_COLOR);
        card.setOpaque(true);
        card.setBorder(new EmptyBorder(40, 40, 40, 40));
        
        JButton btnCreate = createModernButton("Create Public Room", PRIMARY_COLOR);
        btnCreate.addActionListener(e -> send("CREATE_ROOM:Match:false"));
        
        JTextField codeField = createModernTextField("Enter Room Code");
        JButton btnJoin = createModernButton("Join Room", new Color(100, 100, 100));
        btnJoin.addActionListener(e -> send("JOIN_ROOM:" + codeField.getText()));
        
        card.add(btnCreate);
        card.add(Box.createRigidArea(new Dimension(0, 30)));
        
        JLabel lbl = new JLabel("Or join existing:");
        lbl.setForeground(TEXT_PRIMARY);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(lbl);
        
        card.add(Box.createRigidArea(new Dimension(0, 10)));
        card.add(codeField);
        card.add(Box.createRigidArea(new Dimension(0, 10)));
        card.add(btnJoin);
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(card, gbc);
        return panel;
    }

    private JPanel createGamePanel() {
        JPanel panel = new JPanel(new BorderLayout(20, 20));
        panel.setBackground(BG_COLOR);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        
        JPanel boardPanel = new JPanel(new GridLayout(8, 8));
        boardPanel.setPreferredSize(new Dimension(600, 600));
        boardPanel.setBackground(BG_COLOR);
        
        for (int r = 7; r >= 0; r--) {
            for (int c = 0; c < 8; c++) {
                JButton btn = new JButton();
                btn.setFont(new Font("SansSerif", Font.PLAIN, 46));
                btn.setFocusPainted(false);
                btn.setBorderPainted(false);
                btn.setOpaque(true);
                btn.setBackground((r + c) % 2 != 0 ? BOARD_LIGHT : BOARD_DARK);
                
                final int finalR = r;
                final int finalC = c;
                btn.addActionListener(e -> handleSquareClick(finalR, finalC));
                
                boardSquares[r][c] = btn;
                boardPanel.add(btn);
            }
        }
        
        JPanel sidePanel = new JPanel();
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setBackground(BG_COLOR);
        sidePanel.setPreferredSize(new Dimension(200, 600));
        
        JButton btnResign = createModernButton("Resign", new Color(220, 53, 69));
        btnResign.addActionListener(e -> send("RESIGN"));
        
        sidePanel.add(Box.createVerticalGlue());
        sidePanel.add(btnResign);
        
        panel.add(boardPanel, BorderLayout.CENTER);
        panel.add(sidePanel, BorderLayout.EAST);
        return panel;
    }

    private void handleSquareClick(int r, int c) {
        String coord = "" + (char)('a' + c) + (r + 1);
        if (selectedSquare == null) {
            selectedSquare = coord;
            boardSquares[r][c].setBorder(BorderFactory.createLineBorder(Color.RED, 3));
            boardSquares[r][c].setBorderPainted(true);
        } else {
            send("MOVE:" + selectedSquare + ":" + coord);
            // reset borders
            for(int i=0; i<8; i++){
                for(int j=0; j<8; j++){
                    boardSquares[i][j].setBorderPainted(false);
                }
            }
            selectedSquare = null;
        }
    }

    private JTextField createModernTextField(String placeholder) {
        JTextField tf = new JTextField(placeholder);
        tf.setPreferredSize(new Dimension(300, 40));
        tf.setMaximumSize(new Dimension(300, 40));
        tf.setFont(new Font("SansSerif", Font.PLAIN, 16));
        tf.setBackground(new Color(50, 50, 58));
        tf.setForeground(TEXT_PRIMARY);
        tf.setCaretColor(TEXT_PRIMARY);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(70, 70, 80)),
            new EmptyBorder(5, 10, 5, 10)
        ));
        tf.setAlignmentX(Component.CENTER_ALIGNMENT);
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent evt) {
                if (tf.getText().equals(placeholder)) {
                    tf.setText("");
                }
            }
        });
        return tf;
    }
    
    private JButton createModernButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setForeground(Color.WHITE);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setPreferredSize(new Dimension(300, 40));
        btn.setMaximumSize(new Dimension(300, 40));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI();
            gui.setVisible(true);
        });
    }
}
