package tests;

/** Runs one test class by name, bypassing the fail-fast TestRunner. */
public class RunOne {
    public static void main(String[] args) throws Exception {
        String which = args[0];
        System.out.println("=== " + which + " ===");
        try {
            switch (which) {
                case "chess": TestChessGame.run(); break;
                case "match": TestMatchFlow.run(); break;
                case "network": TestNetworkIntegration.run(); break;
                case "client": TestClientComponents.run(); break;
                case "room": TestRoomHandler.run(); break;
                default: throw new IllegalArgumentException("unknown: " + which);
            }
            System.out.println(">>> " + which + ": PASS");
        } catch (Throwable t) {
            System.out.println(">>> " + which + ": FAIL -> " + t);
            Throwable c = t;
            while (c != null) {
                for (StackTraceElement e : c.getStackTrace()) {
                    if (e.getClassName().startsWith("tests") || e.getClassName().startsWith("game")
                            || e.getClassName().startsWith("server") || e.getClassName().startsWith("client")) {
                        System.out.println("    at " + e);
                    }
                }
                c = c.getCause();
            }
        }
    }
}
