package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Scanner;

/**
 * ClientMain provides an interactive console UI for players:
 * - Register, Login, or Play as Guest
 * - Create room, join public room, or enter private room code
 * - Live board display via TerminalUI
 * - Automatic keep-alive ping/pong via LineListener
 */
public class ClientMain {

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner scanner;
    private TerminalUI ui;
    private volatile boolean inGame = false;
    private volatile boolean running = true;
    private volatile String myColor = "";
    private LineListener listener;

    public static void main(String[] args) {
        try {
            ClientConfig config = ClientConfig.fromArgs(args);
            new ClientMain().start(config);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.exit(1);
        }
    }

    public void start(ClientConfig config) {
        scanner = new Scanner(System.in);
        TerminalTheme theme = new TerminalTheme(
                config.isColorEnabled(),
                config.isUnicodeEnabled(),
                config.isEmojiEnabled(),
                config.isCompact());
        ui = new TerminalUI(theme, System.out);

        ui.showHeader();
        ui.card(
                "SESSION",
                ui.theme().badge("HOST", config.getHost()),
                ui.theme().badge("PORT", String.valueOf(config.getPort())),
                ui.theme().badge("MODE", config.isCompact() ? "COMPACT" : "STANDARD")
        );

        try {
            showConnectingIndicator(config);
            socket = new Socket();
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), config.getTimeoutMs());
            socket.setSoTimeout(0); // Server manages timeouts, prevent client from disconnecting in lobby
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            String welcome = in.readLine();
            ui.ok("Connected");
            if (welcome != null) ui.info("Server says: " + welcome);

            if (!performAuth()) {
                ui.warn("Exiting client.");
                return;
            }

            if (!performRoomSelection()) {
                ui.warn("Room setup closed.");
                return;
            }

