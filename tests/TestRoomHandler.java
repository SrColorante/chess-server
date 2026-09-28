package tests;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import server.ServerRoomHandler;
import server.ServerRoomHandler.Room;

public class TestRoomHandler {
    public static void run() throws Exception {
        System.out.println("-> Running TestRoomHandler...");
        ServerRoomHandler handler = new ServerRoomHandler();

        Room r1 = handler.createRoom("ChessClub", false, null, "player1");
        assert r1 != null : "Room creation failed";
        assert "ChessClub".equals(r1.getName()) : "Room name mismatch";
        assert !r1.isPrivate() : "Room should be public";
        assert r1.getCode() != null && r1.getCode().length() == 6 : "Room code must be 6 chars";

        Room r2 = handler.createRoom("SecretMatch", true, null, "player2");
        assert r2.isPrivate() : "Room should be private";

        List<Room> publicRooms = handler.getPublicRooms();
        assert publicRooms.size() == 1 : "Expected 1 public room, got " + publicRooms.size();
        assert "ChessClub".equals(publicRooms.get(0).getName()) : "Public room should be ChessClub";

        Room found = handler.getRoomByCode(r2.getCode());
        assert found != null : "Private room should be found by code";
        assert "SecretMatch".equals(found.getName()) : "Found room mismatch";

        try (ServerSocket ss = new ServerSocket(0);
             Socket clientA = new Socket("localhost", ss.getLocalPort());
             Socket serverA = ss.accept();
             Socket clientB = new Socket("localhost", ss.getLocalPort());
             Socket serverB = ss.accept()) {

            assert r1.trySetGuest(serverA, "guestA") : "First guest join should succeed";
            assert !r1.trySetGuest(serverB, "guestB") : "Second guest join should fail atomically";
            r1.rollbackGuestJoin();
            assert r1.getStatus() == ServerRoomHandler.RoomStatus.WAITING : "Rollback should restore WAITING";
        }

        handler.removeRoom(r1.getId());
        assert handler.getPublicRooms().isEmpty() : "Public rooms should be empty after removal";
        assert handler.getRoomByCode(r1.getCode()) == null : "Removed room should not be found";

        System.out.println("   [✓] TestRoomHandler PASSED");
    }
}
