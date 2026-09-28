package server;

/**
 * Server describes the configuration, state, and central handlers of the chess backend.
 */
public class Server {
    public static final int DEFAULT_PORT = 6700;

    private final int port;
    private final String serverName;
    private final ServerAccountHandler accountHandler;
    private final ServerRoomHandler roomHandler;

    public Server() {
        this(DEFAULT_PORT, "Antigravity Chess Server");
    }

    public Server(int port, String serverName) {
        this.port = port;
        this.serverName = serverName;
        this.accountHandler = new ServerAccountHandler();
        this.roomHandler = new ServerRoomHandler();
    }

    public int getPort() { return port; }
    public String getServerName() { return serverName; }
    public ServerAccountHandler getAccountHandler() { return accountHandler; }
    public ServerRoomHandler getRoomHandler() { return roomHandler; }

    @Override
    public String toString() {
        return String.format("Server[Name='%s', Port=%d, Running=true]", serverName, port);
    }
}