            playGame();

        } catch (SocketTimeoutException ste) {
            System.err.println("[Client Error] Timed out waiting for server response.");
        } catch (IOException e) {
            if (running) ui.error("Connection failed: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void showConnectingIndicator(ClientConfig config) {
        if (!config.isAnimationsEnabled()) {
            ui.info("Connecting...");
            return;
        }
        String[] frames = config.isCompact()
                ? new String[]{".", "..", "..."}
                : new String[]{"░░░", "▒▒▒", "▓▓▓"};
        for (String frame : frames) {
            ui.println(ui.theme().info("Connecting " + frame));
            try { Thread.sleep(80); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ─────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────
    private boolean performAuth() throws IOException {
        while (running) {
            ui.menu("AUTH", "Login", "Register new account", "Play as Guest", "Exit");
            String choice = ui.prompt(scanner, "Select option [1-4]:").trim();

            if ("1".equals(choice)) {
                String user = ui.prompt(scanner, "Username:").trim();
                String pass = ui.prompt(scanner, "Password:").trim();
                out.println("LOGIN:" + user + ":" + pass);
            } else if ("2".equals(choice)) {
                String user = ui.prompt(scanner, "Choose username:").trim();
                String pass = ui.prompt(scanner, "Choose password:").trim();
                out.println("REGISTER:" + user + ":" + pass);
            } else if ("3".equals(choice)) {
                String nick = ui.prompt(scanner, "Guest nickname (ENTER for random):").trim();
                out.println("GUEST:" + nick);
            } else if ("4".equals(choice)) {
                return false;
            } else {
                ui.warn("Invalid option. Choose 1-4.");
                continue;
            }

            String resp = in.readLine();
            if (resp != null && resp.startsWith("AUTH_OK:")) {
                ui.ok(resp.substring(8));
                return true;
            }
            if (resp != null && resp.startsWith("AUTH_ERROR:")) {
                ui.error(resp.substring(11));
            } else {
                ui.warn("Server: " + resp);
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────
    // Room Selection
    // ─────────────────────────────────────────────
    private boolean performRoomSelection() throws IOException {
        while (running) {
            ui.menu("ROOMS", "Create new room", "List & join public room", "Join private room via code", "Exit");
            String choice = ui.prompt(scanner, "Select option [1-4]:").trim();

            if ("1".equals(choice)) {
                String name = ui.prompt(scanner, "Room name:").trim();
                String privateFlag = ui.prompt(scanner, "Private room? [y/N]:").trim();
                boolean isPrivate = "y".equalsIgnoreCase(privateFlag);
                out.println("CREATE_ROOM:" + name + ":" + isPrivate);

                String roomCreatedLine = in.readLine();
                try {
                    ClientProtocolParser.RoomCreated created = ClientProtocolParser.parseRoomCreated(roomCreatedLine);
                    ui.ok("Room created: " + created.getRoomName());
                    ui.card(
                            "LOBBY",
                            ui.theme().badge("CODE", created.getRoomCode()),
                            ui.theme().badge("VISIBILITY", created.isPrivate() ? "PRIVATE" : "PUBLIC"),
                            "Share the code with your opponent!"
                    );
                    // Read "INFO: Waiting..." from server and discard
                    in.readLine();
                    return true;
                } catch (IllegalArgumentException ex) {
                    ui.error("Invalid server room response: " + ex.getMessage());
                }

            } else if ("2".equals(choice)) {
                out.println("LIST_ROOMS");
                String line = in.readLine();
                try {
                    List<ClientProtocolParser.PublicRoom> rooms = ClientProtocolParser.parseRoomsList(line);
                    if (rooms.isEmpty()) {
                        ui.warn("No public rooms available. Try creating one!");
                        continue;
                    }
                    String[] lines = new String[rooms.size()];
                    for (int i = 0; i < rooms.size(); i++) {
                        ClientProtocolParser.PublicRoom room = rooms.get(i);
                        lines[i] = String.format("%d) %s | %s | host=%s", i + 1, room.getCode(), room.getName(), room.getHost());
                    }
                    ui.card("PUBLIC ROOMS", lines);

                    String code = ui.prompt(scanner, "Type room code to join (or BACK):").trim().toUpperCase();
                    if ("BACK".equals(code)) continue;

                    out.println("JOIN_ROOM:" + code);
                    if (handleJoinResponse(in.readLine())) return true;

                } catch (IllegalArgumentException ex) {
                    ui.error("Could not parse room list: " + ex.getMessage());
                }

            } else if ("3".equals(choice)) {
                String code = ui.prompt(scanner, "Private room code:").trim().toUpperCase();
                out.println("JOIN_ROOM:" + code);
                if (handleJoinResponse(in.readLine())) return true;

            } else if ("4".equals(choice)) {
                return false;
            } else {
                ui.warn("Invalid option. Choose 1-4.");
            }
        }
        return false;
    }

    private boolean handleJoinResponse(String joinResp) {
        if (joinResp != null && joinResp.startsWith("JOIN_OK:")) {
            ui.ok(joinResp.substring(8));
            return true;
        }
        ui.error(joinResp == null ? "Server closed connection." : joinResp);
        return false;
    }

    // ─────────────────────────────────────────────
    // Gameplay
    // ─────────────────────────────────────────────
    private void playGame() {
        inGame = true;
        ui.card("GAME", "Lobby ready", "Commands: e2 e4 | e2e4 | resign", "Host settings: SET_MODE/SET_COLOR/SET_TIMER");

        listener = new LineListener(in, this::handleServerLine, () -> {
            if (running) {
                ui.warn("Disconnected from game session.");
                running = false;
                inGame = false;
            }
        });
        listener.start("client-listener");

        while (running && inGame) {
            String input = ui.prompt(scanner, "Action:").trim();
            if (!running) break;
            if (input.isEmpty()) continue;

            if (input.equalsIgnoreCase("resign")) {
                out.println("RESIGN");
            } else if (input.startsWith("SET_")) {
                out.println(input);
            } else {
                String[] parts = input.split("\\s+");
                if (parts.length == 2) {
                    out.println("MOVE:" + parts[0] + ":" + parts[1]);
                } else if (parts.length == 1 && parts[0].length() == 4) {
                    out.println("MOVE:" + parts[0].substring(0, 2) + ":" + parts[0].substring(2, 4));
                } else {
                    ui.warn("Invalid format. Use: e2 e4 | e2e4 | resign | SET_MODE:BLITZ");
                }
            }
        }
    }

    private void handleServerLine(String line) throws IOException {
        if ("PING".equals(line)) {
            out.println("PONG");
            return;
        }
        if (line.startsWith("ASSIGNED_COLOR:")) {
            myColor = line.substring(15).trim();
            ui.ok("Assigned color: " + myColor);
            return;
        }
        if ("BOARD:".equals(line) || line.startsWith("BOARD:")) {
            try {
                String board = ClientProtocolParser.parseBoardBlock(line, in);
                String turnLabel = board.contains("Current Turn: " + myColor) ? "YOUR TURN" : "OPPONENT TURN";
                ui.printBoard(board + "\n" + ui.theme().badge("TURN", turnLabel));
            } catch (IllegalArgumentException ex) {
                ui.error("Invalid board payload: " + ex.getMessage());
            }
            return;
        }
        if (line.startsWith("MOVE_OK:")) {
            ui.ok(line.substring(8));
            return;
        }
        if (line.startsWith("MOVE_ERROR:")) {
            ui.error("Invalid move: " + line.substring(11));
            return;
        }
        if (line.startsWith("GAME_OVER:")) {
            ui.card("GAME OVER", line.substring(10));
            running = false;
            inGame = false;
            return;
        }
        if (line.startsWith("INFO:")) {
            ui.info(line.substring(5));
            return;
        }
        if (line.startsWith("ROOM_CREATED:") || line.startsWith("AUTH_OK:") || line.startsWith("MATCH_START:")) {
            // Pre-game protocol messages, display as info
            ui.info("Server: " + line);
            return;
        }
        ui.info("Server: " + line);
    }

    // ─────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────
    private void close() {
        running = false;
        if (listener != null) {
            listener.stop();
            listener.awaitStop(1500);
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
