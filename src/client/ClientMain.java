package client;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

/**
 * ClientMain provides an interactive console UI for players:
 * - Register, Login, or Play as Guest
 * - Create room, join public room, or enter private room code
 * - Live ASCII chess board display
 * - Automatic background keep-alive ping/pong
 */
public class ClientMain {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6700;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner scanner;
    private volatile boolean inGame = false;
    private volatile boolean running = true;
    private String myColor = "";

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new ClientMain().start(host, port);
    }

    public void start(String host, int port) {
        scanner = new Scanner(System.in);
        System.out.println("==================================================");
        System.out.println("            CHESS MULTIPLAYER CLIENT              ");
        System.out.println("==================================================");

        try {
            System.out.println("[*] Connecting to chess server at " + host + ":" + port + "...");
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            // Read welcome message
            String welcome = in.readLine();
            System.out.println("[Server] " + welcome);

            // 1. Authentication
            if (!performAuth()) {
                System.out.println("[!] Exiting client.");
                return;
            }

            // 2. Room Selection
            if (!performRoomSelection()) {
                System.out.println("[!] Exiting room setup.");
                return;
            }

            // 3. Gameplay loop
            playGame();

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

                // Read room creation info
                String roomCreated = in.readLine();
                if (roomCreated != null && roomCreated.startsWith("ROOM_CREATED:")) {
                    String[] parts = roomCreated.split(":");
                    System.out.println("\n[✓] Room Created: " + parts[1]);
                    System.out.println("[*] Room Code: >>> " + parts[2] + " <<< (Share this with opponent!)");
                    return true;
                }
            } else if ("2".equals(choice)) {
                out.println("LIST_ROOMS");
                String resp = in.readLine();
                if (resp != null && resp.startsWith("ROOMS_LIST:")) {
                    String data = resp.substring(11);
                    if (data.startsWith("0")) {
                        System.out.println("[!] No active public rooms available. Try creating one!");
                        continue;
                    }
                    String[] rooms = data.split(";");
                    System.out.println("\nAvailable Public Rooms:");
                    for (int i = 1; i < rooms.length; i++) {
                        String[] info = rooms[i].split("\\|");
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

        // Background server listener (handles keep-alive PING and server events)
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
                            if (bLine.contains("Current Turn:")) {
                                boardBuilder.append(bLine).append("\n");
                                break;
                            }
                            boardBuilder.append(bLine).append("\n");
                        }
                        System.out.println(boardBuilder.toString());
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
            } catch (IOException e) {
                if (running) {
                    System.out.println("\n[Disconnected from game session]");
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();

        // Foreground user input loop
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
                        // Allow room settings commands before match
                        out.println(input);
                    } else {
                        System.out.println("Format: 'e2 e4' (from to) or 'resign'");
                    }
                }
            }
        }
    }

    private void close() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
