package server;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class ServerAccountHandler {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final SecureRandom STATIC_RANDOM = new SecureRandom();

    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    public ServerAccountHandler() {
        this.dbUrl = System.getenv("DB_URL") != null ? System.getenv("DB_URL") : "jdbc:postgresql://localhost:5432/chess";
        this.dbUser = System.getenv("DB_USER") != null ? System.getenv("DB_USER") : "postgres";
        this.dbPass = System.getenv("DB_PASS") != null ? System.getenv("DB_PASS") : "postgres";

        initDatabase();
    }
    
    private void initDatabase() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS chess_users (" +
                        "username VARCHAR(24) PRIMARY KEY, " +
                        "salt VARCHAR(64) NOT NULL, " +
                        "password_hash VARCHAR(128) NOT NULL, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                System.out.println("[DB] Connected to PostgreSQL and verified 'chess_users' table.");
            }
        } catch (Exception e) {
            System.err.println("[DB] PostgreSQL init failed: " + e.getMessage() + ". Check DB_URL, DB_USER, DB_PASS.");
        }
    }
    
    private Connection getConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPass);
    }

    public synchronized boolean register(String username, String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) return false;
        
        try (Connection conn = getConnection();
             PreparedStatement checkStmt = conn.prepareStatement("SELECT username FROM chess_users WHERE username = ?")) {
            
            checkStmt.setString(1, username);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                return false; // Username taken
            }
            
            byte[] salt = new byte[SALT_BYTES];
            STATIC_RANDOM.nextBytes(salt);
            byte[] hash = hashPassword(password.toCharArray(), salt);
            if (hash == null) return false;
            
            String b64Salt = Base64.getEncoder().encodeToString(salt);
            String b64Hash = Base64.getEncoder().encodeToString(hash);
            
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO chess_users (username, salt, password_hash) VALUES (?, ?, ?)")) {
                insertStmt.setString(1, username);
                insertStmt.setString(2, b64Salt);
                insertStmt.setString(3, b64Hash);
                insertStmt.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean login(String username, String password) {
        if (username == null || password == null) return false;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT salt, password_hash FROM chess_users WHERE username = ?")) {
            
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            
            if (!rs.next()) {
                hashPassword("dummy".toCharArray(), new byte[SALT_BYTES]); // Prevent timing attacks
                return false;
            }
            
            byte[] salt = Base64.getDecoder().decode(rs.getString("salt"));
            byte[] storedHash = Base64.getDecoder().decode(rs.getString("password_hash"));
            
            byte[] computedHash = hashPassword(password.toCharArray(), salt);
            if (computedHash == null) return false;

            return MessageDigest.isEqual(storedHash, computedHash);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public synchronized boolean deleteAccount(String username, String password) {
        if (!login(username, password)) {
            return false;
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM chess_users WHERE username = ?")) {
            stmt.setString(1, username);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private byte[] hashPassword(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BYTES * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALGO);
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            return null;
        } finally {
            for (int i = 0; i < password.length; i++) password[i] = '\0';
        }
    }
}
