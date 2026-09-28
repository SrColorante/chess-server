package server;

import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerRoomHandler manages active game rooms: public listings,
 * private room code lookups, creation, and lifecycle termination.
 */
public class ServerRoomHandler {

    public enum RoomStatus {
        WAITING,
        PLAYING,
        FINISHED
    }

    public static class Room {
        private final String id;
        private final String name;
        private final String code;
        private final boolean isPrivate;
        private final String hostUsername;
        private final Socket hostSocket;
        private volatile Socket guestSocket;
        private volatile String guestUsername;
        private volatile RoomStatus status;
        private volatile ConnectionHandler connectionHandler;

        public Room(String id, String name, String code, boolean isPrivate, Socket hostSocket, String hostUsername) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.isPrivate = isPrivate;
            this.hostSocket = hostSocket;
            this.hostUsername = hostUsername;
            this.status = RoomStatus.WAITING;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getCode() { return code; }
        public boolean isPrivate() { return isPrivate; }
        public String getHostUsername() { return hostUsername; }
        public Socket getHostSocket() { return hostSocket; }
        public Socket getGuestSocket() { return guestSocket; }
        public String getGuestUsername() { return guestUsername; }
        public RoomStatus getStatus() { return status; }
        public ConnectionHandler getConnectionHandler() { return connectionHandler; }

        public synchronized boolean trySetGuest(Socket socket, String username) {
            if (status != RoomStatus.WAITING || guestSocket != null || socket == null || username == null) {
                return false;
            }
            guestSocket = socket;
            guestUsername = username;
            status = RoomStatus.PLAYING;
            return true;
        }

        public synchronized void rollbackGuestJoin() {
            guestSocket = null;
            guestUsername = null;
            if (status != RoomStatus.FINISHED) {
                status = RoomStatus.WAITING;
            }
        }

        public synchronized void setStatus(RoomStatus status) {
            this.status = status;
        }

        public void setConnectionHandler(ConnectionHandler handler) {
            this.connectionHandler = handler;
        }
    }

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();
    private final Map<String, Room> roomsByCode = new ConcurrentHashMap<>();

    public synchronized Room createRoom(String name, boolean isPrivate, Socket hostSocket, String hostUser) {
        String id = UUID.randomUUID().toString();
        String code = generateUniqueCode();
        Room room = new Room(id, name, code, isPrivate, hostSocket, hostUser);
        roomsById.put(id, room);
        roomsByCode.put(code.toUpperCase(), room);
        return room;
    }

    public List<Room> getPublicRooms() {
        List<Room> result = new ArrayList<>();
        for (Room room : roomsById.values()) {
            if (!room.isPrivate() && room.getStatus() == RoomStatus.WAITING) {
                result.add(room);
            }
        }
        return result;
    }

    public Room getRoomByCode(String code) {
        if (code == null) return null;
        return roomsByCode.get(code.trim().toUpperCase());
    }

    public Room getRoomById(String id) {
        if (id == null) return null;
        return roomsById.get(id);
    }

    public synchronized void removeRoom(String id) {
        Room room = roomsById.remove(id);
        if (room != null) {
            roomsByCode.remove(room.getCode().toUpperCase());
            room.setStatus(RoomStatus.FINISHED);
        }
    }

    private String generateUniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(secureRandom.nextInt(CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (roomsByCode.containsKey(code));
        return code;
    }
}
