package de.braandn.events;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;

public class SpawnerSpawnControl implements Listener {

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSpawnerSpawn(SpawnerSpawnEvent e) {
    CreatureSpawner spawner = e.getSpawner();
    if (spawner == null) return;

    String spawnerId =
        SpawnerData.buildId(
            spawner.getWorld().getName(), spawner.getX(), spawner.getY(), spawner.getZ());

    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    if (plugin.getSpawnerCache().containsKey(spawnerId)
        || SpawnerItem.isCustomSpawnerBlock(spawner)) {
      e.setCancelled(true);
    }
  }
}
