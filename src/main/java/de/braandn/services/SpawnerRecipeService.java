package de.braandn.services;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerItem;
import de.braandn.utils.SpawnerTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

public class SpawnerRecipeService implements Listener {

  private final SpawnersPlugin plugin;
  private final Map<NamespacedKey, ConfiguredRecipe> configuredRecipes;
  private final Set<NamespacedKey> registeredRecipeKeys;
  private BaseInputMode baseInputMode;

  public SpawnerRecipeService(SpawnersPlugin plugin) {
    this.plugin = plugin;
    this.configuredRecipes = new LinkedHashMap<>();
    this.registeredRecipeKeys = ConcurrentHashMap.newKeySet();
    this.baseInputMode = BaseInputMode.VANILLA_OR_SINGLE_CUSTOM;
  }

  public void registerConfiguredRecipes() {
    for (NamespacedKey key : registeredRecipeKeys) {
      Bukkit.removeRecipe(key);
    }
    registeredRecipeKeys.clear();
    configuredRecipes.clear();

    ConfigurationSection section = plugin.getConfig().getConfigurationSection("recipes.type-change");
    if (section == null || !section.getBoolean("enabled", true)) {
      return;
    }

    baseInputMode = BaseInputMode.fromConfig(section.getString("allow-base-input"));

    ConfigurationSection overrides = section.getConfigurationSection("overrides");
    if (overrides != null) {
      for (String key : overrides.getKeys(false)) {
        ConfigurationSection recipeSection = overrides.getConfigurationSection(key);
        if (recipeSection == null || !recipeSection.getBoolean("enabled", true)) {
          continue;
        }

        ConfiguredRecipe recipe = parseRecipe("type_change_override_" + key, recipeSection, false);
        if (recipe != null) {
          registerRecipe(recipe);
        }
      }
    }

    ConfigurationSection main = section.getConfigurationSection("main");
    if (main != null && main.getBoolean("enabled", true)) {
      ConfiguredRecipe recipe = parseRecipe("type_change_main", main, true);
      if (recipe != null) {
        registerRecipe(recipe);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPrepareCraft(PrepareItemCraftEvent event) {
    ConfiguredRecipe recipe = resolveRecipe(event.getRecipe());
    if (recipe == null) {
      return;
    }

    ValidationResult result = validateRecipe(recipe, event.getInventory());
    event.getInventory().setResult(result.valid() ? SpawnerItem.buildSpawnerItem(result.resultType(), 1) : null);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    ConfiguredRecipe recipe = resolveRecipe(event.getRecipe());
    if (recipe == null) {
      return;
    }

    if (!(event.getInventory() instanceof CraftingInventory inventory)) {
      return;
    }

    ValidationResult result = validateRecipe(recipe, inventory);
    if (!result.valid()) {
      inventory.setResult(null);
      event.setCancelled(true);
      return;
    }

    ItemStack current = event.getCurrentItem();
    if (current == null
        || current.getType() != Material.SPAWNER
        || !SpawnerItem.isCustomSpawner(current)
        || !Objects.equals(SpawnerTypes.canonicalType(SpawnerItem.getSpawnerType(current)), result.resultType())
        || SpawnerItem.getSpawnerAmount(current) != 1) {
      event.setCurrentItem(SpawnerItem.buildSpawnerItem(result.resultType(), 1));
    }

    if (event.getWhoClicked() instanceof Player player) {
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory updated) {
                  ValidationResult refreshed = validateRecipe(recipe, updated);
                  updated.setResult(
                      refreshed.valid() ? SpawnerItem.buildSpawnerItem(refreshed.resultType(), 1) : null);
                }
              });
    }
  }

