package de.grumanda.database;

class SqlCommands {

    static final String CREATE_USER_TABLE = """
            CREATE TABLE IF NOT EXISTS users (
                uuid TEXT PRIMARY KEY,
                last_known_name TEXT NOT NULL
            );
            """;

    static final String CREATE_COORDS_TABLE = """
            CREATE TABLE IF NOT EXISTS coordinates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                location_name TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                dimension TEXT NOT NULL,
                is_public BOOLEAN NOT NULL,
                status TEXT DEFAULT 'PENDING',
                FOREIGN KEY (player_uuid) REFERENCES users(uuid)
            );
            """;

    static final String CREATE_USER = "INSERT INTO users(uuid, last_known_name) VALUES (?, ?)";

    static final String UPSERT_USER = """
            INSERT INTO users(uuid, last_known_name) VALUES (?, ?)
            ON CONFLICT(uuid) DO UPDATE SET last_known_name = excluded.last_known_name
            """;

    static final String CREATE_COORDS = """
            INSERT INTO coordinates (player_uuid, location_name, x, y, z, dimension, is_public, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
}
