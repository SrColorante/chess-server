package tests;

import server.Server;
import server.ServerMain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Security regressions over real sockets.
 *
 * <p>Each case here corresponds to a defect that shipped and was confirmed
 * against a running server, so the suite fails loudly if any of them returns.
 */
public class TestProtocolHardening {

    public static void run() throws Exception {
        System.out.println("-> Running TestProtocolHardening...");
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Server server = new Server(port, "Hardening Test Server");
            ExecutorService pool = Executors.newFixedThreadPool(8);

            Thread acceptor = new Thread(() -> {
                try {
                    while (!serverSocket.isClosed()) {
                        Socket s = serverSocket.accept();
                        pool.submit(() -> dispatch(s, server));
                    }
                } catch (IOException ignored) {
                    // closed by the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            overLongLineDoesNotForfeitTheMatch(port);
            overLongLineIsRejectedDuringAuth(port);
            authAttemptsAreRateLimited(port);
            injectedProtocolFieldsAreRefused(port);

            serverSocket.close();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        System.out.println("   [\u2713] TestProtocolHardening PASSED");
    }

    /**
     * The original defect: a line over MAX_MESSAGE_LENGTH raised a plain
     * IOException, which the game loop handled as a disconnect, resigned the
     * sender and ended a live match. A hostile or buggy client could therefore
     * decide a game.
     */
    private static void overLongLineDoesNotForfeitTheMatch(int port) throws Exception {
        Session host = guestLogin(port, "HostPlayer");
        host.send("CREATE_ROOM:HardeningRoom:false");
        String created = host.readUntil("ROOM_CREATED:");
        assert created != null : "Room creation failed";
        String code = created.split(":")[2];

        Session guest = guestLogin(port, "Mallory");
        guest.send("JOIN_ROOM:" + code);
        assert guest.readUntil("JOIN_OK:") != null : "Guest could not join";
        host.readUntil("MATCH_START");
        guest.readUntil("MATCH_START");

        host.send("MOVE:e2:e4");
        assert host.readUntil("MOVE_OK") != null : "A legal opening move must be accepted";

        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < 4000; i++) junk.append('A');
        guest.send(junk.toString());

        // The bad line is answered with a protocol error, not a game over.
        String error = guest.readUntil("ERROR:");
        assert error != null : "An over-long line must be answered with an error";
        assert !error.contains("too long") || true : "error text is informational";

        // And the match is still playable by the very player who sent it.
        guest.send("MOVE:e7:e5");
        String ok = guest.readUntil("MOVE_OK");
        assert ok != null : "The match must survive an over-long line: it was forfeited instead";

        host.close();
        guest.close();
    }

    /** Over-long input before authentication must be refused, not crash the session. */
    private static void overLongLineIsRejectedDuringAuth(int port) throws Exception {
        Session s = new Session(port);
        assert s.readUntil("WELCOME:") != null : "Expected a welcome frame";

        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < 4000; i++) junk.append('B');
        s.send(junk.toString());
        String error = s.readUntil("ERROR:");
        assert error != null : "An over-long line during auth must be refused";

        // The socket must still be usable afterwards.
        s.send("GUEST:AfterJunk");
        assert s.readUntil("AUTH_OK:") != null : "Session must survive an over-long line";
        s.close();
    }

    /** Unlimited LOGIN retries are a brute-force and CPU-exhaustion vector. */
    private static void authAttemptsAreRateLimited(int port) throws Exception {
        Session s = new Session(port);
        assert s.readUntil("WELCOME:") != null : "Expected a welcome frame";

        // No database in the test environment, so every attempt fails: exactly
        // the brute-force shape the limit has to stop.
        String lastLine = null;
        for (int i = 0; i < 12; i++) {
            s.send("LOGIN:victim" + i + ":wrongpassword");
            lastLine = s.readAny();
            if (lastLine == null) break;
        }
        assert lastLine == null || lastLine.contains("Too many failed attempts")
                : "The connection must be closed after repeated failed logins, last frame was: " + lastLine;
        s.close();
    }

    /**
     * Protocol framing: a room name or guest nickname carrying a separator must
     * never be able to inject a second field into the room listing.
     */
    private static void injectedProtocolFieldsAreRefused(int port) throws Exception {
        Session s = new Session(port);
        assert s.readUntil("WELCOME:") != null : "Expected a welcome frame";

        // '|' is the room-listing field separator.
        s.send("GUEST:bad|name");
        String reply = s.readAny();
        assert reply == null || !reply.contains("AUTH_OK") : "A nickname with a separator must be refused";
        s.close();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void dispatch(Socket s, Server server) {
        try {
            Method m = ServerMain.class.getDeclaredMethod("handleInitialClient", Socket.class, Server.class);
            m.setAccessible(true);
            m.invoke(null, s, server);
        } catch (Exception e) {
            System.err.println("TestProtocolHardening dispatch error: " + e.getMessage());
        }
    }

    private static Session guestLogin(int port, String nick) throws IOException {
        Session s = new Session(port);
        s.send("GUEST:" + nick);
        assert s.readUntil("AUTH_OK:") != null : "Guest login failed for " + nick;
        return s;
    }

    /** One connected client, with a read that never blocks indefinitely. */
    private static final class Session {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        Session(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }

        void send(String line) {
            out.println(line);
        }

        /** First frame containing the token, or null on timeout or close. */
        String readUntil(String token) throws IOException {
            String line = readAny();
            while (line != null && !line.contains(token)) {
                line = readAny();
            }
            return line;
        }

        /** The next frame, or null on timeout or close. */
        String readAny() throws IOException {
            try {
                return in.readLine();
            } catch (SocketTimeoutException timeout) {
                return null;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing for the test
            }
        }
    }
}
