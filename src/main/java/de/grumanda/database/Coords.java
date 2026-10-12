package de.grumanda.database;

public class Coords {

    private String uuid;
    private String location_name;
    private int x;
    private int y;
    private int z;
    private String dimension;
    private boolean isPublic;
    private String status;

    public Coords(String uuid, String location_name, int x, int y, int z, String dimension, boolean isPublic, String status) {
        this.uuid = uuid;
        this.location_name = location_name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.isPublic = isPublic;
        this.status = status;
    }

    public String getUuid() {
        return uuid;
    }

    public String getLocation_name() {
        return location_name;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public int getY() {
        return y;
    }

    public String getDimension() {
        return dimension;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public String getStatus() {
        return status;
    }

}
