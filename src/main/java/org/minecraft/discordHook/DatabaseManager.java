package org.minecraft.discordHook;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final DiscordHook plugin;
    private HikariDataSource dataSource;
    private String tableName;

    public DatabaseManager(DiscordHook plugin) {
        this.plugin = plugin;
    }

    /**
     * เริ่มต้นเชื่อมต่อฐานข้อมูล MySQL และสร้างตารางอัตโนมัติหากยังไม่มี
     */
    public boolean initialize() {
        close(); // ปิดการเชื่อมต่อเดิมหากเป็นการ reload

        boolean enabled = plugin.getConfig().getBoolean("mysql.enabled", true);
        if (!enabled) {
            plugin.getLogger().warning("MySQL is disabled in config.yml! Reward tracking will not work.");
            return false;
        }

        String host = plugin.getConfig().getString("mysql.host", "localhost");
        int port = plugin.getConfig().getInt("mysql.port", 3306);
        String database = plugin.getConfig().getString("mysql.database", "minecraft");
        String username = plugin.getConfig().getString("mysql.username", "root");
        String password = plugin.getConfig().getString("mysql.password", "");
        boolean useSSL = plugin.getConfig().getBoolean("mysql.use-ssl", false);
        this.tableName = plugin.getConfig().getString("mysql.table", "discordhook_rewards");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=%b&characterEncoding=utf-8&serverTimezone=UTC",
                host, port, database, useSSL));
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);

        // ตั้งค่า Connection Pool
        hikariConfig.setMaximumPoolSize(plugin.getConfig().getInt("mysql.pool.maximum-pool-size", 10));
        hikariConfig.setMinimumIdle(plugin.getConfig().getInt("mysql.pool.minimum-idle", 2));
        hikariConfig.setConnectionTimeout(plugin.getConfig().getLong("mysql.pool.connection-timeout", 30000));
        hikariConfig.setPoolName("DiscordHook-MySQL-Pool");

        // เพิ่มการตั้งค่าเพื่อความเสถียรและประสิทธิภาพ
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            createTable();
            plugin.getLogger().info("Successfully connected to MySQL database!");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not connect to MySQL database! Please check your config.yml", e);
            return false;
        }
    }

    /**
     * สร้างตารางจัดเก็บข้อมูลหากยังไม่มี
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "uuid VARCHAR(36) PRIMARY KEY, "
                + "player_name VARCHAR(16) NOT NULL, "
                + "discord_id VARCHAR(32), "
                + "claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ");";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * ตรวจสอบว่าบัญชีเกม (UUID) นี้เคยรับรางวัลไปแล้วหรือไม่
     */
    public boolean hasPlayerClaimed(UUID uuid) {
        if (!isConnected()) return false;
        String sql = "SELECT uuid FROM " + tableName + " WHERE uuid = ? LIMIT 1;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error checking claim status for UUID: " + uuid, e);
            return false;
        }
    }

    /**
     * ตรวจสอบว่าบัญชี Discord ID นี้เคยรับรางวัลไปแล้วหรือไม่
     */
    public boolean hasDiscordClaimed(String discordId) {
        if (!isConnected() || discordId == null || discordId.isEmpty()) return false;
        String sql = "SELECT uuid FROM " + tableName + " WHERE discord_id = ? LIMIT 1;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error checking claim status for Discord ID: " + discordId, e);
            return false;
        }
    }

    /**
     * บันทึกข้อมูลการรับรางวัลลงใน MySQL
     */
    public boolean recordClaim(UUID uuid, String playerName, String discordId) {
        if (!isConnected()) return false;
        String sql = "INSERT INTO " + tableName + " (uuid, player_name, discord_id) VALUES (?, ?, ?);";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, discordId != null ? discordId : "");
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error recording claim for UUID: " + uuid, e);
            return false;
        }
    }

    /**
     * ลบประวัติการรับรางวัลของผู้เล่น เพื่อให้รับใหม่ได้ (สำหรับคำสั่งแอดมิน)
     */
    public boolean resetClaim(UUID uuid) {
        if (!isConnected()) return false;
        String sql = "DELETE FROM " + tableName + " WHERE uuid = ?;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error resetting claim for UUID: " + uuid, e);
            return false;
        }
    }

    /**
     * ปิดการเชื่อมต่อฐานข้อมูล
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }
}

