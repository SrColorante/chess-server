package client;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class ClientGUI extends JFrame {
    
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
        super("Chess Multiplayer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 750);
        setLocationRelativeTo(null);
        
        initUnicodeMap();
        
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        
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
        JOptionPane.showMessageDialog(this, msg, "Information", JOptionPane.INFORMATION_MESSAGE);
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
                    setTitle("Chess - Playing as " + myColor);
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
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(50, 60, 50, 60));
        
        // Let FlatLaf know this is a card/panel
        card.putClientProperty("FlatLaf.styleClass", "card");
        
        JLabel title = new JLabel("Welcome");
        title.putClientProperty("FlatLaf.styleClass", "h1");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JTextField userField = new JTextField();
        userField.putClientProperty("JTextField.placeholderText", "Username");
        userField.putClientProperty("JComponent.roundRect", true);
        userField.setPreferredSize(new Dimension(250, 45));
        
        JPasswordField passField = new JPasswordField();
        passField.putClientProperty("JTextField.placeholderText", "Password");
        passField.putClientProperty("JComponent.roundRect", true);
        passField.setPreferredSize(new Dimension(250, 45));
        
        JButton btnLogin = new JButton("Login");
        btnLogin.putClientProperty("JButton.buttonType", "roundRect");
        btnLogin.setBackground(UIManager.getColor("Actions.Blue"));
        btnLogin.setForeground(Color.WHITE);
        btnLogin.setPreferredSize(new Dimension(250, 45));
        btnLogin.addActionListener(e -> send("LOGIN:" + userField.getText() + ":" + new String(passField.getPassword())));
        
        JButton btnGuest = new JButton("Play as Guest");
        btnGuest.putClientProperty("JButton.buttonType", "roundRect");
        btnGuest.setPreferredSize(new Dimension(250, 45));
        btnGuest.addActionListener(e -> send("GUEST:Guest_" + (int)(Math.random()*1000)));
        
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 30)));
        card.add(userField);
        card.add(Box.createRigidArea(new Dimension(0, 15)));
        card.add(passField);
        card.add(Box.createRigidArea(new Dimension(0, 30)));
        card.add(btnLogin);
        card.add(Box.createRigidArea(new Dimension(0, 15)));
        card.add(btnGuest);
        
        panel.add(card);
        return panel;
    }

    private JPanel createLobbyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(50, 60, 50, 60));
        card.putClientProperty("FlatLaf.styleClass", "card");
        
        JLabel title = new JLabel("Lobby");
        title.putClientProperty("FlatLaf.styleClass", "h2");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JButton btnCreate = new JButton("Create Room");
        btnCreate.putClientProperty("JButton.buttonType", "roundRect");
        btnCreate.setBackground(UIManager.getColor("Actions.Blue"));
        btnCreate.setForeground(Color.WHITE);
        btnCreate.setPreferredSize(new Dimension(250, 45));
        btnCreate.addActionListener(e -> send("CREATE_ROOM:Match:false"));
        
        JTextField codeField = new JTextField();
        codeField.putClientProperty("JTextField.placeholderText", "Room Code");
        codeField.putClientProperty("JComponent.roundRect", true);
        codeField.setPreferredSize(new Dimension(250, 45));
        
        JButton btnJoin = new JButton("Join Room");
        btnJoin.putClientProperty("JButton.buttonType", "roundRect");
        btnJoin.setPreferredSize(new Dimension(250, 45));
        btnJoin.addActionListener(e -> send("JOIN_ROOM:" + codeField.getText()));
        
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 30)));
        card.add(btnCreate);
        card.add(Box.createRigidArea(new Dimension(0, 40)));
        card.add(new JLabel("Or join existing:"));
        card.add(Box.createRigidArea(new Dimension(0, 10)));
        card.add(codeField);
        card.add(Box.createRigidArea(new Dimension(0, 15)));
        card.add(btnJoin);
        
        panel.add(card);
        return panel;
    }

    private JPanel createGamePanel() {
        JPanel panel = new JPanel(new BorderLayout(20, 20));
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        
        JPanel boardPanel = new JPanel(new GridLayout(8, 8));
        boardPanel.setPreferredSize(new Dimension(600, 600));
        
        for (int r = 7; r >= 0; r--) {
            for (int c = 0; c < 8; c++) {
                JButton btn = new JButton();
                btn.setFont(new Font("SansSerif", Font.PLAIN, 46));
                btn.setFocusPainted(false);
                btn.setBorderPainted(false);
                btn.setBackground((r + c) % 2 != 0 ? BOARD_LIGHT : BOARD_DARK);
                btn.putClientProperty("JButton.buttonType", "square");
                
                final int finalR = r;
                final int finalC = c;
                btn.addActionListener(e -> handleSquareClick(finalR, finalC));
                
                boardSquares[r][c] = btn;
                boardPanel.add(btn);
            }
        }
        
        JPanel sidePanel = new JPanel();
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setPreferredSize(new Dimension(200, 600));
        
        JButton btnResign = new JButton("Resign");
        btnResign.putClientProperty("JButton.buttonType", "roundRect");
        btnResign.setBackground(UIManager.getColor("Actions.Red"));
        btnResign.setForeground(Color.WHITE);
        btnResign.setPreferredSize(new Dimension(180, 45));
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
            boardSquares[r][c].setBorder(BorderFactory.createLineBorder(UIManager.getColor("Actions.Red"), 4));
            boardSquares[r][c].setBorderPainted(true);
        } else {
            send("MOVE:" + selectedSquare + ":" + coord);
            for(int i=0; i<8; i++) {
                for(int j=0; j<8; j++) {
                    boardSquares[i][j].setBorderPainted(false);
                }
            }
            selectedSquare = null;
        }
    }

    public static void main(String[] args) {
        try {
            // Load custom font Comfortaa
            File fontFile = new File("lib/Comfortaa.ttf");
            if (fontFile.exists()) {
                Font comfortaa = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(comfortaa);
                UIManager.put("defaultFont", comfortaa.deriveFont(15f));
            }
            
            // Set up modern FlatLaf
            FlatDarkLaf.setup();
            
            // Global rounded corners
            UIManager.put("Button.arc", 15);
            UIManager.put("Component.arc", 15);
            UIManager.put("TextComponent.arc", 15);
            UIManager.put("Panel.arc", 20); // for cards
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI();
            gui.setVisible(true);
        });
    }
}
