package dev.timefiles.miasmartgiftroll.storage;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import dev.timefiles.miasmartgiftroll.kit.KitSerializer;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class DatabaseManager {
    private final MiaSmartGiftRoll plugin;
    private Connection connection;
    private final File databaseFile;

    public DatabaseManager(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "pending_items.db");
    }

    public void initialize() {
        try {
            if (!this.plugin.getDataFolder().exists()) {
                this.plugin.getDataFolder().mkdirs();
            }
            String url = "jdbc:sqlite:" + this.databaseFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            this.createTables();
            this.plugin.getLogger().info("SQLite database initialized: " + this.databaseFile.getName());
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        String createPendingItemsTable = "CREATE TABLE IF NOT EXISTS pending_items (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    player_uuid TEXT NOT NULL,\n    item_data TEXT NOT NULL,\n    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n)\n";
        String createIndexSql = "CREATE INDEX IF NOT EXISTS idx_player_uuid ON pending_items(player_uuid)\n";
        try (Statement stmt = this.connection.createStatement();){
            stmt.execute(createPendingItemsTable);
            stmt.execute(createIndexSql);
        }
    }

    public void addPendingItems(UUID playerUuid, List<ItemStack> items) {
        String sql = "INSERT INTO pending_items (player_uuid, item_data) VALUES (?, ?)";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql);){
            for (ItemStack item : items) {
                if (item == null) continue;
                String serialized = KitSerializer.serializeItem(item);
                pstmt.setString(1, playerUuid.toString());
                pstmt.setString(2, serialized);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to add pending items: " + e.getMessage());
        }
    }

    public Map<Integer, ItemStack> getPendingItems(UUID playerUuid) {
        LinkedHashMap<Integer, ItemStack> items = new LinkedHashMap<Integer, ItemStack>();
        String sql = "SELECT id, item_data FROM pending_items WHERE player_uuid = ? ORDER BY created_at ASC";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql);){
            pstmt.setString(1, playerUuid.toString());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                String itemData = rs.getString("item_data");
                try {
                    ItemStack item = KitSerializer.deserializeItem(itemData);
                    if (item == null) continue;
                    items.put(id, item);
                }
                catch (Exception e) {
                    this.plugin.getLogger().warning("Failed to deserialize pending item #" + id + ": " + e.getMessage());
                }
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to get pending items: " + e.getMessage());
        }
        return items;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public int getPendingCount(UUID playerUuid) {
        String sql = "SELECT COUNT(*) FROM pending_items WHERE player_uuid = ?";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql);){
            pstmt.setString(1, playerUuid.toString());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) return 0;
            int n = rs.getInt(1);
            return n;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to count pending items: " + e.getMessage());
        }
        return 0;
    }

    public boolean hasPendingItems(UUID playerUuid) {
        return this.getPendingCount(playerUuid) > 0;
    }

    public boolean removePendingItem(int itemId) {
        String sql = "DELETE FROM pending_items WHERE id = ?";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setInt(1, itemId);
            return pstmt.executeUpdate() > 0;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to remove pending item: " + e.getMessage());
            return false;
        }
    }

    public int removeAllPendingItems(UUID playerUuid) {
        String sql = "DELETE FROM pending_items WHERE player_uuid = ?";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            return pstmt.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to remove all pending items: " + e.getMessage());
            return 0;
        }
    }

    public void close() {
        try {
            if (this.connection != null && !this.connection.isClosed()) {
                this.connection.close();
                this.plugin.getLogger().info("SQLite database connection closed.");
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to close database connection: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return this.connection != null && !this.connection.isClosed();
        }
        catch (SQLException e) {
            return false;
        }
    }
}



