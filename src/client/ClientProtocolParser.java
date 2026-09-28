package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClientProtocolParser {

    public static final class RoomCreated {
        private final String roomName;
        private final String roomCode;
        private final boolean isPrivate;

        public RoomCreated(String roomName, String roomCode, boolean isPrivate) {
            this.roomName = roomName;
            this.roomCode = roomCode;
            this.isPrivate = isPrivate;
        }

        public String getRoomName() { return roomName; }
        public String getRoomCode() { return roomCode; }
        public boolean isPrivate() { return isPrivate; }
    }

    public static final class PublicRoom {
        private final String code;
        private final String name;
        private final String host;

        public PublicRoom(String code, String name, String host) {
            this.code = code;
            this.name = name;
            this.host = host;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getHost() { return host; }
    }

    public static RoomCreated parseRoomCreated(String line) {
        if (line == null || !line.startsWith("ROOM_CREATED:")) {
            throw new IllegalArgumentException("Invalid ROOM_CREATED message");
        }
        String payload = line.substring("ROOM_CREATED:".length());
        int lastColon = payload.lastIndexOf(':');
        int secondLastColon = payload.lastIndexOf(':', lastColon - 1);
        if (lastColon <= 0 || secondLastColon <= 0) {
            throw new IllegalArgumentException("Malformed ROOM_CREATED payload");
        }

        String roomName = payload.substring(0, secondLastColon).trim();
        String roomCode = payload.substring(secondLastColon + 1, lastColon).trim();
        String privateToken = payload.substring(lastColon + 1).trim();

        if (roomName.isEmpty()) throw new IllegalArgumentException("Room name is empty");
        if (!roomCode.matches("[A-Z0-9]{6}")) throw new IllegalArgumentException("Invalid room code");
        if (!"true".equalsIgnoreCase(privateToken) && !"false".equalsIgnoreCase(privateToken)) {
            throw new IllegalArgumentException("Invalid room privacy value");
        }

        return new RoomCreated(roomName, roomCode, Boolean.parseBoolean(privateToken));
    }

    public static List<PublicRoom> parseRoomsList(String line) {
        if (line == null || !line.startsWith("ROOMS_LIST:")) {
            throw new IllegalArgumentException("Invalid ROOMS_LIST message");
        }

        String payload = line.substring("ROOMS_LIST:".length());
        String[] entries = payload.split(";", -1);
        if (entries.length == 0) throw new IllegalArgumentException("Malformed ROOMS_LIST payload");

        int declaredCount;
        try {
            declaredCount = Integer.parseInt(entries[0]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid ROOMS_LIST count");
        }

        if (declaredCount < 0) throw new IllegalArgumentException("Negative ROOMS_LIST count");

        List<PublicRoom> rooms = new ArrayList<>();
        for (int i = 1; i < entries.length; i++) {
            String entry = entries[i].trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed room entry");
            }
            String code = parts[0].trim();
            String name = parts[1].trim();
            String host = parts[2].trim();
            if (!code.matches("[A-Z0-9]{6}")) throw new IllegalArgumentException("Invalid room code in list");
            if (name.isEmpty() || host.isEmpty()) throw new IllegalArgumentException("Room entry contains empty fields");
            rooms.add(new PublicRoom(code, name, host));
        }

        if (rooms.size() != declaredCount) {
            throw new IllegalArgumentException("ROOMS_LIST count mismatch");
        }

        return rooms;
    }

    public static String parseBoardBlock(String boardHeader, BufferedReader in) throws IOException {
        if (!"BOARD:".equals(boardHeader)) {
            throw new IllegalArgumentException("Expected BOARD header");
        }
        StringBuilder board = new StringBuilder();
        boolean foundTurn = false;
        String line;
        int lines = 0;

        while ((line = in.readLine()) != null) {
            lines++;
            if (lines > 64) throw new IllegalArgumentException("BOARD payload too large");
            board.append(line).append('\n');
            if (line.startsWith("Current Turn:")) {
                String turn = line.substring("Current Turn:".length()).trim();
                if (!"WHITE".equals(turn) && !"BLACK".equals(turn)) {
                    throw new IllegalArgumentException("Invalid turn marker");
                }
                foundTurn = true;
                break;
            }
        }

        if (!foundTurn) throw new IllegalArgumentException("BOARD payload missing turn marker");
        return board.toString().trim();
    }
}
