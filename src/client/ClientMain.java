package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/**
 * ClientMain provides an interactive console UI for players.
 */
public class ClientMain {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6700;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner scanner;
    private volatile boolean inGame = false;
    private volatile boolean running = true;
    private String myColor = "";

    public static void main(String[] args) {
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

    public void start(String host, int port) {
        scanner = new Scanner(System.in);
        System.out.println("==================================================");
        System.out.println("            CHESS MULTIPLAYER CLIENT              ");
        System.out.println("==================================================");

        try {
            System.out.println("[*] Connecting to chess server at " + host + ":" + port + "...");
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            String welcome = in.readLine();
            System.out.println("[Server] " + welcome);

            if (!performAuth()) {
                System.out.println("[!] Exiting client.");
                return;
            }

            if (!performRoomSelection()) {
                System.out.println("[!] Exiting room setup.");
                return;
            }

            playGame();

        } catch (SocketTimeoutException ste) {
            System.err.println("[Client Error] Timed out waiting for server response.");
        } catch (IOException e) {
            System.err.println("[Client Error] Connection failed: " + e.getMessage());
        } finally {
            close();
        }
    }

    private boolean performAuth() throws IOException {
        while (running) {
            System.out.println("\n--- AUTHENTICATION ---");
            System.out.println("1. Login");
            System.out.println("2. Register new account");
            System.out.println("3. Play as Guest (Ospite)");
            System.out.println("4. Exit");
            System.out.print("Choose option (1-4): ");

            String choice = scanner.nextLine().trim();
            if ("1".equals(choice)) {
                System.out.print("Username: ");
                String u = scanner.nextLine().trim();
                System.out.print("Password: ");
                String p = scanner.nextLine().trim();
                out.println("LOGIN:" + u + ":" + p);
            } else if ("2".equals(choice)) {
                System.out.print("Choose Username: ");
                String u = scanner.nextLine().trim();
                System.out.print("Choose Password: ");
                String p = scanner.nextLine().trim();
                out.println("REGISTER:" + u + ":" + p);
            } else if ("3".equals(choice)) {
                System.out.print("Enter guest nickname (or press ENTER for random): ");
                String nick = scanner.nextLine().trim();
                out.println("GUEST:" + nick);
            } else if ("4".equals(choice)) {
                return false;
            } else {
                System.out.println("[!] Invalid option.");
                continue;
            }

            String resp = in.readLine();
            if (resp != null && resp.startsWith("AUTH_OK:")) {
                System.out.println("[✓] " + resp.substring(8));
                return true;
            } else if (resp != null && resp.startsWith("AUTH_ERROR:")) {
                System.out.println("[✗] " + resp.substring(11));
            } else {
                System.out.println("[?] Server: " + resp);
            }
        }
        return false;
    }

    private boolean performRoomSelection() throws IOException {
        while (running) {
            System.out.println("\n--- ROOM SELECTION ---");
            System.out.println("1. Create a new Room");
            System.out.println("2. List & Join Public Rooms");
            System.out.println("3. Join Private Room via Code");
            System.out.println("4. Exit");
            System.out.print("Choose option (1-4): ");

            String choice = scanner.nextLine().trim();
            if ("1".equals(choice)) {
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
                    System.out.print("Enter room code to join (or BACK): ");
                    String code = scanner.nextLine().trim();
                    if ("BACK".equalsIgnoreCase(code)) continue;

                    out.println("JOIN_ROOM:" + code);
                    String joinResp = in.readLine();
                    if (joinResp != null && joinResp.startsWith("JOIN_OK:")) {
                        System.out.println("[✓] " + joinResp.substring(8));
                        return true;
                    } else {
                        System.out.println("[✗] " + joinResp);
                    }
                }
            } else if ("3".equals(choice)) {
                System.out.print("Enter 6-character private room code: ");
                String code = scanner.nextLine().trim().toUpperCase();
                out.println("JOIN_ROOM:" + code);
                String joinResp = in.readLine();
                if (joinResp != null && joinResp.startsWith("JOIN_OK:")) {
                    System.out.println("[✓] " + joinResp.substring(8));
                    return true;
                } else {
                    System.out.println("[✗] " + joinResp);
                }
            } else if ("4".equals(choice)) {
                return false;
            }
        }
        return false;
    }

    private void playGame() {
        inGame = true;
        System.out.println("[*] Entering game lobby / session...");

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
        listenerThread.setDaemon(true);
        listenerThread.start();

        while (running && inGame) {
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
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Failed to close client socket: " + e.getMessage());
        }
    }
}
