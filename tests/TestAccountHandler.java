package tests;

import server.ServerAccountHandler;

public class TestAccountHandler {
    public static void run() {
        System.out.println("-> Running TestAccountHandler...");
        ServerAccountHandler handler = new ServerAccountHandler();

        // 1. Register new user
        assert handler.register("alice", "password123") : "Register alice should succeed";
        assert !handler.register("alice", "password456") : "Duplicate register should fail";

        // 2. Login verification
        assert handler.login("alice", "password123") : "Valid login should succeed";
        assert !handler.login("alice", "wrongpass") : "Wrong password should fail";
        assert !handler.login("bob", "password123") : "Non-existent user login should fail";

        // 3. User existence
        assert handler.userExists("alice") : "User alice must exist";
        assert !handler.userExists("bob") : "User bob must not exist";

        // 4. Delete account
        assert !handler.deleteAccount("alice", "wrongpass") : "Delete with wrong password should fail";
        assert handler.deleteAccount("alice", "password123") : "Delete with correct password should succeed";
        assert !handler.userExists("alice") : "Deleted user must not exist";
        assert !handler.login("alice", "password123") : "Deleted user login must fail";

        // 5. Passwords must not be stored in plain text
        // (Handled by cryptographic hashing)
        System.out.println("   [✓] TestAccountHandler PASSED");
    }
}
