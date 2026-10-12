package de.grumanda.database;

import java.sql.*;

public class DatabaseManager {

    private static final String DATABASE_URL = "jdbc:sqlite:grumanda-server.db";

    private static DatabaseManager instance;

    private DatabaseManager() throws SQLException {
        initDatabase();
    }

    public static DatabaseManager getInstance() throws SQLException {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private static void initDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DATABASE_URL)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(SqlCommands.CREATE_USER_TABLE);
                stmt.execute(SqlCommands.CREATE_COORDS_TABLE);
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA busy_timeout = 3000;");
            }
        }
    }

    public void createCoordsEntry(User user, Coords coords) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DATABASE_URL)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(SqlCommands.UPSERT_USER)) {
                    stmt.setString(1, user.getUuid());
                    stmt.setString(2, user.getName());
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(SqlCommands.CREATE_COORDS)) {
                    stmt.setString(1, user.getUuid());
                    stmt.setString(2, coords.getLocation_name());
                    stmt.setInt(3, coords.getX());
                    stmt.setInt(4, coords.getY());
                    stmt.setInt(5, coords.getZ());
                    stmt.setString(6, coords.getDimension());
                    stmt.setBoolean(7, coords.isPublic());
                    stmt.setString(8, coords.getStatus());
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }
}
