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
 * Full match over real sockets, authenticating as guests so it runs anywhere.
 *
 * <p>{@link TestNetworkIntegration} covers the account-backed path but needs
 * PostgreSQL, so on a machine without a database the whole network layer went
 * untested. This one exercises the part that actually matters for a match:
 * room creation, joining, the board frame, a legal move, a refused illegal
 * move, and the game-over broadcast to both sides.
 */
public class TestMatchFlow {

    public static void run() throws Exception {
        System.out.println("-> Running TestMatchFlow (guest match over real sockets)...");

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            // One shared server: each client must hit the same room registry.
            Server server = new Server(port, "Test Match Server");
            ExecutorService pool = Executors.newFixedThreadPool(8);

            Thread acceptor = new Thread(() -> {
                try {
                    while (!serverSocket.isClosed()) {
                        Socket s = serverSocket.accept();
                        pool.submit(() -> dispatch(s, server));
                    }
                } catch (IOException ignored) {
                    // server closed by the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            runFullMatch(port);

            serverSocket.close();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.println("   [✓] TestMatchFlow PASSED");
    }

    private static void dispatch(Socket s, Server server) {
        try {
            Method m = ServerMain.class.getDeclaredMethod("handleInitialClient", Socket.class, Server.class);
            m.setAccessible(true);
            m.invoke(null, s, server);
        } catch (Exception e) {
            System.err.println("TestMatchFlow dispatch error: " + e.getMessage());
        }
    }

    private static void runFullMatch(int port) throws Exception {
        Socket host = new Socket("localhost", port);
        host.setSoTimeout(10_000);
        BufferedReader hostIn = new BufferedReader(new InputStreamReader(host.getInputStream()));
        PrintWriter hostOut = new PrintWriter(new OutputStreamWriter(host.getOutputStream()), true);

        assert readUntil(hostIn, "WELCOME:") != null : "Expected welcome";
        hostOut.println("GUEST:HostPlayer");
        assert readUntil(hostIn, "AUTH_OK:") != null : "Host guest login failed";

        hostOut.println("CREATE_ROOM:SmokeRoom:false");
        String created = readUntil(hostIn, "ROOM_CREATED:");
        assert created != null : "Room creation failed: " + created;
        String code = created.split(":")[2];
        assert code.length() == 6 : "Room code must be 6 chars, was " + code;
        readUntil(hostIn, "INFO:");

        // The host configures the match before anybody joins.
        hostOut.println("SET_MODE:BLITZ");
        hostOut.println("SET_COLOR:WHITE");
        hostOut.println("SET_TIMER:300");

        Socket guest = new Socket("localhost", port);
        guest.setSoTimeout(10_000);
        BufferedReader guestIn = new BufferedReader(new InputStreamReader(guest.getInputStream()));
        PrintWriter guestOut = new PrintWriter(new OutputStreamWriter(guest.getOutputStream()), true);
        assert readUntil(guestIn, "WELCOME:") != null : "Guest welcome";
        guestOut.println("GUEST:GuestPlayer");
        assert readUntil(guestIn, "AUTH_OK:") != null : "Guest login failed";
        guestOut.println("JOIN_ROOM:" + code);
        assert readUntil(guestIn, "JOIN_OK:") != null : "Guest could not join";

        assert readUntil(hostIn, "MATCH_START") != null : "Host never started the match";
        assert readUntil(guestIn, "MATCH_START") != null : "Guest never started the match";

        String hostColor = readUntil(hostIn, "ASSIGNED_COLOR:");
        String guestColor = readUntil(guestIn, "ASSIGNED_COLOR:");
        assert hostColor != null && hostColor.endsWith("WHITE")
                : "Host asked for white, got: " + hostColor;
        assert guestColor != null && guestColor.endsWith("BLACK")
                : "Guest must get black, got: " + guestColor;

        // The board frame is a single line of exactly 78 characters: that shape
        // is what stopped the old multi-line format from interleaving.
        String hostBoard = readUntil(hostIn, "BOARD:");
        String guestBoard = readUntil(guestIn, "BOARD:");
        assert hostBoard != null && hostBoard.length() == 78
                : "Board frame must be one 78-char line, was: " + hostBoard;
        assert guestBoard != null : "Guest never received a board";
        assert hostBoard.startsWith("BOARD:rnbqkbnr/")
                : "Board must start from the opening position: " + hostBoard;
        assert hostBoard.endsWith("w") : "White must move first: " + hostBoard;

        // A legal move must round-trip to both players and flip the turn.
        hostOut.println("MOVE:e2:e4");
        assert readUntil(hostIn, "MOVE_OK") != null : "Host move was not accepted";
        assert readUntil(guestIn, "MOVE_OK") != null : "Guest did not see the move";
        String afterMove = readUntil(hostIn, "BOARD:");
        assert afterMove != null && afterMove.endsWith("b")
                : "Turn must flip to black: " + afterMove;
        // The compact frame joins ranks with '/', so rank 4 is the fifth group.
        String[] ranks = afterMove.substring(6).split("/", -1);
        assert "....P...".equals(ranks[4])
                : "The pawn must be on e4, rank 4 was: " + ranks[4];

        // An illegal move is refused without breaking the session.
        hostOut.println("MOVE:e7:e5");
        String refusal = readUntil(hostIn, "MOVE_ERROR");
        assert refusal != null : "An illegal move must be refused";

        // The session is still alive, so Black can reply.
        guestOut.println("MOVE:e7:e5");
        assert readUntil(guestIn, "MOVE_OK") != null : "Guest move was not accepted";
        assert readUntil(hostIn, "MOVE_OK") != null : "Host did not see the reply";

        // Resigning ends the match for both sides.
        hostOut.println("RESIGN");
        String hostOver = readUntil(hostIn, "GAME_OVER");
        String guestOver = readUntil(guestIn, "GAME_OVER");
        assert hostOver != null && guestOver != null : "Both players must be told the game ended";
        assert hostOver.contains("BLACK") : "Black wins when white resigns: " + hostOver;

        host.close();
        guest.close();
    }

    private static String readUntil(BufferedReader in, String token) throws IOException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String line = in.readLine();
                if (line == null) return null;
                if (line.startsWith(token) || line.contains(token)) return line;
            } catch (SocketTimeoutException ste) {
                return null;
            }
        }
        return null;
    }
}
