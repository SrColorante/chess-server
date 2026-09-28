package server;

import game.ChessGame;
import game.ChessGame.Color;
import game.ChessGame.MoveResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConnectionHandler coordinates the two players in a room.
 */
public class ConnectionHandler implements Runnable {

    public static class RoomSettings {
        public enum Mode {
            STANDARD,
            RAPID,
            BLITZ;

            public static Mode from(String value) {
                if (value == null) return null;
                try {
                    return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        public enum HostColorPreference {
            WHITE,
            BLACK,
            RANDOM;

            public static HostColorPreference from(String value) {
                if (value == null) return null;
                try {
                    return HostColorPreference.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        private static final int MIN_TIMER_SECONDS = 10;
        private static final int MAX_TIMER_SECONDS = 3600;

        private Mode mode = Mode.STANDARD;
        private int turnTimeLimitSeconds = 60;
        private HostColorPreference hostPreferredColor = HostColorPreference.WHITE;

        public synchronized Mode getMode() {
            return mode;
        }

        public synchronized boolean setMode(String modeRaw) {
            Mode parsed = Mode.from(modeRaw);
            if (parsed == null) return false;
            this.mode = parsed;
            return true;
        }

        public synchronized int getTurnTimeLimitSeconds() {
            return turnTimeLimitSeconds;
        }

        public synchronized boolean setTurnTimeLimitSeconds(int seconds) {
            if (seconds < MIN_TIMER_SECONDS || seconds > MAX_TIMER_SECONDS) {
                return false;
            }
            this.turnTimeLimitSeconds = seconds;
            return true;
        }

        public synchronized HostColorPreference getHostPreferredColor() {
            return hostPreferredColor;
        }

        public synchronized boolean setHostPreferredColor(String colorRaw) {
            HostColorPreference parsed = HostColorPreference.from(colorRaw);
            if (parsed == null) return false;
            this.hostPreferredColor = parsed;
            return true;
        }

        @Override
        public synchronized String toString() {
            return String.format("[Mode: %s, Turn Limit: %ds, Host Color: %s]", mode, turnTimeLimitSeconds, hostPreferredColor);
        }
    }

    public static class GameHandler {
        private final ChessGame chessGame = new ChessGame();
        private Color hostColor = Color.WHITE;
        private Color guestColor = Color.BLACK;
        private volatile boolean matchActive = false;

        public synchronized void init(RoomSettings.HostColorPreference hostPrefColor) {
            if (hostPrefColor == RoomSettings.HostColorPreference.BLACK) {
                hostColor = Color.BLACK;
                guestColor = Color.WHITE;
            } else if (hostPrefColor == RoomSettings.HostColorPreference.RANDOM) {
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

        public ChessGame getChessGame() {
            return chessGame;
        }

        public Color getHostColor() {
            return hostColor;
        }

        public Color getGuestColor() {
            return guestColor;
        }

        public boolean isMatchActive() {
            return matchActive && !chessGame.isGameOver();
        }

        public MoveResult handlePlayerMove(String from, String to, boolean isHost) {
            Color playerColor = isHost ? hostColor : guestColor;
            return chessGame.makeMove(from, to, playerColor);
        }

        public synchronized void resign(boolean isHost) {
            Color playerColor = isHost ? hostColor : guestColor;
            chessGame.resign(playerColor);
            matchActive = false;
        }

        public String getFinalResult() {
            if (!chessGame.isGameOver()) return "Match ended abruptly.";
            if (chessGame.getWinner() == null) {
                return "DRAW (" + chessGame.getEndReason() + ")";
            }
            return "WINNER: " + chessGame.getWinner() + " (" + chessGame.getEndReason() + ")";
        }
    }

    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int LOBBY_SOCKET_READ_TIMEOUT_MS = 1000;
    private static final int GAME_SOCKET_READ_TIMEOUT_MS = 2000;
    private static final long LOBBY_TIMEOUT_MS = 120_000L;

    private final ServerRoomHandler.Room room;
    private final ServerRoomHandler roomHandler;
    private final RoomSettings settings = new RoomSettings();
    private final GameHandler gameHandler = new GameHandler();
    private final CountDownLatch guestJoinedLatch = new CountDownLatch(1);
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    private final Socket hostSocket;
    private volatile Socket guestSocket;

    private volatile PrintWriter hostOut;
    private volatile BufferedReader hostIn;
    private volatile PrintWriter guestOut;
    private volatile BufferedReader guestIn;

    private volatile boolean running = true;
    private volatile long lastHostPing = System.currentTimeMillis();
    private volatile long lastGuestPing = System.currentTimeMillis();
    private volatile long lastTurnActionMs = System.currentTimeMillis();

    private ScheduledExecutorService keepAliveScheduler;
    private ExecutorService gameListenerPool;

    public ConnectionHandler(ServerRoomHandler.Room room, ServerRoomHandler roomHandler) {
        this.room = room;
        this.roomHandler = roomHandler;
        this.hostSocket = room.getHostSocket();
    }

    public RoomSettings getSettings() {
        return settings;
    }

    public GameHandler getGameHandler() {
        return gameHandler;
    }

    public synchronized boolean attachGuest(Socket guestSocket, String guestName) {
        if (guestSocket == null || guestName == null || !running) {
            return false;
        }
        if (this.guestSocket != null) {
            return false;
        }
        this.guestSocket = guestSocket;
        try {
            guestSocket.setSoTimeout(GAME_SOCKET_READ_TIMEOUT_MS);
            this.guestOut = new PrintWriter(new OutputStreamWriter(guestSocket.getOutputStream()), true);
            this.guestIn = new BufferedReader(new InputStreamReader(guestSocket.getInputStream()));
            lastGuestPing = System.currentTimeMillis();
            guestJoinedLatch.countDown();
            return true;
        } catch (IOException e) {
            System.err.println("Error initializing guest streams: " + e.getMessage());
            safeCloseSocket(guestSocket, "guest init failure");
            this.guestSocket = null;
            return false;
        }
    }

    @Override
    public void run() {
        try {
            if (hostSocket == null) {
                selfDestruct("Host socket missing");
                return;
            }

            hostSocket.setSoTimeout(LOBBY_SOCKET_READ_TIMEOUT_MS);
            hostOut = new PrintWriter(new OutputStreamWriter(hostSocket.getOutputStream()), true);
            hostIn = new BufferedReader(new InputStreamReader(hostSocket.getInputStream()));

            hostOut.println("ROOM_CREATED:" + room.getName() + ":" + room.getCode() + ":" + room.isPrivate());
            hostOut.println("INFO:Waiting for opponent to join room code: " + room.getCode());

            long lobbyStart = System.currentTimeMillis();
            while (running && guestJoinedLatch.getCount() > 0) {
                if (System.currentTimeMillis() - lobbyStart > LOBBY_TIMEOUT_MS) {
                    selfDestruct("Lobby timeout waiting for guest");
                    return;
                }
                try {
                    String line = readLineWithLimit(hostIn, MAX_MESSAGE_LENGTH);
                    if (line == null) {
                        selfDestruct("Host disconnected during lobby");
                        return;
                    }
                    handleLobbyCommand(line);
                } catch (SocketTimeoutException ignored) {
                    // periodic wake-up for latch/timeout checks
                }
            }

            if (!running) return;

            hostSocket.setSoTimeout(GAME_SOCKET_READ_TIMEOUT_MS);
            gameHandler.init(settings.getHostPreferredColor());
            lastTurnActionMs = System.currentTimeMillis();
            startKeepAlive();

            broadcast("MATCH_START:Room " + room.getName() + " starting!");
            hostOut.println("ASSIGNED_COLOR:" + gameHandler.getHostColor());
            if (guestOut != null) {
                guestOut.println("ASSIGNED_COLOR:" + gameHandler.getGuestColor());
            }
            broadcastBoard();

            gameListenerPool = Executors.newFixedThreadPool(2);
            gameListenerPool.submit(() -> listenPlayer(true));
            gameListenerPool.submit(() -> listenPlayer(false));

            while (running && gameHandler.isMatchActive()) {
                Thread.sleep(100);
            }

            if (gameHandler.getChessGame().isGameOver()) {
                broadcast("GAME_OVER:" + gameHandler.getFinalResult());
            }
            selfDestruct("Game session terminated");

        } catch (Exception e) {
            selfDestruct("Session error: " + e.getMessage());
        }
    }

    private void handleLobbyCommand(String cmd) {
        if (cmd == null || cmd.length() > MAX_MESSAGE_LENGTH) {
            hostOut.println("ERROR:Malformed lobby command");
            return;
        }

        if (cmd.startsWith("SET_MODE:")) {
            String value = cmd.substring(9).trim();
            if (settings.setMode(value)) {
                hostOut.println("INFO:Mode set to " + settings.getMode());
            } else {
                hostOut.println("ERROR:Invalid mode. Use STANDARD, RAPID, BLITZ");
            }
        } else if (cmd.startsWith("SET_COLOR:")) {
            String value = cmd.substring(10).trim();
            if (settings.setHostPreferredColor(value)) {
                hostOut.println("INFO:Host color set to " + settings.getHostPreferredColor());
            } else {
                hostOut.println("ERROR:Invalid color. Use WHITE, BLACK, RANDOM");
            }
        } else if (cmd.startsWith("SET_TIMER:")) {
            try {
                int timer = Integer.parseInt(cmd.substring(10).trim());
                if (settings.setTurnTimeLimitSeconds(timer)) {
                    hostOut.println("INFO:Turn timer set to " + settings.getTurnTimeLimitSeconds() + "s");
                } else {
                    hostOut.println("ERROR:Invalid timer. Allowed range: 10-3600 seconds");
                }
            } catch (NumberFormatException nfe) {
                hostOut.println("ERROR:Invalid timer. Must be numeric");
            }
        } else if ("PING".equals(cmd)) {
            lastHostPing = System.currentTimeMillis();
            hostOut.println("PONG");
        } else {
            hostOut.println("ERROR:Unknown lobby command");
        }
    }

    private void listenPlayer(boolean isHost) {
        BufferedReader in = isHost ? hostIn : guestIn;
        PrintWriter out = isHost ? hostOut : guestOut;
        String role = isHost ? "Host" : "Guest";

        if (in == null || out == null) {
            if (running) {
                selfDestruct(role + " stream unavailable");
            }
            return;
        }

        try {
            while (running) {
                String line;
                try {
                    line = readLineWithLimit(in, MAX_MESSAGE_LENGTH);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }

                if (line == null) {
                    if (running) {
                        broadcast("INFO:" + role + " disconnected.");
                        gameHandler.resign(isHost);
                        running = false;
                    }
                    break;
                }

                if ("PING".equals(line) || "PONG".equals(line)) {
                    if (isHost) {
                        lastHostPing = System.currentTimeMillis();
                    } else {
                        lastGuestPing = System.currentTimeMillis();
                    }
                    if ("PING".equals(line)) {
                        out.println("PONG");
                    }
                    continue;
                }

                if (line.startsWith("MOVE:")) {
                    String[] parts = line.split(":", 3);
                    if (parts.length != 3 || !isValidSquare(parts[1]) || !isValidSquare(parts[2])) {
                        out.println("MOVE_ERROR:Malformed move");
                        continue;
                    }
                    String from = parts[1].toLowerCase(Locale.ROOT);
                    String to = parts[2].toLowerCase(Locale.ROOT);
                    MoveResult res = gameHandler.handlePlayerMove(from, to, isHost);
                    if (res.isSuccess()) {
                        lastTurnActionMs = System.currentTimeMillis();
                        broadcast("MOVE_OK:" + role + " moved " + from + " -> " + to);
                        broadcastBoard();
                        if (gameHandler.getChessGame().isGameOver()) {
                            running = false;
                        }
                    } else {
                        out.println("MOVE_ERROR:" + res.getMessage());
                    }
                } else if ("RESIGN".equals(line)) {
                    gameHandler.resign(isHost);
                    broadcast("INFO:" + role + " has resigned.");
                    running = false;
                } else {
                    out.println("ERROR:Unknown command");
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

    private static boolean isValidSquare(String square) {
        if (square == null || square.length() != 2) return false;
        char file = Character.toLowerCase(square.charAt(0));
        char rank = square.charAt(1);
        return file >= 'a' && file <= 'h' && rank >= '1' && rank <= '8';
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
            if (!running) {
                return;
            }

            long now = System.currentTimeMillis();
            if (hostOut != null) hostOut.println("PING");
            if (guestOut != null) guestOut.println("PING");

            if (now - lastHostPing > 25_000) {
                broadcast("INFO:Host timed out (Keep-Alive failed).");
                gameHandler.resign(true);
                running = false;
                return;
            }

            if (now - lastGuestPing > 25_000) {
                broadcast("INFO:Guest timed out (Keep-Alive failed).");
                gameHandler.resign(false);
                running = false;
                return;
            }

            if (gameHandler.isMatchActive()) {
                long elapsed = now - lastTurnActionMs;
                long maxTurnMs = settings.getTurnTimeLimitSeconds() * 1000L;
                if (elapsed > maxTurnMs) {
                    Color turn = gameHandler.getChessGame().getCurrentTurn();
                    boolean hostTurn = turn == gameHandler.getHostColor();
                    broadcast("INFO:Turn timer expired for " + (hostTurn ? "Host" : "Guest") + ".");
                    gameHandler.resign(hostTurn);
                    running = false;
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static String readLineWithLimit(BufferedReader in, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int ch = in.read();
            if (ch == -1) {
                if (sb.length() == 0) return null;
                return sb.toString();
            }
            if (ch == '\n') {
                break;
            }
            if (ch != '\r') {
                sb.append((char) ch);
                if (sb.length() > maxLen) {
                    throw new IOException("Input line too long");
                }
            }
        }
        return sb.toString();
    }

    public synchronized void selfDestruct(String reason) {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        running = false;
        room.setStatus(ServerRoomHandler.RoomStatus.FINISHED);
        System.out.println("[ConnectionHandler] Room " + room.getCode() + " self-destructing: " + reason);

        if (keepAliveScheduler != null) {
            keepAliveScheduler.shutdownNow();
        }
        if (gameListenerPool != null) {
            gameListenerPool.shutdownNow();
        }

        safeCloseSocket(hostSocket, "host");
        safeCloseSocket(guestSocket, "guest");
        roomHandler.removeRoom(room.getId());
    }

    private void safeCloseSocket(Socket socket, String role) {
        if (socket == null) return;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Failed to close " + role + " socket: " + e.getMessage());
        }
    }
}
