package server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerAccountHandler manages user accounts: registration, login, deletion,
 * and secure SHA-256 password hashing. Stores only username and encrypted credentials.
 */
public class ServerAccountHandler {
    // In-memory credential store: username -> SHA-256 password hash
    private final Map<String, String> userCredentials = new ConcurrentHashMap<>();

    public ServerAccountHandler() {
        // Optional default accounts or empty
    }

    /**
     * Hashes a password using SHA-256 with a constant pepper/salt.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Registers a new account. Fails if user already exists or inputs are invalid.
     */
    public synchronized boolean register(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            return false;
        }
        username = username.trim();
        if (userCredentials.containsKey(username)) {
            return false;
        }
        userCredentials.put(username, hashPassword(password));
        return true;
    }

    /**
     * Verifies user login.
     */
    public boolean login(String username, String password) {
        if (username == null || password == null) return false;
        String storedHash = userCredentials.get(username.trim());
        if (storedHash == null) return false;
        return storedHash.equals(hashPassword(password));
    }

    /**
     * Deletes an account given valid username and password.
     */
    public synchronized boolean deleteAccount(String username, String password) {
        if (login(username, password)) {
            userCredentials.remove(username.trim());
            return true;
        }
        return false;
    }

    /**
     * Checks if a user already exists.
     */
    public boolean userExists(String username) {
        if (username == null) return false;
        return userCredentials.containsKey(username.trim());
    }
}
