package de.braandn.events;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class SpawnerBreak implements Listener {

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent e) {
    Block block = e.getBlock();
    if (block.getType() != Material.SPAWNER) {
      return;
    }

    Player player = e.getPlayer();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();

    e.setDropItems(false);
    e.setExpToDrop(0);

    boolean silkNeeded = plugin.getConfig().getBoolean("spawner.silktouch-needed", true);
    boolean creative = player.getGameMode() == GameMode.CREATIVE;
    boolean hasSilk = hasSilkTouch(player.getInventory().getItemInMainHand());

    if (silkNeeded && !creative && !hasSilk) {
      SpawnerItem.sendConfiguredMessage(player, "messages.no-silktouch");
      return;
    }

    String spawnerId =
        SpawnerData.buildId(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    SpawnerData data = plugin.getSpawnerCache().get(spawnerId);

    String spawnerType = resolveSpawnerType(block, data);
    int packedAmount = resolvePackedAmount(block, data);
    Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);

    block.getWorld().dropItemNaturally(dropLoc, SpawnerItem.buildSpawnerItem(spawnerType, packedAmount));

    if (data != null) {
      List<ItemStack> stored = data.getInventory();
      for (ItemStack item : stored) {
        if (item != null) {
          block.getWorld().dropItemNaturally(dropLoc, item);
        }
      }

      if (data.getExperience() > 0) {
        int xp = (int) Math.min(data.getExperience(), Integer.MAX_VALUE);
        block.getWorld().spawn(dropLoc, ExperienceOrb.class, orb -> orb.setExperience(xp));
      }

      plugin.unregisterSpawner(spawnerId);
    }
  }

  private String resolveSpawnerType(Block block, SpawnerData data) {
    if (data != null && data.getSpawnerType() != null && !data.getSpawnerType().isEmpty()) {
      return data.getSpawnerType();
    }

    if (block.getState() instanceof CreatureSpawner cs) {
      String pdcType =
          cs.getPersistentDataContainer()
              .get(SpawnersPlugin.SPAWNER_TYPE_KEY, PersistentDataType.STRING);
      if (pdcType != null && !pdcType.isEmpty()) {
        return pdcType;
      }
      if (cs.getSpawnedType() != null) {
        return cs.getSpawnedType().name();
      }
    }

    return "PIG";
  }

  private int resolvePackedAmount(Block block, SpawnerData data) {
    if (data != null) {
      return Math.max(1, data.getAmount());
    }

    if (block.getState() instanceof CreatureSpawner cs) {
      Integer pdcAmount =
          cs.getPersistentDataContainer()
              .get(SpawnersPlugin.SPAWNER_AMOUNT_KEY, PersistentDataType.INTEGER);
      if (pdcAmount != null) {
        return Math.max(1, pdcAmount);
      }
    }

    return 1;
  }

  private boolean hasSilkTouch(ItemStack tool) {
    if (tool == null || tool.getType() == Material.AIR) {
      return false;
    }
    return tool.containsEnchantment(Enchantment.SILK_TOUCH);
  }
}
