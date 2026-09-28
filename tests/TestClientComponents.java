package tests;

import client.ClientConfig;
import client.ClientProtocolParser;
import client.LineListener;
import client.TerminalTheme;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TestClientComponents {
    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        System.out.println("-> Running TestClientComponents...");

        // host/port validation
        assert "localhost".equals(ClientConfig.validateHost(" localhost ")) : "Host trim failed";
        assert ClientConfig.validatePort("6700") == 6700 : "Port parse failed";
        assertThrows(() -> ClientConfig.validateHost("   "), "Empty host should fail");
        assertThrows(() -> ClientConfig.validatePort("70000"), "Out of range port should fail");

        // ROOM_CREATED parser with ':' in room name
        ClientProtocolParser.RoomCreated room = ClientProtocolParser.parseRoomCreated("ROOM_CREATED:Team:Alpha Room:ABC123:true");
        assert "Team:Alpha Room".equals(room.getRoomName()) : "Room name parse mismatch";
        assert "ABC123".equals(room.getRoomCode()) : "Room code parse mismatch";
        assert room.isPrivate() : "Room privacy parse mismatch";

        // ROOMS_LIST parser and edge cases
        List<ClientProtocolParser.PublicRoom> rooms = ClientProtocolParser.parseRoomsList(
                "ROOMS_LIST:2;ABC123|Rapid|Alice;QWE789|Blitz Night|Bob");
        assert rooms.size() == 2 : "Room list size mismatch";
        assert "QWE789".equals(rooms.get(1).getCode()) : "Second room code mismatch";
        assertThrows(() -> ClientProtocolParser.parseRoomsList("ROOMS_LIST:1;INVALID|NoHost"), "Malformed room entry should fail");
        assertThrows(() -> ClientProtocolParser.parseRoomsList("ROOMS_LIST:2;ABC123|OnlyOne|Alice"), "Count mismatch should fail");

        // BOARD parser
        String payload = "8 | r n b q k b n r\n"
                + "7 | p p p p p p p p\n"
                + "Current Turn: WHITE\n";
        BufferedReader boardReader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        String board = ClientProtocolParser.parseBoardBlock("BOARD:", boardReader);
        assert board.contains("Current Turn: WHITE") : "Board turn marker missing";

        // theme fallback no color/no unicode/no emoji
        TerminalTheme fallback = new TerminalTheme(false, false, false, true);
        String error = fallback.error("Failure");
        assert !error.contains("\u001B[") : "ANSI should be disabled";
        assert error.contains("ERR") : "ASCII fallback should include ERR";

        // listener shutdown determinism
        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream(pos);
        BufferedReader listenerReader = new BufferedReader(new InputStreamReader(pis, StandardCharsets.UTF_8));
        AtomicInteger counter = new AtomicInteger();

        LineListener listener = new LineListener(listenerReader, line -> counter.incrementAndGet(), null);
        listener.start("test-line-listener");
        pos.write("LINE1\n".getBytes(StandardCharsets.UTF_8));
        pos.flush();
        Thread.sleep(100);
        pos.close();
        listener.stop();
        boolean stopped = listener.awaitStop(1000);
        assert stopped : "Listener should stop gracefully";
        assert counter.get() >= 1 : "Listener should process at least one line";

        System.out.println("   [✓] TestClientComponents PASSED");
    }

    private static void assertThrows(Runnable r, String message) {
        boolean thrown = false;
        try {
            r.run();
        } catch (Exception expected) {
            thrown = true;
        }
        assert thrown : message;
    }
}
