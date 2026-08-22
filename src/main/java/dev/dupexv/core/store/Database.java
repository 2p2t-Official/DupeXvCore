package dev.dupexv.core.store;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Database {

    private final DupeXvCore plugin;
    private Connection connection;

    public Database(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void open() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File file = new File(plugin.getDataFolder(), "data.db");
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS players (
                          uuid TEXT PRIMARY KEY,
                          name TEXT,
                          last_seen INTEGER NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS homes (
                          uuid TEXT NOT NULL,
                          name TEXT NOT NULL,
                          world TEXT NOT NULL,
                          x REAL NOT NULL,
                          y REAL NOT NULL,
                          z REAL NOT NULL,
                          yaw REAL NOT NULL,
                          pitch REAL NOT NULL,
                          created INTEGER NOT NULL,
                          PRIMARY KEY (uuid, name)
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS ignores (
                          uuid TEXT NOT NULL,
                          ignored TEXT NOT NULL,
                          PRIMARY KEY (uuid, ignored)
                        )
                        """);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
            connection = null;
        }
    }

    public synchronized void touch(UUID uuid, String name, long when) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO players(uuid, name, last_seen) VALUES(?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, when);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
    }

    public synchronized List<HomeRecord> homes(UUID uuid) {
        List<HomeRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, world, x, y, z, yaw, pitch, created FROM homes WHERE uuid=? ORDER BY created ASC, name ASC")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HomeRecord(
                            uuid,
                            rs.getString("name"),
                            rs.getString("world"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch"),
                            rs.getLong("created")
                    ));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
        return list;
    }

    public synchronized HomeRecord home(UUID uuid, String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, world, x, y, z, yaw, pitch, created FROM homes WHERE uuid=? AND lower(name)=lower(?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new HomeRecord(
                        uuid,
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"),
                        rs.getLong("created")
                );
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return null;
        }
    }

    public synchronized int homeCount(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM homes WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return 0;
        }
    }

    public synchronized void saveHome(Player player, String name, Location loc, long created) {
        touch(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        HomeRecord existing = home(player.getUniqueId(), name);
        long stamp = existing != null ? existing.created : created;
        String stored = existing != null ? existing.name : name;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO homes(uuid, name, world, x, y, z, yaw, pitch, created) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uuid, name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, stored);
            ps.setString(3, loc.getWorld().getName());
            ps.setDouble(4, loc.getX());
            ps.setDouble(5, loc.getY());
            ps.setDouble(6, loc.getZ());
            ps.setFloat(7, loc.getYaw());
            ps.setFloat(8, loc.getPitch());
            ps.setLong(9, stamp);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
    }

    public synchronized void renameHome(UUID uuid, String from, String to) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE homes SET name=? WHERE uuid=? AND lower(name)=lower(?)")) {
            ps.setString(1, to);
            ps.setString(2, uuid.toString());
            ps.setString(3, from);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
    }

    public synchronized boolean deleteHome(UUID uuid, String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM homes WHERE uuid=? AND lower(name)=lower(?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return false;
        }
    }

    public synchronized boolean isIgnoring(UUID who, UUID whom) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM ignores WHERE uuid=? AND ignored=?")) {
            ps.setString(1, who.toString());
            ps.setString(2, whom.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return false;
        }
    }

    public synchronized void addIgnore(UUID who, UUID whom) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO ignores(uuid, ignored) VALUES(?, ?)")) {
            ps.setString(1, who.toString());
            ps.setString(2, whom.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
    }

    public synchronized boolean removeIgnore(UUID who, UUID whom) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM ignores WHERE uuid=? AND ignored=?")) {
            ps.setString(1, who.toString());
            ps.setString(2, whom.toString());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return false;
        }
    }

    public synchronized List<UUID> ignores(UUID who) {
        List<UUID> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ignored FROM ignores WHERE uuid=? ORDER BY ignored ASC")) {
            ps.setString(1, who.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(UUID.fromString(rs.getString("ignored")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
        }
        return list;
    }
}
