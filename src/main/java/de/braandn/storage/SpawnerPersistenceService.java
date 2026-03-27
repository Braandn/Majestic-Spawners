package de.braandn.storage;

import de.braandn.SpawnersPlugin;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

public final class SpawnerPersistenceService {

  private final SpawnersPlugin plugin;
  private final File dataFolder;
  private final Logger logger;
  private final ExecutorService executor;
  private final Object bufferLock;

  private final Map<String, SpawnerRecord> pendingUpserts;
  private final Set<String> pendingDeletes;

  private Connection connection;
  private boolean drainScheduled;
  private boolean closed;

  public SpawnerPersistenceService(SpawnersPlugin plugin) {
    this.plugin = plugin;
    this.dataFolder = plugin.getDataFolder();
    this.logger = plugin.getLogger();
    this.executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "MajesticSpawners-DB");
              thread.setDaemon(true);
              return thread;
            });
    this.bufferLock = new Object();
    this.pendingUpserts = new LinkedHashMap<>();
    this.pendingDeletes = new LinkedHashSet<>();
  }

  public void queueUpsert(SpawnerRecord record) {
    if (record == null) {
      return;
    }

    synchronized (bufferLock) {
      if (closed) {
        return;
      }
      pendingDeletes.remove(record.spawnerId());
      pendingUpserts.put(record.spawnerId(), record);
      scheduleDrainLocked();
    }
  }

  public void queueUpserts(Collection<SpawnerRecord> records) {
    if (records == null || records.isEmpty()) {
      return;
    }

    synchronized (bufferLock) {
      if (closed) {
        return;
      }

      for (SpawnerRecord record : records) {
        if (record == null) {
          continue;
        }
        pendingDeletes.remove(record.spawnerId());
        pendingUpserts.put(record.spawnerId(), record);
      }

      scheduleDrainLocked();
    }
  }

  public void queueDelete(String spawnerId) {
    if (spawnerId == null || spawnerId.isBlank()) {
      return;
    }

    synchronized (bufferLock) {
      if (closed) {
        return;
      }
      pendingUpserts.remove(spawnerId);
      pendingDeletes.add(spawnerId);
      scheduleDrainLocked();
    }
  }

  public void shutdownAndFlush(Collection<SpawnerRecord> finalSnapshots) {
    synchronized (bufferLock) {
      if (closed) {
        return;
      }

      if (finalSnapshots != null) {
        for (SpawnerRecord record : finalSnapshots) {
          if (record == null || pendingDeletes.contains(record.spawnerId())) {
            continue;
          }
          pendingUpserts.put(record.spawnerId(), record);
        }
      }

      closed = true;
      scheduleDrainLocked();
    }

    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        logger.warning("Timed out waiting for async spawner persistence to finish.");
        executor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      logger.log(Level.WARNING, "Interrupted while waiting for spawner persistence shutdown.", ex);
      executor.shutdownNow();
    } finally {
      closeConnection();
    }
  }

  private void scheduleDrainLocked() {
    if (drainScheduled) {
      return;
    }
    drainScheduled = true;
    executor.execute(this::drainPending);
  }

  private void drainPending() {
    while (true) {
      PendingBatch batch = takePendingBatch();
      if (batch == null) {
        return;
      }

      try {
        Connection conn = ensureConnection();
        if (!batch.deletes().isEmpty()) {
          DatabaseStorage.deleteSpawners(conn, batch.deletes());
        }
        if (!batch.upserts().isEmpty()) {
          DatabaseStorage.upsertSpawners(conn, batch.upserts());
          acknowledgeWrites(batch.upserts());
        }
      } catch (Exception ex) {
        requeue(batch);
        logger.log(Level.SEVERE, "Failed to persist spawner data asynchronously.", ex);
        pauseAfterFailure();
        return;
      }
    }
  }

  private PendingBatch takePendingBatch() {
    synchronized (bufferLock) {
      if (pendingUpserts.isEmpty() && pendingDeletes.isEmpty()) {
        drainScheduled = false;
        return null;
      }

      List<String> deletes = List.copyOf(pendingDeletes);
      List<SpawnerRecord> upserts = List.copyOf(pendingUpserts.values());
      pendingDeletes.clear();
      pendingUpserts.clear();
      return new PendingBatch(upserts, deletes);
    }
  }

  private void requeue(PendingBatch batch) {
    synchronized (bufferLock) {
      for (String spawnerId : batch.deletes()) {
        pendingUpserts.remove(spawnerId);
        pendingDeletes.add(spawnerId);
      }

      for (SpawnerRecord record : batch.upserts()) {
        if (!pendingDeletes.contains(record.spawnerId())) {
          pendingUpserts.put(record.spawnerId(), record);
        }
      }

      drainScheduled = false;
      if (!closed) {
        scheduleDrainLocked();
      }
    }
  }

  private void pauseAfterFailure() {
    try {
      TimeUnit.SECONDS.sleep(1L);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private Connection ensureConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      connection = DatabaseStorage.openConnection(dataFolder, logger);
      if (connection == null) {
        throw new SQLException("Could not open SQLite connection.");
      }
    }
    return connection;
  }

  private void acknowledgeWrites(List<SpawnerRecord> records) {
    if (!plugin.isEnabled() || records.isEmpty()) {
      return;
    }

    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              for (SpawnerRecord record : records) {
                plugin.markPersisted(record.spawnerId(), record.version());
              }
            });
  }

  private void closeConnection() {
    if (connection == null) {
      return;
    }

    try {
      connection.close();
    } catch (SQLException ex) {
      logger.log(Level.WARNING, "Could not close spawner database connection cleanly.", ex);
    } finally {
      connection = null;
    }
  }

  private record PendingBatch(List<SpawnerRecord> upserts, List<String> deletes) {}
}
