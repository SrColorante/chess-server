package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;

/**
 * ClientMain provides an interactive console UI for players:
 * - Register, Login, or Play as Guest
 * - Create room, join public room, or enter private room code
 * - Live board display
 * - Automatic keep-alive ping/pong
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
            System.err.println(ex.getMessage());
        }
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = DEFAULT_PORT;

        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                System.err.println("Invalid port: must be numeric");
                return;
            }
        }

        if (!isValidHost(host) || port < 1 || port > 65535) {
            System.err.println("Invalid host or port.");
            return;
        }

        new ClientMain().start(host, port);
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
            socket.setSoTimeout(config.getTimeoutMs());
            System.out.println("[*] Connecting to chess server at " + host + ":" + port + "...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            String welcome = in.readLine();
            ui.ok("Connected");
            ui.info("Server says: " + welcome);

            if (!performAuth()) {
                ui.warn("Exiting client");
                return;
            }

            if (!performRoomSelection()) {
                ui.warn("Room setup closed");
                return;
            }

            playGame();

        } catch (SocketTimeoutException ste) {
            System.err.println("[Client Error] Timed out waiting for server response.");
        } catch (IOException e) {
            ui.error("Connection failed: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void showConnectingIndicator(ClientConfig config) {
        if (!config.isAnimationsEnabled()) {
            ui.info("Connecting...");
            return;
        }

        String[] frames = ui.theme().isCompact()
                ? new String[]{".", "..", "..."}
                : new String[]{"░░░", "▒▒▒", "▓▓▓"};
        for (String frame : frames) {
            ui.println(ui.theme().info("Connecting " + frame));
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

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
                            "Share the code with your opponent"
                    );
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
                        ui.warn("No public rooms available.");
                        continue;
                    }
                    String[] lines = new String[rooms.size()];
                    for (int i = 0; i < rooms.size(); i++) {
                        ClientProtocolParser.PublicRoom room = rooms.get(i);
                        lines[i] = String.format("%d) %s | %s | host=%s", i + 1, room.getCode(), room.getName(), room.getHost());
                System.out.print("Enter Room Name: ");
                String name = scanner.nextLine().trim();
                System.out.print("Make room private? (y/N): ");
                boolean isPriv = scanner.nextLine().trim().equalsIgnoreCase("y");
                out.println("CREATE_ROOM:" + name + ":" + isPriv);

                String roomCreated = in.readLine();
                if (roomCreated != null && roomCreated.startsWith("ROOM_CREATED:")) {
                    String[] parts = roomCreated.split(":", 4);
                    if (parts.length >= 4) {
                        System.out.println("\n[✓] Room Created: " + parts[1]);
                        System.out.println("[*] Room Code: >>> " + parts[2] + " <<< (Share this with opponent!)");
                        return true;
                    }
                }
                System.out.println("[✗] Invalid room creation response: " + roomCreated);

            } else if ("2".equals(choice)) {
                out.println("LIST_ROOMS");
                String resp = in.readLine();
                if (resp != null && resp.startsWith("ROOMS_LIST:")) {
                    String data = resp.substring(11);
                    String[] rooms = data.split(";");
                    if (rooms.length == 0 || "0".equals(rooms[0])) {
                        System.out.println("[!] No active public rooms available. Try creating one!");
                        continue;
                    }
                    System.out.println("\nAvailable Public Rooms:");
                    for (int i = 1; i < rooms.length; i++) {
                        String[] info = rooms[i].split("\\|", 3);
                        if (info.length < 3) continue;
                        System.out.printf(" [%d] Code: %s | Name: %s | Host: %s\n", i, info[0], info[1], info[2]);
                    }
                    ui.card("PUBLIC ROOMS", lines);

                    String code = ui.prompt(scanner, "Type room code to join (or BACK):").trim().toUpperCase();
                    if ("BACK".equals(code)) {
                        continue;
                    }
                    out.println("JOIN_ROOM:" + code);
                    if (handleJoinResponse(in.readLine())) {
                        return true;
                    }
                } catch (IllegalArgumentException ex) {
                    ui.error("Could not parse room list: " + ex.getMessage());
                }

            } else if ("3".equals(choice)) {
                String code = ui.prompt(scanner, "Private room code:").trim().toUpperCase();
                out.println("JOIN_ROOM:" + code);
                if (handleJoinResponse(in.readLine())) {
                    return true;
                }
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
        ui.error(joinResp == null ? "Server closed connection" : joinResp);
        return false;
    }

    private void playGame() {
        inGame = true;
        ui.card("GAME", "Lobby ready", "Commands: e2 e4 | e2e4 | resign", "Host settings: SET_MODE/SET_COLOR/SET_TIMER");

        listener = new LineListener(in, this::handleServerLine, () -> {
            if (running) {
                ui.warn("Disconnected from game session.");
                running = false;
                inGame = false;
        Thread listenerThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    if ("PING".equals(line)) {
                        out.println("PONG");
                        continue;
                    }
                    if (line.startsWith("ASSIGNED_COLOR:")) {
                        myColor = line.substring(15).trim();
                        System.out.println("\n>>> YOU ARE PLAYING AS: " + myColor + " <<<");
                    } else if (line.startsWith("BOARD:")) {
                        StringBuilder boardBuilder = new StringBuilder();
                        String bLine;
                        while ((bLine = in.readLine()) != null) {
                            boardBuilder.append(bLine).append("\n");
                            if (bLine.contains("Current Turn:")) {
                                break;
                            }
                        }
                        System.out.println(boardBuilder);
                        System.out.print("Your action (e.g. 'e2 e4' or 'resign'): ");
                    } else if (line.startsWith("MOVE_OK:")) {
                        System.out.println("\n[Move] " + line.substring(8));
                    } else if (line.startsWith("MOVE_ERROR:")) {
                        System.out.println("\n[!] Invalid Move: " + line.substring(11));
                        System.out.print("Try again (e.g. 'e2 e4'): ");
                    } else if (line.startsWith("GAME_OVER:")) {
                        System.out.println("\n==============================================");
                        System.out.println(" [GAME OVER] " + line.substring(10));
                        System.out.println("==============================================");
                        running = false;
                        inGame = false;
                        break;
                    } else if (line.startsWith("INFO:")) {
                        System.out.println("\n[Info] " + line.substring(5));
                    } else {
                        System.out.println("\n[Server] " + line);
                    }
                }
            } catch (SocketTimeoutException ste) {
                if (running) {
                    System.out.println("\n[Server timeout - no data received]");
                }
            } catch (IOException e) {
                if (running) {
                    System.out.println("\n[Disconnected from game session]");
                }
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
                    ui.warn("Invalid format. Use e2 e4, e2e4, SET_*, or resign.");
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

        if ("BOARD:".equals(line)) {
            try {
                String board = ClientProtocolParser.parseBoardBlock(line, in);
                String turnLabel = board.contains("Current Turn: " + myColor)
                        ? "YOUR TURN"
                        : "OPPONENT TURN";
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

        ui.info("Server: " + line);
            if (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();
                if (!running) break;

                if (input.equalsIgnoreCase("resign")) {
                    out.println("RESIGN");
                } else if (!input.isEmpty()) {
                    String[] parts = input.split("\\s+");
                    if (parts.length == 2) {
                        out.println("MOVE:" + parts[0] + ":" + parts[1]);
                    } else if (parts.length == 1 && parts[0].length() == 4) {
                        out.println("MOVE:" + parts[0].substring(0, 2) + ":" + parts[0].substring(2, 4));
                    } else if (input.startsWith("SET_")) {
                        out.println(input);
                    } else {
                        System.out.println("Format: 'e2 e4' (from to) or 'resign'");
                    }
                }
            }
        }
    }

    private static boolean isValidHost(String host) {
        return host != null && !host.trim().isEmpty() && !host.contains("\n") && !host.contains("\r");
    }

    private void close() {
        running = false;
        if (listener != null) {
            listener.stop();
            listener.awaitStop(1500);
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        } catch (IOException e) {
            System.err.println("Failed to close client socket: " + e.getMessage());
        }
    }
}
