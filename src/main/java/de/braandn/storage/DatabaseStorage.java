package de.braandn.storage;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class DatabaseStorage {

  static final String DATABASE = "spawners.db";

  private static final String PRAGMA_JOURNAL_MODE = "PRAGMA journal_mode=WAL;";
  private static final String PRAGMA_SYNCHRONOUS = "PRAGMA synchronous=NORMAL;";
  private static final String PRAGMA_TEMP_STORE = "PRAGMA temp_store=MEMORY;";
  private static final String PRAGMA_BUSY_TIMEOUT = "PRAGMA busy_timeout=5000;";

  private static final String CHECK_TABLE =
      "SELECT name FROM sqlite_master WHERE type='table' AND name='spawners';";
  private static final String CREATE_TABLE =
      "CREATE TABLE spawners ("
          + "  spawner_id   TEXT    PRIMARY KEY NOT NULL, "
          + "  world        TEXT    NOT NULL, "
          + "  chunk_x      INTEGER NOT NULL, "
          + "  chunk_z      INTEGER NOT NULL, "
          + "  block_x      INTEGER NOT NULL, "
          + "  block_y      INTEGER NOT NULL, "
          + "  block_z      INTEGER NOT NULL, "
          + "  owner_uuid   TEXT    NOT NULL DEFAULT '', "
          + "  spawner_type TEXT    NOT NULL DEFAULT '', "
          + "  amount       INTEGER NOT NULL DEFAULT 1, "
          + "  experience   BIGINT  NOT NULL DEFAULT 0, "
          + "  inventory    BLOB"
          + ");";
  private static final String CREATE_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_chunk ON spawners(world, chunk_x, chunk_z);";

  private static final String SELECT_ALL = "SELECT * FROM spawners;";
  private static final String UPSERT_ROW =
      "INSERT INTO spawners "
          + "(spawner_id, world, chunk_x, chunk_z, block_x, block_y, block_z, "
          + " owner_uuid, spawner_type, amount, experience, inventory) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT(spawner_id) DO UPDATE SET "
          + "owner_uuid=excluded.owner_uuid, "
          + "spawner_type=excluded.spawner_type, "
          + "amount=excluded.amount, "
          + "experience=excluded.experience, "
          + "inventory=excluded.inventory;";
  private static final String DELETE_ROW = "DELETE FROM spawners WHERE spawner_id = ?;";

  private DatabaseStorage() {}

  public static Connection openConnection(File dataFolder, Logger log) {
    String path = dataFolder.getAbsolutePath();
    String url = path + File.separator + DATABASE;

    dataFolder.mkdirs();

    boolean fresh = !new File(url).exists();
    try {
      Connection conn = DriverManager.getConnection("jdbc:sqlite:" + url);
      configureConnection(conn);
      initializeSchema(conn, log, fresh);
      return conn;
    } catch (SQLException ex) {
      log.log(Level.SEVERE, "Could not open spawner database.", ex);
      return null;
    }
  }

  public static Map<String, SpawnerData> readAllSpawners(Connection conn) {
    Map<String, SpawnerData> result = new LinkedHashMap<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(SELECT_ALL)) {
      while (rs.next()) {
        String id = rs.getString("spawner_id");
        String world = rs.getString("world");
        int chunkX = rs.getInt("chunk_x");
        int chunkZ = rs.getInt("chunk_z");
        int blockX = rs.getInt("block_x");
        int blockY = rs.getInt("block_y");
        int blockZ = rs.getInt("block_z");
        String ownerUuid = rs.getString("owner_uuid");
        String type = rs.getString("spawner_type");
        int amount = rs.getInt("amount");
        long experience = rs.getLong("experience");
        byte[] blob = rs.getBytes("inventory");

        SpawnerData data =
            new SpawnerData(
                id,
                world,
                chunkX,
                chunkZ,
                blockX,
                blockY,
                blockZ,
                ownerUuid,
                type,
                amount,
                experience,
                deserializeInventory(blob));
        result.put(id, data);
      }
    } catch (SQLException ex) {
      SpawnersPlugin.getInstance().getLogger().log(Level.SEVERE, "Could not read spawners.", ex);
    }

    int count = result.size();
    SpawnersPlugin.getInstance()
        .getLogger()
        .info("Loaded " + count + " spawner" + (count != 1 ? "s." : "."));
    return result;
  }

  public static void upsertSpawners(Connection conn, Collection<SpawnerRecord> records)
      throws SQLException {
    if (records == null || records.isEmpty()) {
      return;
    }

    boolean originalAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    try (PreparedStatement prep = conn.prepareStatement(UPSERT_ROW)) {
      for (SpawnerRecord record : records) {
        prep.setString(1, record.spawnerId());
        prep.setString(2, record.world());
        prep.setInt(3, record.chunkX());
        prep.setInt(4, record.chunkZ());
        prep.setInt(5, record.blockX());
        prep.setInt(6, record.blockY());
        prep.setInt(7, record.blockZ());
        prep.setString(8, record.ownerUuid());
        prep.setString(9, record.spawnerType());
        prep.setInt(10, record.amount());
        prep.setLong(11, record.experience());
        prep.setBytes(12, serializeInventory(record.inventory()));
        prep.addBatch();
      }
      prep.executeBatch();
      conn.commit();
    } catch (SQLException ex) {
      conn.rollback();
      throw ex;
    } finally {
      conn.setAutoCommit(originalAutoCommit);
    }
  }

  public static void deleteSpawners(Connection conn, Collection<String> spawnerIds)
      throws SQLException {
    if (spawnerIds == null || spawnerIds.isEmpty()) {
      return;
    }

    boolean originalAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    try (PreparedStatement prep = conn.prepareStatement(DELETE_ROW)) {
      for (String spawnerId : spawnerIds) {
        prep.setString(1, spawnerId);
        prep.addBatch();
      }
      prep.executeBatch();
      conn.commit();
    } catch (SQLException ex) {
      conn.rollback();
      throw ex;
    } finally {
      conn.setAutoCommit(originalAutoCommit);
    }
  }

  static byte[] serializeInventory(List<ItemStack> items) {
    try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        BukkitObjectOutputStream out = new BukkitObjectOutputStream(byteStream)) {
      out.writeInt(items.size());
      for (ItemStack item : items) {
        out.writeObject(item);
      }
      out.flush();
      return byteStream.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Could not serialize spawner inventory.", ex);
    }
  }

  static List<ItemStack> deserializeInventory(byte[] blob) {
    List<ItemStack> binary = deserializeBinaryInventory(blob);
    if (binary != null) {
      return binary;
    }
    return deserializeLegacyYamlInventory(blob);
  }

  private static void configureConnection(Connection conn) throws SQLException {
    try (Statement statement = conn.createStatement()) {
      statement.execute(PRAGMA_JOURNAL_MODE);
      statement.execute(PRAGMA_SYNCHRONOUS);
      statement.execute(PRAGMA_TEMP_STORE);
      statement.execute(PRAGMA_BUSY_TIMEOUT);
    }
  }

  private static void initializeSchema(Connection conn, Logger log, boolean fresh) throws SQLException {
    if (fresh) {
      log.info("Created database file.");
    }

    try (Statement statement = conn.createStatement();
        ResultSet rs = statement.executeQuery(CHECK_TABLE)) {
      if (!rs.next()) {
        try (Statement create = conn.createStatement()) {
          create.executeUpdate(CREATE_TABLE);
          create.executeUpdate(CREATE_INDEX);
          log.info("Created spawners table + chunk index.");
        }
      }
    }
  }

  private static List<ItemStack> deserializeBinaryInventory(byte[] blob) {
    if (blob == null || blob.length == 0) {
      return List.of();
    }

    try (ByteArrayInputStream byteStream = new ByteArrayInputStream(blob);
        BukkitObjectInputStream in = new BukkitObjectInputStream(byteStream)) {
      int count = in.readInt();
      List<ItemStack> items = new ArrayList<>(Math.max(0, count));
      for (int i = 0; i < count; i++) {
        Object raw = in.readObject();
        if (raw instanceof ItemStack item) {
          items.add(item);
        }
      }
      return items;
    } catch (IOException | ClassNotFoundException ex) {
      return null;
    }
  }

  private static List<ItemStack> deserializeLegacyYamlInventory(byte[] blob) {
    List<ItemStack> items = new ArrayList<>();
    if (blob == null || blob.length == 0) {
      return items;
    }

    YamlConfiguration cfg = new YamlConfiguration();
    try {
      cfg.loadFromString(new String(blob, StandardCharsets.UTF_8));
      int count = cfg.getInt("count", 0);
      for (int i = 0; i < count; i++) {
        ItemStack item = cfg.getItemStack("item_" + i, null);
        if (item != null) {
          items.add(item);
        }
      }
    } catch (InvalidConfigurationException ex) {
      SpawnersPlugin.getInstance()
          .getLogger()
          .log(Level.WARNING, "Could not deserialize legacy spawner inventory blob.", ex);
    }
    return items;
  }
}
