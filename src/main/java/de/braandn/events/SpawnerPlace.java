package de.braandn.events;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

public class SpawnerPlace implements Listener {

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent e) {
    Block block = e.getBlock();
    if (block.getType() != Material.SPAWNER) {
      return;
    }

    ItemStack hand = e.getItemInHand();
    if (!SpawnerItem.isCustomSpawner(hand)) {
      return;
    }

    String spawnerType = SpawnerItem.getSpawnerType(hand);
    if (spawnerType == null) {
      return;
    }
    int stackAmount = SpawnerItem.getSpawnerAmount(hand);
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    if (stackAmount > plugin.getMaxSpawnerStack()) {
      e.setCancelled(true);
      return;
    }

    Player player = e.getPlayer();

    String world = block.getWorld().getName();
    int chunkX = block.getChunk().getX();
    int chunkZ = block.getChunk().getZ();
    int bx = block.getX();
    int by = block.getY();
    int bz = block.getZ();
    String spawnerId = SpawnerData.buildId(world, bx, by, bz);

    SpawnerData data =
        new SpawnerData(
            spawnerId,
            world,
            chunkX,
            chunkZ,
            bx,
            by,
            bz,
            player.getUniqueId().toString(),
            spawnerType,
            stackAmount,
            0L,
            new ArrayList<>());

    if (!plugin.registerSpawner(data)) {
      e.setCancelled(true);
      return;
    }

    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (block.getType() != Material.SPAWNER) {
                return;
              }

              try {
                CreatureSpawner spawner = (CreatureSpawner) block.getState();
                spawner.setSpawnedType(EntityType.valueOf(spawnerType));
                SpawnerItem.applyPlacedSpawnerData(
                    spawner, spawnerType, stackAmount, player.getUniqueId().toString());
                spawner.update(true);
              } catch (IllegalArgumentException ignored) {
              }
            });

    plugin.startTicker(data);
  }
}
