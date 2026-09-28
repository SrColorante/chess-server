package server;

import java.net.Socket;
import java.util.*;
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
        private Socket hostSocket;
        private Socket guestSocket;
        private String guestUsername;
        private RoomStatus status;
        private ConnectionHandler connectionHandler;

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

        public void setGuest(Socket guestSocket, String guestUsername) {
            this.guestSocket = guestSocket;
            this.guestUsername = guestUsername;
            this.status = RoomStatus.PLAYING;
        }

        public void setStatus(RoomStatus status) { this.status = status; }
        public void setConnectionHandler(ConnectionHandler handler) { this.connectionHandler = handler; }
    }

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
        for (Room r : roomsById.values()) {
            if (!r.isPrivate() && r.getStatus() == RoomStatus.WAITING) {
                result.add(r);
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
        Room r = roomsById.remove(id);
        if (r != null) {
            roomsByCode.remove(r.getCode().toUpperCase());
        }
    }

    private String generateUniqueCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random random = new Random();
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            code = sb.toString();
        } while (roomsByCode.containsKey(code));
        return code;
    }
}
