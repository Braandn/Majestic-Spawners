package de.braandn.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

public final class SpawnerTypes {

  private static final String SPAWN_EGG_SUFFIX = "_SPAWN_EGG";

  private static final Map<String, String> TYPE_ALIASES =
      Map.of(
          "MUSHROOM_COW", "MOOSHROOM",
          "PIG_ZOMBIE", "ZOMBIFIED_PIGLIN",
          "SNOWMAN", "SNOW_GOLEM");

  private static final List<Material> SPAWN_EGGS =
      Arrays.stream(Material.values())
          .filter(Material::isItem)
          .filter(material -> material.name().endsWith(SPAWN_EGG_SUFFIX))
          .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
          .toList();

  private static final List<String> SUPPORTED_SPAWNER_TYPES =
      Arrays.stream(EntityType.values())
          .filter(type -> type != EntityType.UNKNOWN)
          .filter(type -> type != EntityType.PLAYER)
          .filter(EntityType::isAlive)
          .map(EntityType::name)
          .sorted(String::compareToIgnoreCase)
          .toList();

  private SpawnerTypes() {}

  public static String normalize(String type) {
    return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
  }

  public static String canonicalType(String type) {
    String normalized = normalize(type);
    return TYPE_ALIASES.getOrDefault(normalized, normalized);
  }

  public static String lootTableType(String type) {
    return canonicalType(type);
  }

  public static boolean isValidSpawnerType(String type) {
    if (canonicalType(type).isBlank()) {
      return false;
    }

    try {
      EntityType.valueOf(canonicalType(type));
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public static String fromSpawnEgg(Material material) {
    if (material == null) {
      return null;
    }

    String name = material.name();
    if (!name.endsWith(SPAWN_EGG_SUFFIX)) {
      return null;
    }

    return canonicalType(name.substring(0, name.length() - SPAWN_EGG_SUFFIX.length()));
  }

  public static boolean isSpawnEgg(Material material) {
    return fromSpawnEgg(material) != null;
  }

  public static List<Material> spawnEggMaterials() {
    return SPAWN_EGGS;
  }

  public static List<String> supportedSpawnerTypes() {
    return SUPPORTED_SPAWNER_TYPES;
  }

  public static boolean isSupportedSpawnerType(String type) {
    return SUPPORTED_SPAWNER_TYPES.contains(canonicalType(type));
  }
}
