package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * ServerMain binds to port 6700, accepts incoming connections,
 * processes authentication / room selection, and delegates rooms to ConnectionHandler.
 */
public class ServerMain {
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int AUTH_TIMEOUT_MS = 300_000;   // 5 minuti per autenticarsi
    private static final int LOBBY_TIMEOUT_MS = 600_000;  // 10 minuti in lobby
    private static final int MAX_CONNECTIONS = 128;
    /** Failed LOGIN/REGISTER attempts allowed before the socket is dropped. */
    private static final int MAX_AUTH_ATTEMPTS = 5;
    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final String NAME_PATTERN = "^[A-Za-z0-9_-]{3,24}$";
    private static final String ROOM_NAME_PATTERN = "^[A-Za-z0-9 _-]{3,32}$";

    /** Shared for guest nicknames; one instance per call was wasteful. */
    private static final SecureRandom NICKNAME_RANDOM = new SecureRandom();

    public static void main(String[] args) {
        Server server = new Server(Server.DEFAULT_PORT, "Main Chess Server");
        System.out.println("==================================================");
        System.out.println("  " + server.getServerName() + " (Port " + server.getPort() + ")");
        System.out.println("==================================================");

        Semaphore connectionSemaphore = new Semaphore(MAX_CONNECTIONS);
        ExecutorService threadPool = new ThreadPoolExecutor(
                THREADS,
                THREADS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONNECTIONS),
                new ThreadPoolExecutor.AbortPolicy());

        boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("chess.server.tls", "false"));

        try (ServerSocket serverSocket = createServerSocket(server.getPort(), tlsEnabled)) {
            System.out.println("[ServerMain] Ready and listening for connections on port " + server.getPort() + "...");
            if (!tlsEnabled) {
                System.out.println("[ServerMain] WARNING: TLS disabled. Credentials are sent in cleartext.");
            }

            while (true) {
                Socket clientSocket = serverSocket.accept();
                if (!connectionSemaphore.tryAcquire()) {
                    rejectConnection(clientSocket, "ERROR:Server busy. Try again later.");
                    continue;
                }

                try {
                    threadPool.submit(() -> {
                        try {
                            handleInitialClient(clientSocket, server);
                        } finally {
                            connectionSemaphore.release();
                        }
                    });
                } catch (RuntimeException re) {
                    connectionSemaphore.release();
                    rejectConnection(clientSocket, "ERROR:Server overloaded. Try again later.");
                }
            }
        } catch (IOException e) {
            System.err.println("[ServerMain] Fatal server error: " + e.getMessage());
        }
    }

    private static ServerSocket createServerSocket(int port, boolean tlsEnabled) throws IOException {
        ServerSocketFactory factory = tlsEnabled
                ? SSLServerSocketFactory.getDefault()
                : ServerSocketFactory.getDefault();
        ServerSocket socket = factory.createServerSocket(port);
        // Survive a restart. Without SO_REUSEADDR a redeploy can fail to bind
        // while the previous socket lingers in TIME_WAIT, which reads as a
        // crash-loop cause rather than the transient state it is.
        socket.setReuseAddress(true);
        return socket;
    }

    private static void rejectConnection(Socket socket, String message) {
        try (Socket s = socket;
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
            out.println(message);
        } catch (IOException ioe) {
            System.err.println("[ServerMain] Failed to reject connection cleanly: " + ioe.getMessage());
        }
    }

    private static void handleInitialClient(Socket socket, Server server) {
        boolean handoffToRoomHandler = false;
        Socket managedSocket = socket;
        try {
            // The client already sets this; without the matching flag on the
            // server side, Nagle delayed small writes (the board frame after a
            // move is ~78 bytes) while the reader waited, adding up to 200ms of
            // pure delay to every move in a game.
            managedSocket.setTcpNoDelay(true);
            managedSocket.setSoTimeout(AUTH_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(managedSocket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(managedSocket.getOutputStream()), true);

            out.println("WELCOME:" + server.getServerName());

            String currentUser = null;
            boolean authenticated = false;
            int failedAuthAttempts = 0;

            while (!authenticated) {
                String line;
                try {
                    line = readLineWithLimit(in, MAX_MESSAGE_LENGTH);
                } catch (LineTooLongException tooLong) {
                    out.println("ERROR:Message too long (max " + MAX_MESSAGE_LENGTH + " bytes)");
                    continue;
                }
                if (line == null) return;

                if (line.startsWith("LOGIN:") || line.startsWith("REGISTER:")) {
                    // Brute-force protection. PBKDF2 at 120k iterations is
                    // deliberately expensive, so an unlimited retry loop is a
                    // denial-of-service lever as much as a credential-guessing
                    // one: a single socket could pin a core guessing forever.
                    if (failedAuthAttempts >= MAX_AUTH_ATTEMPTS) {
                        out.println("AUTH_ERROR:Too many failed attempts. Connection closing.");
                        System.out.println("[ServerMain] Closing connection after "
                                + MAX_AUTH_ATTEMPTS + " failed auth attempts");
                        return;
                    }
                }

                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && validUsername(parts[1]) && server.getAccountHandler().login(parts[1], parts[2])) {
                        currentUser = parts[1].trim();
                        authenticated = true;
                        out.println("AUTH_OK:Logged in as " + currentUser);
                    } else {
                        failedAuthAttempts++;
                        out.println("AUTH_ERROR:Invalid username or password");
                    }
                } else if (line.startsWith("REGISTER:")) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && validUsername(parts[1]) && server.getAccountHandler().register(parts[1], parts[2])) {
                        currentUser = parts[1].trim();
                        authenticated = true;
                        out.println("AUTH_OK:Account created and logged in as " + currentUser);
                    } else {
                        failedAuthAttempts++;
                        out.println("AUTH_ERROR:Registration failed");
                    }
                } else if (line.startsWith("GUEST:")) {
                    String nick = line.length() > 6 ? line.substring(6).trim() : "";
                    if (nick.isEmpty()) {
                        nick = "Guest_" + (NICKNAME_RANDOM.nextInt(9000) + 1000);
                    }
                    if (!validUsername(nick)) {
                        out.println("AUTH_ERROR:Invalid guest nickname");
                        continue;
                    }
                    currentUser = nick;
                    authenticated = true;
                    out.println("AUTH_OK:Joined as guest " + currentUser);
                } else if (line.startsWith("DELETE_ACCOUNT:")) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && validUsername(parts[1]) && server.getAccountHandler().deleteAccount(parts[1], parts[2])) {
                        out.println("INFO:Account deleted successfully");
                    } else {
                        out.println("ERROR:Failed to delete account");
                    }
                } else {
                    out.println("ERROR:Please authenticate first (LOGIN, REGISTER, or GUEST)");
                }
            }

            managedSocket.setSoTimeout(LOBBY_TIMEOUT_MS);
            while (true) {
                String line;
                try {
                    line = readLineWithLimit(in, MAX_MESSAGE_LENGTH);
                } catch (LineTooLongException tooLong) {
                    out.println("ERROR:Message too long (max " + MAX_MESSAGE_LENGTH + " bytes)");
                    continue;
                }
                if (line == null) return;

                if ("LIST_ROOMS".equals(line)) {
                    List<ServerRoomHandler.Room> rooms = server.getRoomHandler().getPublicRooms();
                    StringBuilder sb = new StringBuilder("ROOMS_LIST:" + rooms.size());
                    for (ServerRoomHandler.Room room : rooms) {
                        sb.append(";")
                                .append(room.getCode()).append("|")
                                .append(room.getName()).append("|")
                                .append(room.getHostUsername());
                    }
                    out.println(sb.toString());

                } else if (line.startsWith("CREATE_ROOM:")) {
                    String[] parts = line.split(":", 3);
                    String roomName = parts.length > 1 ? parts[1].trim() : "Chess Match";
                    boolean isPrivate = parts.length > 2 && Boolean.parseBoolean(parts[2]);

                    if (!validRoomName(roomName)) {
                        out.println("ERROR:Invalid room name");
                        continue;
                    }

                    ServerRoomHandler.Room room = server.getRoomHandler().createRoom(roomName, isPrivate, managedSocket, currentUser);
                    ConnectionHandler ch = new ConnectionHandler(room, server.getRoomHandler());
                    room.setConnectionHandler(ch);

                    handoffToRoomHandler = true;
                    new Thread(ch, "room-" + room.getCode()).start();
                    return;

                } else if (line.startsWith("JOIN_ROOM:")) {
                    String code = line.substring(10).trim().toUpperCase(Locale.ROOT);
                    if (!code.matches("^[A-Z2-9]{6}$")) {
                        out.println("JOIN_ERROR:Invalid room code format");
                        continue;
                    }

                    ServerRoomHandler.Room room = server.getRoomHandler().getRoomByCode(code);
                    if (room == null) {
                        out.println("JOIN_ERROR:Room not found");
                        continue;
                    }

                    if (!room.trySetGuest(managedSocket, currentUser)) {
                        out.println("JOIN_ERROR:Room is already full or finished");
                        continue;
                    }

                    ConnectionHandler ch = room.getConnectionHandler();
                    if (ch == null || !ch.attachGuest(managedSocket, currentUser)) {
                        room.rollbackGuestJoin();
                        out.println("JOIN_ERROR:Unable to join room");
                        continue;
                    }

                    out.println("JOIN_OK:Joined room " + room.getName());
                    handoffToRoomHandler = true;
                    return;

                } else if ("PING".equals(line)) {
                    out.println("PONG");
                } else {
                    out.println("ERROR:Unknown command");
                }
            }
        } catch (SocketTimeoutException e) {
            System.err.println("[ServerMain] Client timed out during negotiation: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[ServerMain] Client disconnected during negotiation: " + e.getMessage());
        } finally {
            if (!handoffToRoomHandler) {
                try {
                    managedSocket.close();
                } catch (IOException e) {
                    System.err.println("[ServerMain] Failed to close client socket: " + e.getMessage());
                }
            }
        }
    }

    /**
     * A line that exceeded {@link #MAX_MESSAGE_LENGTH}. See
     * {@link ConnectionHandler.LineTooLongException} for why this is not a
     * generic {@link IOException}.
     */
    private static final class LineTooLongException extends IOException {
        LineTooLongException(int maxLen) {
            super("Input line exceeds " + maxLen + " bytes");
        }
    }

    /**
     * Reads one line, refusing anything longer than {@code maxLen} bytes.
     *
     * <p>Overflow drains the rest of the line first, so the tail is never left
     * in the buffer to be re-read as a new command.
     *
     * @throws LineTooLongException if the line exceeded {@code maxLen}
     */
    private static String readLineWithLimit(BufferedReader in, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean overflow = false;
        while (true) {
            int ch = in.read();
            if (ch == -1) {
                if (overflow) throw new LineTooLongException(maxLen);
                if (sb.length() == 0) return null;
                return sb.toString();
            }
            if (ch == '\n') {
                if (overflow) throw new LineTooLongException(maxLen);
                return sb.toString();
            }
            if (ch != '\r') {
                if (sb.length() < maxLen) {
                    sb.append((char) ch);
                } else {
                    overflow = true;
                }
            }
        }
    }

    private static boolean validUsername(String username) {
        return username != null && username.trim().matches(NAME_PATTERN);
    }

    private static boolean validRoomName(String roomName) {
        return roomName != null && roomName.matches(ROOM_NAME_PATTERN)
                && roomName.indexOf(':') < 0
                && roomName.indexOf('|') < 0
                && roomName.indexOf('\n') < 0
                && roomName.indexOf('\r') < 0;
    }
}
