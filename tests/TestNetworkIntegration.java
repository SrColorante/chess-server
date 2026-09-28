package tests;

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
import server.Server;
import server.ServerMain;

public class TestNetworkIntegration {

    public static void run() throws Exception {
        System.out.println("-> Running TestNetworkIntegration (End-to-End Server-Client Flow)...");

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int testPort = serverSocket.getLocalPort();
            Server server = new Server(testPort, "Test Integration Server");
            ExecutorService serverPool = Executors.newFixedThreadPool(8);

            Thread serverThread = new Thread(() -> {
                try {
                    while (!serverSocket.isClosed()) {
                        Socket s = serverSocket.accept();
                        serverPool.submit(() -> {
                            try {
                                Method m = ServerMain.class.getDeclaredMethod("handleInitialClient", Socket.class, Server.class);
                                m.setAccessible(true);
                                m.invoke(null, s, server);
                            } catch (Exception e) {
                                System.err.println("Server test dispatch error: " + e.getMessage());
                            }
                        });
                    }
                } catch (IOException ignored) {
                    // server closed by test
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            runMainMatchFlow(testPort, server);
            runConcurrentJoinRace(testPort);
            runInputValidationFlow(testPort);

            serverSocket.close();
            serverPool.shutdownNow();
            serverPool.awaitTermination(5, TimeUnit.SECONDS);
        }

        System.out.println("   [✓] TestNetworkIntegration PASSED");
    }

    private static void runMainMatchFlow(int testPort, Server server) throws Exception {
        Socket c1 = new Socket("localhost", testPort);
        c1.setSoTimeout(4_000);
        BufferedReader in1 = new BufferedReader(new InputStreamReader(c1.getInputStream()));
        PrintWriter out1 = new PrintWriter(new OutputStreamWriter(c1.getOutputStream()), true);

        String w1 = in1.readLine();
        assert w1 != null && w1.startsWith("WELCOME:") : "Expected welcome message";

        out1.println("REGISTER:Alice:secretPass123");
        String auth1 = in1.readLine();
        assert auth1 != null && auth1.startsWith("AUTH_OK:") : "Alice registration failed: " + auth1;

        out1.println("CREATE_ROOM:AliceRoom:true");
        String roomCreated = in1.readLine();
        assert roomCreated != null && roomCreated.startsWith("ROOM_CREATED:") : "Room creation failed: " + roomCreated;
        String[] roomParts = roomCreated.split(":", 4);
        String roomCode = roomParts[2];
        in1.readLine();

        Socket c2 = new Socket("localhost", testPort);
        c2.setSoTimeout(4_000);
        BufferedReader in2 = new BufferedReader(new InputStreamReader(c2.getInputStream()));
        PrintWriter out2 = new PrintWriter(new OutputStreamWriter(c2.getOutputStream()), true);

        in2.readLine();

        out2.println("GUEST:BobGuest");
        String auth2 = in2.readLine();
        assert auth2 != null && auth2.startsWith("AUTH_OK:") : "Guest login failed";

        out2.println("JOIN_ROOM:" + roomCode);
        String joinResp = in2.readLine();
        assert joinResp != null && joinResp.startsWith("JOIN_OK:") : "Failed to join room: " + joinResp;

        String m1 = readUntilContains(in1, "MATCH_START", 4000);
        String m2 = readUntilContains(in2, "MATCH_START", 4000);
        assert m1 != null : "Host did not receive match start";
        assert m2 != null : "Guest did not receive match start";

        String col1 = readUntilContains(in1, "ASSIGNED_COLOR:", 4000);
        String col2 = readUntilContains(in2, "ASSIGNED_COLOR:", 4000);
        assert col1 != null && col2 != null : "Color assignment failed";

        readUntilContains(in1, "Current Turn:", 4000);
        readUntilContains(in2, "Current Turn:", 4000);

        out1.println("MOVE:e2:e4");
        assert readUntilContains(in1, "MOVE_OK", 4000) != null;
        assert readUntilContains(in2, "MOVE_OK", 4000) != null;

        out2.println("RESIGN");
        assert readUntilContains(in1, "GAME_OVER", 4000) != null;

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && server.getRoomHandler().getRoomByCode(roomCode) != null) {
            Thread.sleep(50);
        }
        assert server.getRoomHandler().getRoomByCode(roomCode) == null : "Room should be cleaned up after game over";

        c1.close();
        c2.close();
    }

    private static void runConcurrentJoinRace(int port) throws Exception {
        Socket host = new Socket("localhost", port);
        host.setSoTimeout(4_000);
        BufferedReader hostIn = new BufferedReader(new InputStreamReader(host.getInputStream()));
        PrintWriter hostOut = new PrintWriter(new OutputStreamWriter(host.getOutputStream()), true);
        hostIn.readLine();
        hostOut.println("GUEST:HostRace");
        assert hostIn.readLine().startsWith("AUTH_OK:");
        hostOut.println("CREATE_ROOM:RaceRoom:true");
        String created = hostIn.readLine();
        String roomCode = created.split(":", 4)[2];
        hostIn.readLine();

        Socket g1 = new Socket("localhost", port);
        Socket g2 = new Socket("localhost", port);
        g1.setSoTimeout(4_000);
        g2.setSoTimeout(4_000);

        BufferedReader in1 = new BufferedReader(new InputStreamReader(g1.getInputStream()));
        BufferedReader in2 = new BufferedReader(new InputStreamReader(g2.getInputStream()));
        PrintWriter out1 = new PrintWriter(new OutputStreamWriter(g1.getOutputStream()), true);
        PrintWriter out2 = new PrintWriter(new OutputStreamWriter(g2.getOutputStream()), true);

        in1.readLine();
        in2.readLine();
        out1.println("GUEST:JoinerOne");
        out2.println("GUEST:JoinerTwo");
        assert in1.readLine().startsWith("AUTH_OK:");
        assert in2.readLine().startsWith("AUTH_OK:");

        out1.println("JOIN_ROOM:" + roomCode);
        out2.println("JOIN_ROOM:" + roomCode);

        String r1 = in1.readLine();
        String r2 = in2.readLine();
        boolean firstOk = r1 != null && r1.startsWith("JOIN_OK:");
        boolean secondOk = r2 != null && r2.startsWith("JOIN_OK:");
        assert firstOk ^ secondOk : "Exactly one concurrent join must succeed";

        host.close();
        g1.close();
        g2.close();
    }

    private static void runInputValidationFlow(int port) throws Exception {
        Socket c = new Socket("localhost", port);
        c.setSoTimeout(4_000);
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(c.getOutputStream()), true);

        in.readLine();
        out.println("REGISTER:bad|name:password123");
        String r = in.readLine();
        assert r != null && r.startsWith("AUTH_ERROR:") : "Invalid username must be rejected";

        out.println("GUEST:okGuest");
        assert in.readLine().startsWith("AUTH_OK:");

        out.println("CREATE_ROOM:Bad|Room:true");
        String roomResp = in.readLine();
        assert roomResp != null && roomResp.startsWith("ERROR:Invalid room name") : "Room with delimiters must fail";

        out.println("JOIN_ROOM:BAD");
        String joinResp = in.readLine();
        assert joinResp != null && joinResp.startsWith("JOIN_ERROR:Invalid room code format") : "Invalid code format must fail";

        c.close();
    }

    private static String readUntilContains(BufferedReader in, String target, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String line = in.readLine();
                if (line == null) return null;
                if (line.contains(target)) return line;
            } catch (SocketTimeoutException ste) {
                // continue until deadline
            }
        }
        return null;
    }
}
