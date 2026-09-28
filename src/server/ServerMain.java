package server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ServerMain binds to port 6700, accepts incoming connections,
 * processes authentication / room selection, and delegates rooms to ConnectionHandler.
 */
public class ServerMain {

    public static void main(String[] args) {
        Server server = new Server(Server.DEFAULT_PORT, "Main Chess Server");
        System.out.println("==================================================");
        System.out.println("  " + server.getServerName() + " (Port " + server.getPort() + ")");
        System.out.println("==================================================");

        ExecutorService threadPool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(server.getPort())) {
            System.out.println("[ServerMain] Ready and listening for connections on port " + server.getPort() + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[ServerMain] Accepted connection from " + clientSocket.getRemoteSocketAddress());
                threadPool.submit(() -> handleInitialClient(clientSocket, server));
            }
        } catch (IOException e) {
            System.err.println("[ServerMain] Fatal server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleInitialClient(Socket socket, Server server) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            out.println("WELCOME:" + server.getServerName());

            String currentUser = null;
            boolean authenticated = false;

            // Authentication Loop
            while (!authenticated) {
                String line = in.readLine();
                if (line == null) return;

                if (line.startsWith("LOGIN:")) {
                    // LOGIN:username:password
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && server.getAccountHandler().login(parts[1], parts[2])) {
                        currentUser = parts[1];
                        authenticated = true;
                        out.println("AUTH_OK:Logged in as " + currentUser);
                    } else {
                        out.println("AUTH_ERROR:Invalid username or password");
                    }
                } else if (line.startsWith("REGISTER:")) {
                    // REGISTER:username:password
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && server.getAccountHandler().register(parts[1], parts[2])) {
                        currentUser = parts[1];
                        authenticated = true;
                        out.println("AUTH_OK:Account created and logged in as " + currentUser);
                    } else {
                        out.println("AUTH_ERROR:Registration failed (username taken or empty)");
                    }
                } else if (line.startsWith("GUEST:")) {
                    // GUEST:nickname or random
                    String nick = line.length() > 6 ? line.substring(6).trim() : "";
                    if (nick.isEmpty()) nick = "Guest_" + (int)(Math.random() * 9000 + 1000);
                    currentUser = nick;
                    authenticated = true;
                    out.println("AUTH_OK:Joined as guest " + currentUser);
                } else if (line.startsWith("DELETE_ACCOUNT:")) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3 && server.getAccountHandler().deleteAccount(parts[1], parts[2])) {
                        out.println("INFO:Account deleted successfully");
                    } else {
                        out.println("ERROR:Failed to delete account");
                    }
                } else {
                    out.println("ERROR:Please authenticate first (LOGIN, REGISTER, or GUEST)");
                }
            }

            // Room Navigation Loop
            while (true) {
                String line = in.readLine();
                if (line == null) return;

                if (line.equals("LIST_ROOMS")) {
                    List<ServerRoomHandler.Room> rooms = server.getRoomHandler().getPublicRooms();
                    StringBuilder sb = new StringBuilder("ROOMS_LIST:" + rooms.size());
                    for (ServerRoomHandler.Room r : rooms) {
                        sb.append(";").append(r.getCode()).append("|").append(r.getName()).append("|").append(r.getHostUsername());
                    }
                    out.println(sb.toString());

                } else if (line.startsWith("CREATE_ROOM:")) {
                    // CREATE_ROOM:RoomName:isPrivate
                    String[] parts = line.split(":", 3);
                    String roomName = parts.length > 1 ? parts[1] : "Chess Match";
                    boolean isPrivate = parts.length > 2 && Boolean.parseBoolean(parts[2]);

                    ServerRoomHandler.Room room = server.getRoomHandler().createRoom(roomName, isPrivate, socket, currentUser);
                    ConnectionHandler ch = new ConnectionHandler(room, server.getRoomHandler());
                    room.setConnectionHandler(ch);

                    // Hand over socket control entirely to ConnectionHandler thread
                    new Thread(ch).start();
                    return; // Exit initial router loop

                } else if (line.startsWith("JOIN_ROOM:")) {
                    // JOIN_ROOM:<code>
                    String code = line.substring(10).trim();
                    ServerRoomHandler.Room room = server.getRoomHandler().getRoomByCode(code);

                    if (room == null) {
                        out.println("JOIN_ERROR:Room not found with code: " + code);
                    } else if (room.getStatus() != ServerRoomHandler.RoomStatus.WAITING) {
                        out.println("JOIN_ERROR:Room is already full or finished");
                    } else {
                        out.println("JOIN_OK:Joined room " + room.getName());
                        room.getConnectionHandler().setGuest(socket, currentUser);
                        return; // Exit initial router loop
                    }
                } else if (line.equals("PING")) {
                    out.println("PONG");
                }
            }

        } catch (IOException e) {
            System.err.println("[ServerMain] Client disconnected during negotiation: " + e.getMessage());
        }
    }
}
