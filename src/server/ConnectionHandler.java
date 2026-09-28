package server;

import game.ChessGame;
import game.ChessGame.Color;
import game.ChessGame.MoveResult;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

/**
 * ConnectionHandler coordinates the two hosts (host & guest) in a room.
 * Encapsulates:
 * 4a) RoomSettings: manages game rules and room configuration.
 * 4b) GameHandler: starts, updates, validates, and terminates the game.
 * 4c) Lifecycle & Keep-Alive: monitors ping/pong heartbeats and self-destructs upon game finish.
 */
public class ConnectionHandler implements Runnable {

    // ==========================================
    // 4a) RoomSettings: Settings & Rules
    // ==========================================
    public static class RoomSettings {
        private String mode = "STANDARD";
        private int turnTimeLimitSeconds = 60;
        private String hostPreferredColor = "WHITE"; // WHITE, BLACK, RANDOM

        public synchronized String getMode() { return mode; }
        public synchronized void setMode(String mode) { this.mode = mode; }

        public synchronized int getTurnTimeLimitSeconds() { return turnTimeLimitSeconds; }
        public synchronized void setTurnTimeLimitSeconds(int seconds) { this.turnTimeLimitSeconds = seconds; }

        public synchronized String getHostPreferredColor() { return hostPreferredColor; }
        public synchronized void setHostPreferredColor(String color) { this.hostPreferredColor = color; }

        @Override
        public synchronized String toString() {
            return String.format("[Mode: %s, Turn Limit: %ds, Host Color: %s]", mode, turnTimeLimitSeconds, hostPreferredColor);
        }
    }

    // ==========================================
    // 4b) GameHandler: Game Execution & Rules
    // ==========================================
    public static class GameHandler {
        private final ChessGame chessGame = new ChessGame();
        private Color hostColor = Color.WHITE;
        private Color guestColor = Color.BLACK;
        private boolean matchActive = false;

        public void init(String hostPrefColor) {
            if ("BLACK".equalsIgnoreCase(hostPrefColor)) {
                hostColor = Color.BLACK;
                guestColor = Color.WHITE;
            } else if ("RANDOM".equalsIgnoreCase(hostPrefColor)) {
                if (Math.random() < 0.5) {
                    hostColor = Color.WHITE;
                    guestColor = Color.BLACK;
                } else {
                    hostColor = Color.BLACK;
                    guestColor = Color.WHITE;
                }
            } else {
                hostColor = Color.WHITE;
                guestColor = Color.BLACK;
            }
            matchActive = true;
        }

        public ChessGame getChessGame() { return chessGame; }
        public Color getHostColor() { return hostColor; }
        public Color getGuestColor() { return guestColor; }
        public boolean isMatchActive() { return matchActive && !chessGame.isGameOver(); }

        public MoveResult handlePlayerMove(String from, String to, boolean isHost) {
            Color playerColor = isHost ? hostColor : guestColor;
            return chessGame.makeMove(from, to, playerColor);
        }

        public void resign(boolean isHost) {
            Color playerColor = isHost ? hostColor : guestColor;
            chessGame.resign(playerColor);
            matchActive = false;
        }

        public String getFinalResult() {
            if (!chessGame.isGameOver()) return "Match ended abruptly.";
            return "WINNER: " + chessGame.getWinner() + " (" + chessGame.getEndReason() + ")";
        }
    }

    // ==========================================
    // 4c) ConnectionHandler Core Lifecycle
    // ==========================================
    private final ServerRoomHandler.Room room;
    private final ServerRoomHandler roomHandler;
    private final RoomSettings settings = new RoomSettings();
    private final GameHandler gameHandler = new GameHandler();

    private Socket hostSocket;
    private Socket guestSocket;
    private PrintWriter hostOut;
    private BufferedReader hostIn;
    private PrintWriter guestOut;
    private BufferedReader guestIn;

    private volatile boolean running = true;
    private volatile long lastHostPing = System.currentTimeMillis();
    private volatile long lastGuestPing = System.currentTimeMillis();
    private ScheduledExecutorService keepAliveScheduler;

    public ConnectionHandler(ServerRoomHandler.Room room, ServerRoomHandler roomHandler) {
        this.room = room;
        this.roomHandler = roomHandler;
        this.hostSocket = room.getHostSocket();
    }

    public RoomSettings getSettings() { return settings; }
    public GameHandler getGameHandler() { return gameHandler; }

