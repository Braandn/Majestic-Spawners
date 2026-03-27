package de.braandn.utils;

import de.braandn.storage.SpawnerRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public class SpawnerData {

  private final String spawnerId;
  private final String world;
  private final int chunkX;
  private final int chunkZ;
  private final int blockX;
  private final int blockY;
  private final int blockZ;

  private String ownerUuid;
  private String spawnerType;
  private int amount;
  private long experience;
  private List<ItemStack> inventory;
  private int totalItemCount;

  private transient boolean dirty;
  private transient Runnable dirtyListener;
  private transient long mutationVersion;
  private transient long persistedVersion;
  private transient long queuedPersistVersion;
  private transient long lastProcessingTick;

  public SpawnerData(
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
      List<ItemStack> inventory) {
    this.spawnerId = spawnerId;
    this.world = world;
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.blockX = blockX;
    this.blockY = blockY;
    this.blockZ = blockZ;
    this.ownerUuid = ownerUuid;
    this.spawnerType = spawnerType;
    this.amount = amount;
    this.experience = experience;
    this.inventory = sanitizeInventory(inventory);
    this.totalItemCount = computeTotalItemCount(this.inventory);
    this.dirty = false;
    this.mutationVersion = 0L;
    this.persistedVersion = 0L;
    this.queuedPersistVersion = -1L;
    this.lastProcessingTick = 0L;
  }

  public static String buildId(String world, int x, int y, int z) {
    return world + ":" + x + ":" + y + ":" + z;
  }

  public String getSpawnerId() {
    return spawnerId;
  }

  public String getWorld() {
    return world;
  }

  public int getChunkX() {
    return chunkX;
  }

  public int getChunkZ() {
    return chunkZ;
  }

  public int getBlockX() {
    return blockX;
  }

  public int getBlockY() {
    return blockY;
  }

  public int getBlockZ() {
    return blockZ;
  }

  public String getOwnerUuid() {
    return ownerUuid;
  }

  public String getSpawnerType() {
    return spawnerType;
  }

  public int getAmount() {
    return amount;
  }

  public long getExperience() {
    return experience;
  }

  public List<ItemStack> getInventory() {
    return Collections.unmodifiableList(inventory);
  }

  public void setOwnerUuid(String ownerUuid) {
    this.ownerUuid = ownerUuid;
    markDirtyInternal();
  }

  public void setSpawnerType(String spawnerType) {
    this.spawnerType = spawnerType;
    markDirtyInternal();
  }

  public void setAmount(int amount) {
    this.amount = amount;
    markDirtyInternal();
  }

  public void setExperience(long experience) {
    this.experience = experience;
    markDirtyInternal();
  }

  public void setInventory(List<ItemStack> inventory) {
    this.inventory = sanitizeInventory(inventory);
    this.totalItemCount = computeTotalItemCount(this.inventory);
    markDirtyInternal();
  }

  public void clearInventory() {
    if (inventory.isEmpty()) {
      return;
    }

    inventory = new ArrayList<>();
    totalItemCount = 0;
    markDirtyInternal();
  }

  public boolean isDirty() {
    return dirty;
  }

  public void setDirtyListener(Runnable dirtyListener) {
    this.dirtyListener = dirtyListener;
  }

  public void markDirty() {
    markDirtyInternal();
  }

  public void clearDirty() {
    persistedVersion = mutationVersion;
    queuedPersistVersion = persistedVersion;
    dirty = false;
  }

  public void markPersisted(long version) {
    persistedVersion = Math.max(persistedVersion, version);
    if (mutationVersion <= persistedVersion) {
      dirty = false;
      queuedPersistVersion = persistedVersion;
    } else {
      dirty = true;
    }
  }

  public boolean shouldQueuePersistence() {
    return dirty && queuedPersistVersion < mutationVersion;
  }

  public void markQueuedForPersistence(long version) {
    queuedPersistVersion = Math.max(queuedPersistVersion, version);
  }

  public long getMutationVersion() {
    return mutationVersion;
  }

  public long getLastProcessingTick() {
    return lastProcessingTick;
  }

  public void setLastProcessingTick(long lastProcessingTick) {
    this.lastProcessingTick = Math.max(0L, lastProcessingTick);
  }

  public int getTotalItemCount() {
    return totalItemCount;
  }

  public int addDrops(List<ItemStack> drops, int maxItems) {
    if (drops == null || drops.isEmpty()) {
      return 0;
    }

    int currentTotal = totalItemCount;
    int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
    int overflow = 0;
    boolean addedAny = false;

    for (ItemStack drop : drops) {
      if (drop == null || drop.getType().isAir() || drop.getAmount() <= 0) {
        continue;
      }

      int remaining = drop.getAmount();

      for (ItemStack existing : inventory) {
        if (remaining <= 0) {
          break;
        }
        if (existing == null || !existing.isSimilar(drop)) {
          continue;
        }

        int maxStack = existing.getMaxStackSize();
        int free = Math.max(0, maxStack - existing.getAmount());
        if (free <= 0) {
          continue;
        }

        int add = Math.min(remaining, free);
        int space = limit - currentTotal;
        if (space <= 0) {
          overflow += remaining;
          remaining = 0;
          break;
        }

        add = Math.min(add, space);
        if (add <= 0) {
          continue;
        }

        existing.setAmount(existing.getAmount() + add);
        currentTotal += add;
        remaining -= add;
        addedAny = true;
      }

      while (remaining > 0) {
        int space = limit - currentTotal;
        if (space <= 0) {
          overflow += remaining;
          break;
        }

        int maxStack = drop.getMaxStackSize();
        int add = Math.min(remaining, Math.min(maxStack, space));
        ItemStack toStore = drop.clone();
        toStore.setAmount(add);
        inventory.add(toStore);

        currentTotal += add;
        remaining -= add;
        addedAny = true;
      }
    }

    if (addedAny) {
      totalItemCount = currentTotal;
      markDirtyInternal();
    }
    return overflow;
  }

  public SpawnerRecord snapshot() {
    return SpawnerRecord.fromData(this);
  }

  private void markDirtyInternal() {
    dirty = true;
    mutationVersion++;
    if (dirtyListener != null) {
      dirtyListener.run();
    }
  }

  private List<ItemStack> sanitizeInventory(List<ItemStack> source) {
    List<ItemStack> sanitized = new ArrayList<>();
    if (source == null || source.isEmpty()) {
      return sanitized;
    }

    for (ItemStack item : source) {
      if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
        continue;
      }
      sanitized.add(item.clone());
    }
    return sanitized;
  }

  private int computeTotalItemCount(List<ItemStack> items) {
    int total = 0;
    for (ItemStack stack : items) {
      if (stack != null) {
        total += stack.getAmount();
      }
    }
    return total;
  }
}
