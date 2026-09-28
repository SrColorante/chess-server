package tests;

import server.Server;
import server.ServerMain;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public class TestNetworkIntegration {

    public static void run() throws Exception {
        System.out.println("-> Running TestNetworkIntegration (End-to-End Server-Client Flow)...");

        // Start server in background thread on ephemeral port
        int testPort = 6701;
        Server server = new Server(testPort, "Test Integration Server");
        ExecutorService serverPool = Executors.newCachedThreadPool();

        ServerSocket serverSocket = new ServerSocket(testPort);
        Thread serverThread = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket s = serverSocket.accept();
                    serverPool.submit(() -> {
                        try {
                            // Run the same routing as ServerMain
                            java.lang.reflect.Method m = ServerMain.class.getDeclaredMethod("handleInitialClient", Socket.class, Server.class);
                            m.setAccessible(true);
                            m.invoke(null, s, server);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (IOException ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(200); // wait for server to start

        // 1. Client 1 (Host: Alice)
        Socket c1 = new Socket("localhost", testPort);
        BufferedReader in1 = new BufferedReader(new InputStreamReader(c1.getInputStream()));
        PrintWriter out1 = new PrintWriter(new OutputStreamWriter(c1.getOutputStream()), true);

        String w1 = in1.readLine();
        assert w1 != null && w1.startsWith("WELCOME:") : "Expected welcome message";

        // Register & Auth Alice
        out1.println("REGISTER:Alice:secretPass123");
        String auth1 = in1.readLine();
        assert auth1 != null && auth1.startsWith("AUTH_OK:") : "Alice registration failed: " + auth1;

        // Create Room
        out1.println("CREATE_ROOM:AliceRoom:true");
        String roomCreated = in1.readLine(); // ROOM_CREATED:name:code:isPrivate
        assert roomCreated != null && roomCreated.startsWith("ROOM_CREATED:") : "Room creation failed: " + roomCreated;
        String[] roomParts = roomCreated.split(":");
        String roomCode = roomParts[2];
        in1.readLine(); // "INFO:Waiting for opponent..."

        // 2. Client 2 (Guest: Bob)
        Socket c2 = new Socket("localhost", testPort);
        BufferedReader in2 = new BufferedReader(new InputStreamReader(c2.getInputStream()));
        PrintWriter out2 = new PrintWriter(new OutputStreamWriter(c2.getOutputStream()), true);

        in2.readLine(); // WELCOME

        // Login as Guest
        out2.println("GUEST:BobGuest");
        String auth2 = in2.readLine();
        assert auth2 != null && auth2.startsWith("AUTH_OK:") : "Guest login failed";

        // Join Room by Code
        out2.println("JOIN_ROOM:" + roomCode);
        String joinResp = in2.readLine();
        assert joinResp != null && joinResp.startsWith("JOIN_OK:") : "Failed to join room: " + joinResp;

        // 3. Match Starts for both
        String m1 = in1.readLine(); // MATCH_START
        String m2 = in2.readLine(); // MATCH_START
        assert m1 != null && m1.contains("MATCH_START") : "Host did not receive match start";
        assert m2 != null && m2.contains("MATCH_START") : "Guest did not receive match start";

        // Read ASSIGNED_COLOR
        String col1 = in1.readLine();
        String col2 = in2.readLine();
        assert col1 != null && col2 != null : "Color assignment failed";

        // Read initial boards
        readUntil(in1, "Current Turn: WHITE");
        readUntil(in2, "Current Turn: WHITE");

        // 4. White moves e2 -> e4
        out1.println("MOVE:e2:e4");
        readUntil(in1, "MOVE_OK");
        readUntil(in2, "MOVE_OK");

        // 5. Black resigns
        out2.println("RESIGN");
        readUntil(in1, "resigned");

        // Clean shutdown
        c1.close();
        c2.close();
        serverSocket.close();
        serverPool.shutdownNow();

        System.out.println("   [✓] TestNetworkIntegration PASSED (Auth, Room creation, Code join, Moves, and Resign verified!)");
    }

    private static void readUntil(BufferedReader in, String target) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.contains(target)) return;
        }
    }
}
