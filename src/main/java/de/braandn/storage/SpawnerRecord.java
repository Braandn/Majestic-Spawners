package de.braandn.storage;

import de.braandn.utils.SpawnerData;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public record SpawnerRecord(
    String spawnerId,
    String world,
    int chunkX,
    int chunkZ,
    int blockX,
    int blockY,
    int blockZ,
    String ownerUuid,
    String spawnerType,
    int amount,
    long experience,
    long version,
    List<ItemStack> inventory) {

  public static SpawnerRecord fromData(SpawnerData data) {
    List<ItemStack> inventoryCopy = new ArrayList<>(data.getInventory().size());
    for (ItemStack item : data.getInventory()) {
      if (item != null) {
        inventoryCopy.add(item.clone());
      }
    }

    return new SpawnerRecord(
        data.getSpawnerId(),
        data.getWorld(),
        data.getChunkX(),
        data.getChunkZ(),
        data.getBlockX(),
        data.getBlockY(),
        data.getBlockZ(),
        data.getOwnerUuid(),
        data.getSpawnerType(),
        data.getAmount(),
        data.getExperience(),
        data.getMutationVersion(),
        List.copyOf(inventoryCopy));
  }
}
