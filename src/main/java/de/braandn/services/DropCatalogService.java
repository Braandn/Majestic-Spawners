package de.braandn.services;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerTypes;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public class DropCatalogService {

  private final SpawnersPlugin plugin;
  private final File file;
  private final Map<String, List<ConfiguredDrop>> configuredDrops;

  public DropCatalogService(SpawnersPlugin plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "drops.yml");
    this.configuredDrops = new HashMap<>();
  }

  public void load() {
    configuredDrops.clear();

    if (!file.exists()) {
      plugin.getLogger().warning("drops.yml is missing; using emergency fallback drops.");
      return;
    }

    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.load(file);
    } catch (IOException | InvalidConfigurationException ex) {
      plugin.getLogger().warning("Could not load drops.yml; using emergency fallback drops.");
      ex.printStackTrace();
      return;
    }

    ConfigurationSection root = yaml.getConfigurationSection("drops");
    if (root == null) {
      root = yaml;
    }

    for (String key : root.getKeys(false)) {
      Object raw = root.get(key);
      String normalizedType = SpawnerTypes.canonicalType(key);

      if (!(raw instanceof List<?> entries)) {
        plugin.getLogger().warning("drops.yml: " + key + " must be a list.");
        continue;
      }

      if (entries.isEmpty()) {
        configuredDrops.put(normalizedType, List.of());
        continue;
      }

      List<ConfiguredDrop> parsed = new ArrayList<>();
      for (Object entry : entries) {
        if (!(entry instanceof Map<?, ?> values)) {
          plugin.getLogger().warning("drops.yml: invalid drop entry for " + key + ".");
          continue;
        }

        ConfiguredDrop drop = parseConfiguredDrop(key, values);
        if (drop != null) {
          parsed.add(drop);
        }
      }

      if (!parsed.isEmpty()) {
        configuredDrops.put(normalizedType, List.copyOf(parsed));
      }
    }
  }

  public Collection<ItemStack> generateDrops(String spawnerType, ThreadLocalRandom random) {
    String normalizedType = SpawnerTypes.canonicalType(spawnerType);
    if (!configuredDrops.containsKey(normalizedType)) {
      return null;
    }

    List<ConfiguredDrop> entries = configuredDrops.get(normalizedType);
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }

    List<ItemStack> drops = new ArrayList<>();
    for (ConfiguredDrop entry : entries) {
      if (!entry.shouldDrop(random)) {
        continue;
      }

      int amount = entry.amount().roll(random);
      if (amount <= 0) {
        continue;
      }

      drops.add(new ItemStack(entry.material(), amount));
    }
    return drops;
  }

  private ConfiguredDrop parseConfiguredDrop(String type, Map<?, ?> values) {
    String materialName = readString(values.get("material"));
    Material material = parseMaterial(materialName);
    if (material == null || material.isAir() || !material.isItem()) {
      plugin.getLogger().warning("drops.yml: invalid material '" + materialName + "' for " + type);
      return null;
    }

    Object amountValue = values.containsKey("amount") ? values.get("amount") : 1;
    IntRange amount = parseRange(amountValue);
    if (amount == null) {
      plugin.getLogger().warning("drops.yml: invalid amount for " + type + " / " + material);
      return null;
    }

    double chance = parseChance(values.get("chance"));
    if (chance <= 0.0d) {
      return null;
    }

    return new ConfiguredDrop(material, amount, chance);
  }

  private Material parseMaterial(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }

    try {
      return Material.valueOf(name.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private IntRange parseRange(Object raw) {
    if (raw instanceof Number number) {
      int value = Math.max(0, number.intValue());
      return new IntRange(value, value);
    }

    if (raw instanceof ConfigurationSection section) {
      Integer min = readInt(section.get("min"));
      Integer max = readInt(section.get("max"));
      if (min != null || max != null) {
        int low = min != null ? min : (max != null ? max : 0);
        int high = max != null ? max : low;
        return new IntRange(low, high);
      }
      return null;
    }

    if (raw instanceof Map<?, ?> map) {
      Integer min = readInt(map.get("min"));
      Integer max = readInt(map.get("max"));
      if (min != null || max != null) {
        int low = min != null ? min : (max != null ? max : 0);
        int high = max != null ? max : low;
        return new IntRange(low, high);
      }
      return null;
    }

    if (raw instanceof List<?> list && !list.isEmpty()) {
      Integer min = readInt(list.get(0));
      Integer max = list.size() > 1 ? readInt(list.get(1)) : min;
      if (min != null && max != null) {
        return new IntRange(min, max);
      }
      return null;
    }

    if (raw instanceof String string) {
      String value = string.trim();
      if (value.isEmpty()) {
        return null;
      }

      if (value.contains("-")) {
        String[] parts = value.split("-", 2);
        Integer min = readInt(parts[0]);
        Integer max = readInt(parts[1]);
        if (min != null && max != null) {
          return new IntRange(min, max);
        }
      }

      if (value.contains("..")) {
        String[] parts = value.split("\\.\\.", 2);
        Integer min = readInt(parts[0]);
        Integer max = readInt(parts[1]);
        if (min != null && max != null) {
          return new IntRange(min, max);
        }
      }

      Integer single = readInt(value);
      if (single != null) {
        return new IntRange(single, single);
      }
    }

    return null;
  }

  private double parseChance(Object raw) {
    if (raw == null) {
      return 100.0d;
    }

    Double value = readDouble(raw);
    if (value == null) {
      return 100.0d;
    }

    double chance = value;
    if (chance >= 0.0d && chance <= 1.0d) {
      chance *= 100.0d;
    }
    return Math.max(0.0d, Math.min(100.0d, chance));
  }

  private Double readDouble(Object raw) {
    if (raw instanceof Number number) {
      return number.doubleValue();
    }

    if (raw instanceof String string) {
      String value = string.trim().replace("%", "");
      if (value.isEmpty()) {
        return null;
      }

      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    return null;
  }

  private Integer readInt(Object raw) {
    if (raw instanceof Number number) {
      return Math.max(0, number.intValue());
    }

    if (raw instanceof String string) {
      try {
        return Math.max(0, Integer.parseInt(string.trim()));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    return null;
  }

  private String readString(Object raw) {
    return raw == null ? null : String.valueOf(raw);
  }

  private record ConfiguredDrop(Material material, IntRange amount, double chance) {
    private boolean shouldDrop(ThreadLocalRandom random) {
      if (chance >= 100.0d) {
        return true;
      }
      return random.nextDouble(100.0d) < chance;
    }
  }

  private static final class IntRange {
    private final int min;
    private final int max;

    private IntRange(int min, int max) {
      int low = Math.max(0, Math.min(min, max));
      int high = Math.max(low, Math.max(min, max));
      this.min = low;
      this.max = high;
    }

    private int roll(ThreadLocalRandom random) {
      if (max <= min) {
        return min;
      }
      return min + random.nextInt(max - min + 1);
    }
  }
}