  private void registerRecipe(ConfiguredRecipe configuredRecipe) {
    Bukkit.removeRecipe(configuredRecipe.key());

    ShapedRecipe recipe =
        new ShapedRecipe(configuredRecipe.key(), SpawnerItem.buildSpawnerItem("PIG", 1));
    recipe.shape(configuredRecipe.shape());

    for (Map.Entry<Character, IngredientToken> entry : configuredRecipe.ingredients().entrySet()) {
      IngredientToken token = entry.getValue();
      switch (token.kind()) {
        case MATERIAL -> recipe.setIngredient(entry.getKey(), token.material());
        case ANY_SPAWN_EGG ->
            recipe.setIngredient(entry.getKey(), new RecipeChoice.MaterialChoice(SpawnerTypes.spawnEggMaterials()));
        case SPAWNER_BASE -> recipe.setIngredient(entry.getKey(), Material.SPAWNER);
        case AIR -> {
        }
      }
    }

    Bukkit.addRecipe(recipe);
    configuredRecipes.put(configuredRecipe.key(), configuredRecipe);
    registeredRecipeKeys.add(configuredRecipe.key());
  }

  private ConfiguredRecipe parseRecipe(
      String keyName, ConfigurationSection section, boolean deriveResultTypeFromEgg) {
    List<String> rawShape = section.getStringList("shape");
    if (rawShape.size() != 3) {
      plugin.getLogger().warning("Recipe " + keyName + " must have exactly 3 shape rows.");
      return null;
    }

    ConfigurationSection ingredientsSection = section.getConfigurationSection("ingredients");
    if (ingredientsSection == null) {
      plugin.getLogger().warning("Recipe " + keyName + " is missing ingredients.");
      return null;
    }

    Map<Character, IngredientToken> ingredients = new LinkedHashMap<>();
    for (String rawKey : ingredientsSection.getKeys(false)) {
      if (rawKey == null || rawKey.length() != 1) {
        plugin.getLogger().warning("Recipe " + keyName + " has an invalid ingredient key: " + rawKey);
        continue;
      }

      char symbol = rawKey.charAt(0);
      IngredientToken token = parseIngredientToken(ingredientsSection.getString(rawKey));
      if (token == null) {
        plugin.getLogger().warning("Recipe " + keyName + " has an invalid ingredient for " + rawKey);
        continue;
      }
      ingredients.put(symbol, token);
    }

    if (ingredients.isEmpty()) {
      plugin.getLogger().warning("Recipe " + keyName + " has no valid ingredients.");
      return null;
    }

    int baseSlots = 0;
    int eggSlots = 0;
    String[] shape = new String[3];

    for (int row = 0; row < rawShape.size(); row++) {
      String rawRow = rawShape.get(row);
      if (rawRow.length() != 3) {
        plugin.getLogger().warning("Recipe " + keyName + " rows must be exactly 3 characters.");
        return null;
      }

      StringBuilder normalizedRow = new StringBuilder();
      for (int column = 0; column < rawRow.length(); column++) {
        char symbol = rawRow.charAt(column);
        if (symbol == ' ') {
          normalizedRow.append(' ');
          continue;
        }

        IngredientToken token = ingredients.get(symbol);
        if (token == null) {
          plugin.getLogger().warning("Recipe " + keyName + " references unknown ingredient " + symbol);
          return null;
        }

        if (token.kind() == IngredientKind.AIR) {
          normalizedRow.append(' ');
          continue;
        }

        if (token.kind() == IngredientKind.SPAWNER_BASE) {
          baseSlots++;
        }
        if (token.kind() == IngredientKind.ANY_SPAWN_EGG) {
          eggSlots++;
        }

        normalizedRow.append(symbol);
      }
      shape[row] = normalizedRow.toString();
    }

    if (baseSlots != 1) {
      plugin.getLogger().warning("Recipe " + keyName + " must contain exactly one SPAWNER_BASE slot.");
      return null;
    }

    if (deriveResultTypeFromEgg && eggSlots != 1) {
      plugin.getLogger().warning("Main type-change recipe must contain exactly one ANY_SPAWN_EGG slot.");
      return null;
    }

    if (!deriveResultTypeFromEgg && eggSlots > 1) {
      plugin.getLogger().warning("Recipe " + keyName + " can contain at most one ANY_SPAWN_EGG slot.");
      return null;
    }

    String fixedResultType = null;
    if (!deriveResultTypeFromEgg) {
      fixedResultType = SpawnerTypes.canonicalType(section.getString("result-type", ""));
      if (!SpawnerTypes.isValidSpawnerType(fixedResultType)) {
        plugin.getLogger().warning("Recipe " + keyName + " has invalid result-type: " + fixedResultType);
        return null;
      }
    }

    return new ConfiguredRecipe(
        new NamespacedKey(plugin, keyName.toLowerCase(Locale.ROOT)),
        shape,
        Map.copyOf(ingredients),
        deriveResultTypeFromEgg,
        fixedResultType);
  }

