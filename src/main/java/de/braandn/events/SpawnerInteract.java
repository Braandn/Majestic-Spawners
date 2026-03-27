package de.braandn.events;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SpawnerInteract implements Listener {

  @EventHandler(priority = EventPriority.HIGH)
  public void onInteract(PlayerInteractEvent e) {
    if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }

    Block block = e.getClickedBlock();
    if (block == null || block.getType() != Material.SPAWNER) {
      return;
    }

    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    String spawnerId =
        SpawnerData.buildId(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    SpawnerData data = plugin.getSpawnerCache().get(spawnerId);
    if (data == null) {
      return;
    }

    e.setCancelled(true);

    Player player = e.getPlayer();
    boolean isOwner = player.getUniqueId().toString().equals(data.getOwnerUuid());
    String access = plugin.getConfig().getString("spawner.non-owner-access", "normal");
    if (!isOwner && access.equalsIgnoreCase("blocked")) {
      SpawnerItem.sendConfiguredMessage(player, "messages.not-owner");
      return;
    }

    ItemStack hand = player.getInventory().getItemInMainHand();
    if (SpawnerItem.isCustomSpawner(hand)
        && data.getSpawnerType().equals(SpawnerItem.getSpawnerType(hand))) {
      if (!canModify(isOwner, access)) {
        SpawnerItem.sendConfiguredMessage(player, "messages.view-only");
        return;
      }

      SpawnerGUI.openConfirmGui(player, data, SpawnerItem.getSpawnerAmount(hand));
      return;
    }

    SpawnerGUI.openMainGui(player, data, isOwner);
  }

  private boolean canModify(boolean isOwner, String access) {
    return isOwner || "normal".equalsIgnoreCase(access);
  }
}
