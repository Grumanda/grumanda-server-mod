package de.grumanda.database;

public class User {

    private final String uuid;
    private final String name;

    public User(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getUuid() {
        return uuid;
    }
}
