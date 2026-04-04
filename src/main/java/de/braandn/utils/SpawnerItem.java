package de.braandn.utils;

import de.braandn.SpawnersPlugin;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class SpawnerItem {

  public static ItemStack buildSpawnerItem(String spawnerType) {
    return buildSpawnerItem(spawnerType, 1);
  }

  public static ItemStack buildSpawnerItem(String spawnerType, int spawnerAmount) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    String normalizedType = SpawnerTypes.canonicalType(spawnerType);
    ItemStack item = new ItemStack(Material.SPAWNER);
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return item;
    }

    int safeAmount = Math.max(1, spawnerAmount);

    String rawName = plugin.getConfig().getString("spawner-item.name", "&b%type% &7Spawner");
    meta.setDisplayName(
        color(
            rawName
                .replace("%type%", formatType(normalizedType))
                .replace("%amount%", String.valueOf(safeAmount))));

    List<String> lore =
        plugin.getConfig().getStringList("spawner-item.lore").stream()
            .map(
                line ->
                    color(
                        line.replace("%type%", formatType(normalizedType))
                            .replace("%amount%", String.valueOf(safeAmount))))
            .collect(Collectors.toList());
    if (!lore.isEmpty()) {
      meta.setLore(lore);
    }

    meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

    meta.getPersistentDataContainer()
        .set(SpawnersPlugin.SPAWNER_TYPE_KEY, PersistentDataType.STRING, normalizedType);
    meta.getPersistentDataContainer()
        .set(SpawnersPlugin.SPAWNER_AMOUNT_KEY, PersistentDataType.INTEGER, safeAmount);

    item.setItemMeta(meta);
    return item;
  }

  public static boolean isCustomSpawner(ItemStack item) {
    if (item == null || item.getType() != Material.SPAWNER) {
      return false;
    }

    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return false;
    }

    return meta.getPersistentDataContainer()
        .has(SpawnersPlugin.SPAWNER_TYPE_KEY, PersistentDataType.STRING);
  }

  public static String getSpawnerType(ItemStack item) {
    if (!isCustomSpawner(item)) {
      return null;
    }

    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return null;
    }

    return meta.getPersistentDataContainer()
        .get(SpawnersPlugin.SPAWNER_TYPE_KEY, PersistentDataType.STRING);
  }

  public static int getSpawnerAmount(ItemStack item) {
    if (!isCustomSpawner(item)) {
      return 1;
    }

    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return 1;
    }

    Integer amount =
        meta.getPersistentDataContainer()
            .get(SpawnersPlugin.SPAWNER_AMOUNT_KEY, PersistentDataType.INTEGER);
    return amount == null ? 1 : Math.max(1, amount);
  }

  public static void applyPlacedSpawnerData(
      CreatureSpawner spawner, String type, int amount, String ownerUuid) {
    if (spawner == null) {
      return;
    }

    String normalizedType = SpawnerTypes.canonicalType(type);
    spawner
        .getPersistentDataContainer()
        .set(SpawnersPlugin.PLACED_CUSTOM_KEY, PersistentDataType.BYTE, (byte) 1);
    spawner
        .getPersistentDataContainer()
        .set(SpawnersPlugin.SPAWNER_TYPE_KEY, PersistentDataType.STRING, normalizedType);
    spawner
        .getPersistentDataContainer()
        .set(SpawnersPlugin.SPAWNER_AMOUNT_KEY, PersistentDataType.INTEGER, Math.max(1, amount));
    spawner
        .getPersistentDataContainer()
        .set(
            SpawnersPlugin.PLACED_OWNER_KEY,
            PersistentDataType.STRING,
            ownerUuid == null ? "" : ownerUuid);
  }

  public static boolean isCustomSpawnerBlock(CreatureSpawner spawner) {
    if (spawner == null) {
      return false;
    }

    Byte marker =
        spawner
            .getPersistentDataContainer()
            .get(SpawnersPlugin.PLACED_CUSTOM_KEY, PersistentDataType.BYTE);
    return marker != null && marker == (byte) 1;
  }

  public static void updatePlacedSpawnerAmount(Block block, int amount) {
    if (block == null || block.getType() != Material.SPAWNER) {
      return;
    }
    if (!(block.getState() instanceof CreatureSpawner cs)) {
      return;
    }

    cs.getPersistentDataContainer()
        .set(SpawnersPlugin.SPAWNER_AMOUNT_KEY, PersistentDataType.INTEGER, Math.max(1, amount));
    cs.update(true);
  }

  public static String getConfiguredMessage(String path) {
    String raw = SpawnersPlugin.getInstance().getConfig().getString(path);
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    return color(raw);
  }

  public static void sendConfiguredMessage(Player player, String path) {
    sendConfiguredMessage(player, path, null);
  }

  public static void sendConfiguredMessage(
      Player player, String path, UnaryOperator<String> formatter) {
    if (player == null || path == null || path.trim().isEmpty()) {
      return;
    }

    String raw = SpawnersPlugin.getInstance().getConfig().getString(path);
    if (raw == null || raw.trim().isEmpty()) {
      return;
    }

    String formatted = formatter != null ? formatter.apply(raw) : raw;
    if (formatted == null || formatted.trim().isEmpty()) {
      return;
    }

    player.sendMessage(color(formatted));
  }

  public static String formatType(String type) {
    if (type == null || type.isEmpty()) {
      return "Unknown";
    }

    return Arrays.stream(type.split("_"))
        .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
        .collect(Collectors.joining(" "));
  }

  public static String color(String value) {
    return ChatColor.translateAlternateColorCodes('&', value);
  }
}
