package tests;

public class TestRunner {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  RUNNING CHESS SERVER TDD TEST SUITE   ");
        System.out.println("========================================");
        try {
            TestAccountHandler.run();
            TestRoomHandler.run();
            TestChessGame.run();
            TestNetworkIntegration.run();
            System.out.println("\n[✓] ALL CONTRACT & LOGIC TESTS PASSED SUCCESSFULLY!");
            System.exit(0);
        } catch (AssertionError e) {
            System.err.println("\n[✗] TEST ASSERTION FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("\n[✗] UNEXPECTED EXCEPTION: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