    public void setGuest(Socket guestSocket, String guestName) {
        this.guestSocket = guestSocket;
        this.room.setGuest(guestSocket, guestName);
        try {
            this.guestOut = new PrintWriter(new OutputStreamWriter(guestSocket.getOutputStream()), true);
            this.guestIn = new BufferedReader(new InputStreamReader(guestSocket.getInputStream()));
        } catch (IOException e) {
            System.err.println("Error initializing guest streams: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            if (hostSocket != null) {
                hostOut = new PrintWriter(new OutputStreamWriter(hostSocket.getOutputStream()), true);
                hostIn = new BufferedReader(new InputStreamReader(hostSocket.getInputStream()));
            }

            // Phase 1: Wait for Guest and allow Host to adjust settings
            hostOut.println("ROOM_CREATED:" + room.getName() + ":" + room.getCode() + ":" + room.isPrivate());
            hostOut.println("INFO:Waiting for opponent to join room code: " + room.getCode());

            // Handle pre-game host commands (settings)
            while (running && guestSocket == null) {
                if (hostIn.ready()) {
                    String line = hostIn.readLine();
                    if (line == null) {
                        selfDestruct("Host disconnected during lobby phase.");
                        return;
                    }
                    handleLobbyCommand(line);
                }
                Thread.sleep(100);
            }

            if (!running) return;

            // Phase 2: Both clients connected! Start game
            gameHandler.init(settings.getHostPreferredColor());
            startKeepAlive();

            broadcast("MATCH_START:Room " + room.getName() + " starting!");
            hostOut.println("ASSIGNED_COLOR:" + gameHandler.getHostColor());
            guestOut.println("ASSIGNED_COLOR:" + gameHandler.getGuestColor());
            broadcastBoard();

            // Phase 3: Game Loop
            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.submit(() -> listenPlayer(true));
            pool.submit(() -> listenPlayer(false));

            while (running && gameHandler.isMatchActive()) {
                Thread.sleep(200);
            }

            // Phase 4: Game concluded -> broadcast results & self-destruct
            broadcast("GAME_OVER:" + gameHandler.getFinalResult());
            Thread.sleep(1000);
            selfDestruct("Game concluded normally.");

        } catch (Exception e) {
            selfDestruct("Session error: " + e.getMessage());
        }
    }

    private void handleLobbyCommand(String cmd) {
        if (cmd.startsWith("SET_MODE:")) {
            settings.setMode(cmd.substring(9).trim());
            hostOut.println("INFO:Mode set to " + settings.getMode());
        } else if (cmd.startsWith("SET_COLOR:")) {
            settings.setHostPreferredColor(cmd.substring(10).trim());
            hostOut.println("INFO:Host color set to " + settings.getHostPreferredColor());
        } else if (cmd.startsWith("SET_TIMER:")) {
            try {
                settings.setTurnTimeLimitSeconds(Integer.parseInt(cmd.substring(10).trim()));
                hostOut.println("INFO:Turn timer set to " + settings.getTurnTimeLimitSeconds() + "s");
            } catch (NumberFormatException ignored) {}
        } else if (cmd.equals("PING")) {
            lastHostPing = System.currentTimeMillis();
            hostOut.println("PONG");
        }
    }

    private void listenPlayer(boolean isHost) {
        BufferedReader in = isHost ? hostIn : guestIn;
        String role = isHost ? "Host" : "Guest";

        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.equals("PING")) {
                    if (isHost) lastHostPing = System.currentTimeMillis();
                    else lastGuestPing = System.currentTimeMillis();
                    PrintWriter out = isHost ? hostOut : guestOut;
                    if (out != null) out.println("PONG");
                    continue;
                } else if (line.equals("PONG")) {
                    if (isHost) lastHostPing = System.currentTimeMillis();
                    else lastGuestPing = System.currentTimeMillis();
                    continue;
                }

                if (line.startsWith("MOVE:")) {
                    // format MOVE:e2:e4
                    String[] parts = line.split(":");
                    if (parts.length >= 3) {
                        String from = parts[1];
                        String to = parts[2];
                        MoveResult res = gameHandler.handlePlayerMove(from, to, isHost);
                        PrintWriter out = isHost ? hostOut : guestOut;
                        if (res.isSuccess()) {
                            broadcast("MOVE_OK:" + role + " moved " + from + " -> " + to);
                            broadcastBoard();
                            if (gameHandler.getChessGame().isGameOver()) {
                                running = false;
                            }
                        } else {
                            out.println("MOVE_ERROR:" + res.getMessage());
                        }
                    }
                } else if (line.equals("RESIGN")) {
                    gameHandler.resign(isHost);
                    broadcast("INFO:" + role + " has resigned.");
                    running = false;
                }
            }
        } catch (IOException e) {
            if (running) {
                broadcast("INFO:" + role + " disconnected.");
                gameHandler.resign(isHost);
                running = false;
            }
        }
    }

    private void broadcast(String msg) {
        if (hostOut != null) hostOut.println(msg);
        if (guestOut != null) guestOut.println(msg);
    }

    private void broadcastBoard() {
        String boardView = gameHandler.getChessGame().renderBoard();
        broadcast("BOARD:\n" + boardView);
    }

    private void startKeepAlive() {
        keepAliveScheduler = Executors.newSingleThreadScheduledExecutor();
        keepAliveScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // Ping both clients
            if (hostOut != null) hostOut.println("PING");
            if (guestOut != null) guestOut.println("PING");

            // Check responsiveness (timeout after 25s without response)
            if (now - lastHostPing > 25000) {
                broadcast("INFO:Host timed out (Keep-Alive failed).");
                gameHandler.resign(true);
                running = false;
            } else if (now - lastGuestPing > 25000) {
                broadcast("INFO:Guest timed out (Keep-Alive failed).");
                gameHandler.resign(false);
                running = false;
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized void selfDestruct(String reason) {
        if (!running) return;
        running = false;
        System.out.println("[ConnectionHandler] Room " + room.getCode() + " self-destructing: " + reason);

        if (keepAliveScheduler != null) {
            keepAliveScheduler.shutdownNow();
        }

        try {
            if (hostSocket != null && !hostSocket.isClosed()) hostSocket.close();
            if (guestSocket != null && !guestSocket.isClosed()) guestSocket.close();
        } catch (IOException ignored) {}

        // Remove room from ServerRoomHandler
        roomHandler.removeRoom(room.getId());
    }
}
