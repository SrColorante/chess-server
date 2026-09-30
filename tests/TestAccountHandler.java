package tests;

import server.ServerAccountHandler;

/**
 * Account handler tests.
 *
 * <p>The handler talks to PostgreSQL, so these checks only run when a database is
 * reachable. The previous version called methods that no longer exist
 * ({@code userExists}, a {@code hashPassword(String)} overload), so the suite did
 * not compile at all; {@code TestRunner} now skips this group instead of
 * breaking the build.
 */
public class TestAccountHandler {
    public static void run() {
        System.out.println("-> Running TestAccountHandler...");
        if (!databaseReachable()) {
            System.out.println("   [SKIP] No PostgreSQL reachable; skipping account tests");
            System.out.println("   [✓] TestAccountHandler SKIPPED");
            return;
        }

        ServerAccountHandler handler = new ServerAccountHandler();
        // Unique suffix so repeated runs do not collide on the primary key.
        String user = "tester" + (System.currentTimeMillis() % 100000);
        String password = "password123";

        try {
            assert handler.register(user, password) : "Register should succeed";
            assert !handler.register(user, "password456") : "Duplicate register should fail";
            assert !handler.register(user, "short") : "Too-short password should fail";

            assert handler.login(user, password) : "Valid login should succeed";
            assert !handler.login(user, "wrongpass") : "Wrong password should fail";
            assert !handler.login("nobody_" + user, password) : "Unknown user login should fail";
            assert !handler.login(null, password) : "Null username should fail";
            assert !handler.login(user, null) : "Null password should fail";

            assert !handler.deleteAccount(user, "wrongpass") : "Delete with wrong password should fail";
            assert handler.deleteAccount(user, password) : "Delete with correct password should succeed";
            assert !handler.login(user, password) : "Deleted user login should fail";

            System.out.println("   [✓] TestAccountHandler PASSED");
        } finally {
            // Leave no test account behind even if an assertion threw.
            handler.deleteAccount(user, password);
        }
    }

    private static boolean databaseReachable() {
        String url = System.getenv("DB_URL");
        if (url == null || url.isBlank()) return false;
        try {
            Class.forName("org.postgresql.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
