package de.braandn.events;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public class ChunksLoading implements Listener {

  @EventHandler
  public void chunkLoad(ChunkLoadEvent event) {
    Chunk chunk = event.getChunk();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    List<SpawnerData> spawnersInChunk =
        plugin.getSpawnersInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

    for (SpawnerData data : spawnersInChunk) {
      plugin.startTicker(data);
      syncPlacedSpawnerPdc(chunk, data);
    }
  }

  @EventHandler
  public void chunkUnload(ChunkUnloadEvent event) {
    Chunk chunk = event.getChunk();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    List<SpawnerData> spawnersInChunk =
        plugin.getSpawnersInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

    for (SpawnerData data : spawnersInChunk) {
      plugin.stopTicker(data.getSpawnerId());
      plugin.flushSpawner(data);
    }
  }

  private void syncPlacedSpawnerPdc(Chunk chunk, SpawnerData data) {
    Block block = chunk.getWorld().getBlockAt(data.getBlockX(), data.getBlockY(), data.getBlockZ());
    if (block.getType() != Material.SPAWNER) {
      return;
    }
    if (!(block.getState() instanceof CreatureSpawner spawner)) {
      return;
    }

    SpawnerItem.applyPlacedSpawnerData(
        spawner, data.getSpawnerType(), data.getAmount(), data.getOwnerUuid());
    spawner.update(true);
  }
}
