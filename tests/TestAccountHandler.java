package tests;

import server.ServerAccountHandler;

public class TestAccountHandler {
    public static void run() {
        System.out.println("-> Running TestAccountHandler...");
        ServerAccountHandler handler = new ServerAccountHandler();

        assert handler.register("alice", "password123") : "Register alice should succeed";
        assert !handler.register("alice", "password456") : "Duplicate register should fail";

        assert handler.login("alice", "password123") : "Valid login should succeed";
        assert !handler.login("alice", "wrongpass") : "Wrong password should fail";
        assert !handler.login("bob", "password123") : "Non-existent user login should fail";

        assert handler.userExists("alice") : "User alice must exist";
        assert !handler.userExists("bob") : "User bob must not exist";

        assert !handler.deleteAccount("alice", "wrongpass") : "Delete with wrong password should fail";
        assert handler.deleteAccount("alice", "password123") : "Delete with correct password should succeed";
        assert !handler.userExists("alice") : "Deleted user must not exist";
        assert !handler.login("alice", "password123") : "Deleted user login must fail";

        assert !handler.register("bad:name", "password123") : "Username with delimiters should fail";
        assert !handler.register("okname", "short") : "Too-short password should fail";
        assert !handler.register("xy", "password123") : "Too-short username should fail";

        String hash1 = ServerAccountHandler.hashPassword("samePassword123");
        String hash2 = ServerAccountHandler.hashPassword("samePassword123");
        assert !hash1.equals(hash2) : "Hashes should include random salt and differ per call";

        System.out.println("   [✓] TestAccountHandler PASSED");
    }
}