  private IngredientToken parseIngredientToken(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }

    String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "AIR" -> new IngredientToken(IngredientKind.AIR, null);
      case "ANY_SPAWN_EGG" -> new IngredientToken(IngredientKind.ANY_SPAWN_EGG, null);
      case "SPAWNER_BASE" -> new IngredientToken(IngredientKind.SPAWNER_BASE, null);
      default -> {
        try {
          yield new IngredientToken(IngredientKind.MATERIAL, Material.valueOf(normalized));
        } catch (IllegalArgumentException ex) {
          yield null;
        }
      }
    };
  }

  private ConfiguredRecipe resolveRecipe(Recipe recipe) {
    if (!(recipe instanceof Keyed keyed)) {
      return null;
    }
    return configuredRecipes.get(keyed.getKey());
  }

  private ValidationResult validateRecipe(ConfiguredRecipe recipe, CraftingInventory inventory) {
    ItemStack[] matrix = inventory.getMatrix();
    ItemStack spawnerBase = null;
    ItemStack eggItem = null;

    for (ItemStack item : matrix) {
      if (item == null || item.getType().isAir()) {
        continue;
      }

      if (item.getType() == Material.SPAWNER && spawnerBase == null) {
        spawnerBase = item;
      }

      if (SpawnerTypes.isSpawnEgg(item.getType()) && eggItem == null) {
        eggItem = item;
      }
    }

    if (!isAllowedBaseSpawner(spawnerBase)) {
      return ValidationResult.invalid();
    }

    if (recipe.deriveResultTypeFromEgg()) {
      String eggType = eggItem == null ? null : SpawnerTypes.fromSpawnEgg(eggItem.getType());
      if (!SpawnerTypes.isValidSpawnerType(eggType)) {
        return ValidationResult.invalid();
      }
      return ValidationResult.valid(SpawnerTypes.canonicalType(eggType));
    }

    return ValidationResult.valid(recipe.fixedResultType());
  }

  private boolean isAllowedBaseSpawner(ItemStack item) {
    if (item == null || item.getType() != Material.SPAWNER || item.getAmount() != 1) {
      return false;
    }

    boolean custom = SpawnerItem.isCustomSpawner(item);
    int packedAmount = custom ? SpawnerItem.getSpawnerAmount(item) : 1;

    return switch (baseInputMode) {
      case VANILLA_ONLY -> !custom;
      case VANILLA_OR_SINGLE_CUSTOM -> !custom || packedAmount == 1;
      case CUSTOM_ONLY -> custom && packedAmount == 1;
    };
  }

  private enum IngredientKind {
    MATERIAL,
    AIR,
    ANY_SPAWN_EGG,
    SPAWNER_BASE
  }

  private enum BaseInputMode {
    VANILLA_ONLY,
    VANILLA_OR_SINGLE_CUSTOM,
    CUSTOM_ONLY;

    private static BaseInputMode fromConfig(String raw) {
      if (raw == null || raw.isBlank()) {
        return VANILLA_OR_SINGLE_CUSTOM;
      }

      try {
        return BaseInputMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return VANILLA_OR_SINGLE_CUSTOM;
      }
    }
  }

  private record IngredientToken(IngredientKind kind, Material material) {}

  private record ConfiguredRecipe(
      NamespacedKey key,
      String[] shape,
      Map<Character, IngredientToken> ingredients,
      boolean deriveResultTypeFromEgg,
      String fixedResultType) {}

  private record ValidationResult(boolean valid, String resultType) {
    private static ValidationResult valid(String resultType) {
      return new ValidationResult(true, resultType);
    }

    private static ValidationResult invalid() {
      return new ValidationResult(false, null);
    }
  }
}
