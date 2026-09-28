package tests;

import server.ServerRoomHandler;
import server.ServerRoomHandler.Room;
import java.util.List;

public class TestRoomHandler {
    public static void run() {
        System.out.println("-> Running TestRoomHandler...");
        ServerRoomHandler handler = new ServerRoomHandler();

        // 1. Create public room
        Room r1 = handler.createRoom("ChessClub", false, null, "player1");
        assert r1 != null : "Room creation failed";
        assert "ChessClub".equals(r1.getName()) : "Room name mismatch";
        assert !r1.isPrivate() : "Room should be public";
        assert r1.getCode() != null && !r1.getCode().isEmpty() : "Room must have code";

        // 2. Create private room
        Room r2 = handler.createRoom("SecretMatch", true, null, "player2");
        assert r2.isPrivate() : "Room should be private";

        // 3. Public rooms list should contain only r1, not r2
        List<Room> publicRooms = handler.getPublicRooms();
        assert publicRooms.size() == 1 : "Expected 1 public room, got " + publicRooms.size();
        assert "ChessClub".equals(publicRooms.get(0).getName()) : "Public room should be ChessClub";

        // 4. Find room by code
        Room found = handler.getRoomByCode(r2.getCode());
        assert found != null : "Private room should be found by code";
        assert "SecretMatch".equals(found.getName()) : "Found room mismatch";

        // 5. Remove room
        handler.removeRoom(r1.getId());
        assert handler.getPublicRooms().isEmpty() : "Public rooms should be empty after removal";
        assert handler.getRoomByCode(r1.getCode()) == null : "Removed room should not be found";

        System.out.println("   [✓] TestRoomHandler PASSED");
    }
}
