package server;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * ServerAccountHandler manages user accounts with salted PBKDF2 password hashes.
 */
public class ServerAccountHandler {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 24;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final SecureRandom STATIC_RANDOM = new SecureRandom();

    private static final PasswordRecord DUMMY_RECORD = buildDummyRecord();

    private static class PasswordRecord {
        final byte[] salt;
        final byte[] hash;

        PasswordRecord(byte[] salt, byte[] hash) {
            this.salt = salt;
            this.hash = hash;
        }

        String serialize() {
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        }

        static PasswordRecord deserialize(String encoded) {
            if (encoded == null) return null;
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) return null;
            try {
                byte[] salt = Base64.getDecoder().decode(parts[0]);
                byte[] hash = Base64.getDecoder().decode(parts[1]);
                return new PasswordRecord(salt, hash);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static class AttemptState {
        volatile int failedAttempts = 0;
        volatile long blockedUntilMs = 0L;
    }

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, String> userCredentials = new ConcurrentHashMap<>();
    private final Map<String, AttemptState> loginAttempts = new ConcurrentHashMap<>();

    public static String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        STATIC_RANDOM.nextBytes(salt);
        return deriveRecord(password, salt).serialize();
    }

    private static PasswordRecord buildDummyRecord() {
        byte[] salt = new byte[SALT_BYTES];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i * 17 + 3);
        }
        return deriveRecord("dummy_password_for_timing_only", salt);
    }

    private PasswordRecord hashPasswordForStorage(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        return deriveRecord(password, salt);
    }

    private static PasswordRecord deriveRecord(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BYTES * 8);
            byte[] hash;
            try {
                SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGO);
                hash = skf.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
            return new PasswordRecord(salt, hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 algorithm not available", e);
        }
    }

    private static boolean isValidUsername(String username) {
        if (username == null) return false;
        String trimmed = username.trim();
        if (trimmed.length() < MIN_USERNAME_LENGTH || trimmed.length() > MAX_USERNAME_LENGTH) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = Character.isLetterOrDigit(c) || c == '_' || c == '-';
            if (!valid) return false;
        }
        return true;
    }

    private static boolean isValidPassword(String password) {
        if (password == null) return false;
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c == '\n' || c == '\r' || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static String normalizedUsername(String username) {
        return username == null ? "" : username.trim();
    }

    public synchronized boolean register(String username, String password) {
        if (!isValidUsername(username) || !isValidPassword(password)) {
            return false;
        }
        String normalized = normalizedUsername(username);
        if (userCredentials.containsKey(normalized)) {
            return false;
        }
        userCredentials.put(normalized, hashPasswordForStorage(password).serialize());
        loginAttempts.remove(normalized);
        return true;
    }

    public boolean login(String username, String password) {
        if (!isValidUsername(username) || !isValidPassword(password)) {
            return false;
        }

        String normalized = normalizedUsername(username);
        AttemptState state = loginAttempts.computeIfAbsent(normalized, key -> new AttemptState());
        long now = System.currentTimeMillis();
        if (state.blockedUntilMs > now) {
            return false;
        }

        String encoded = userCredentials.get(normalized);
        PasswordRecord record = PasswordRecord.deserialize(encoded);
        if (record == null) {
            record = DUMMY_RECORD;
        }

        PasswordRecord provided = deriveRecord(password, record.salt);
        boolean exists = encoded != null;
        boolean hashMatches = MessageDigest.isEqual(record.hash, provided.hash);
        boolean success = exists && hashMatches;

        if (success) {
            state.failedAttempts = 0;
            state.blockedUntilMs = 0L;
            return true;
        }

        int failed = Math.min(state.failedAttempts + 1, 12);
        state.failedAttempts = failed;
        long backoffMillis = Math.min(1000L, (long) Math.pow(2, Math.max(0, failed - 3)) * 100L);
        state.blockedUntilMs = now + backoffMillis;

        if (backoffMillis > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(backoffMillis, 150L));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    public synchronized boolean deleteAccount(String username, String password) {
        String normalized = normalizedUsername(username);
        if (login(normalized, password)) {
            userCredentials.remove(normalized);
            loginAttempts.remove(normalized);
            return true;
        }
        return false;
    }

    public boolean userExists(String username) {
        if (!isValidUsername(username)) return false;
        return userCredentials.containsKey(normalizedUsername(username));
    }
}
