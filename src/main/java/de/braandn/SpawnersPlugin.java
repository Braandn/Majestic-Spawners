package de.braandn;

import de.braandn.commands.MajesticSpawnersCommand;
import de.braandn.events.ChunksLoading;
import de.braandn.events.SpawnerBreak;
import de.braandn.events.SpawnerGUI;
import de.braandn.events.SpawnerInteract;
import de.braandn.events.SpawnerPlace;
import de.braandn.events.SpawnerSpawnControl;
import de.braandn.services.DropCatalogService;
import de.braandn.services.SpawnerRecipeService;
import de.braandn.storage.DatabaseStorage;
import de.braandn.storage.SpawnerPersistenceService;
import de.braandn.storage.SpawnerRecord;
import de.braandn.utils.ChunkKey;
import de.braandn.utils.GUISession;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerTypes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;

public class SpawnersPlugin extends JavaPlugin {

  private static SpawnersPlugin instance;

  public static NamespacedKey SPAWNER_TYPE_KEY;
  public static NamespacedKey SPAWNER_AMOUNT_KEY;
  public static NamespacedKey PLACED_CUSTOM_KEY;
  public static NamespacedKey PLACED_OWNER_KEY;

  private Map<String, SpawnerData> spawnerCache;
  private Map<ChunkKey, Set<String>> chunkIndex;
  private Map<String, LootTable> lootTableCache;
  private Map<String, IntRange> xpRangeCache;
  private Set<String> fallbackLootTypes;
  private Set<String> warnedLootTypes;
  private Map<UUID, GUISession> guiSessions;
  private Map<String, Set<UUID>> guiSessionsBySpawner;
  private Map<UUID, Map<String, String>> storageFilterPreferences;
  private Set<String> dirtySpawners;
  private List<String> activeSpawnerOrder;
  private Map<String, Integer> activeSpawnerPositions;
  private DropCatalogService dropCatalogService;
  private SpawnerRecipeService spawnerRecipeService;
  private SpawnerPersistenceService persistenceService;
  private int processingTaskId = -1;
  private int saveTaskId = -1;
  private int processingCursor = 0;
  private double processingBudget = 0.0d;
  private long currentProcessingTick = 0L;

  public static SpawnersPlugin getInstance() {
    return instance;
  }

  @Override
  public void onEnable() {
    instance = this;
    saveDefaultConfig();
    saveResource("drops.yml", false);

    SPAWNER_TYPE_KEY = new NamespacedKey(this, "spawner_type");
    SPAWNER_AMOUNT_KEY = new NamespacedKey(this, "spawner_amount");
    PLACED_CUSTOM_KEY = new NamespacedKey(this, "placed_custom");
    PLACED_OWNER_KEY = new NamespacedKey(this, "placed_owner");

    dropCatalogService = new DropCatalogService(this);
    dropCatalogService.load();
    spawnerRecipeService = new SpawnerRecipeService(this);

    spawnerCache = new ConcurrentHashMap<>();
    chunkIndex = new ConcurrentHashMap<>();
    lootTableCache = new ConcurrentHashMap<>();
    xpRangeCache = buildXpRangeCache();
    fallbackLootTypes = ConcurrentHashMap.newKeySet();
    warnedLootTypes = ConcurrentHashMap.newKeySet();
    guiSessions = new ConcurrentHashMap<>();
    guiSessionsBySpawner = new ConcurrentHashMap<>();
    storageFilterPreferences = new ConcurrentHashMap<>();
    dirtySpawners = ConcurrentHashMap.newKeySet();
    activeSpawnerOrder = new ArrayList<>();
    activeSpawnerPositions = new HashMap<>();
    persistenceService = new SpawnerPersistenceService(this);

    if (!loadPersistedSpawners()) {
      getLogger().severe("Could not open database - disabling plugin.");
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }

    Bukkit.getPluginManager().registerEvents(new ChunksLoading(), this);
    Bukkit.getPluginManager().registerEvents(new SpawnerBreak(), this);
    Bukkit.getPluginManager().registerEvents(new SpawnerPlace(), this);
    Bukkit.getPluginManager().registerEvents(new SpawnerInteract(), this);
    Bukkit.getPluginManager().registerEvents(new SpawnerGUI(), this);
    Bukkit.getPluginManager().registerEvents(new SpawnerSpawnControl(), this);
    Bukkit.getPluginManager().registerEvents(spawnerRecipeService, this);

    if (getCommand("majesticspawners") != null) {
      MajesticSpawnersCommand command = new MajesticSpawnersCommand(this);
      getCommand("majesticspawners").setExecutor(command);
      getCommand("majesticspawners").setTabCompleter(command);
    }

    spawnerRecipeService.registerConfiguredRecipes();
    activateSpawnersInLoadedChunks();
    scheduleProcessingTask();
    scheduleSaveTask();
  }

  @Override
  public void onDisable() {
    cancelProcessingTask();
    cancelSaveTask();

    if (persistenceService != null) {
      persistenceService.shutdownAndFlush(snapshotDirtySpawners(true));
      persistenceService = null;
    }
  }

  public void startTicker(SpawnerData data) {
    if (data == null || !isSpawnerChunkLoaded(data)) {
      return;
    }

    String spawnerId = data.getSpawnerId();
    if (activeSpawnerPositions.containsKey(spawnerId)) {
      return;
    }

    data.setLastProcessingTick(currentProcessingTick);
    activeSpawnerPositions.put(spawnerId, activeSpawnerOrder.size());
    activeSpawnerOrder.add(spawnerId);
  }

  public void stopTicker(String spawnerId) {
    Integer removedIndex = activeSpawnerPositions.remove(spawnerId);
    if (removedIndex == null) {
      return;
    }

    int lastIndex = activeSpawnerOrder.size() - 1;
    String lastSpawnerId = activeSpawnerOrder.remove(lastIndex);
    if (removedIndex < lastIndex) {
      activeSpawnerOrder.set(removedIndex, lastSpawnerId);
      activeSpawnerPositions.put(lastSpawnerId, removedIndex);
    }

    if (activeSpawnerOrder.isEmpty()) {
      processingCursor = 0;
      processingBudget = 0.0d;
      return;
    }

    if (processingCursor > lastIndex) {
      processingCursor = 0;
    } else if (removedIndex < processingCursor) {
      processingCursor--;
    }

    if (processingCursor < 0 || processingCursor >= activeSpawnerOrder.size()) {
      processingCursor = 0;
    }
  }

  public void reloadPluginState() {
    reloadConfig();
    saveResource("drops.yml", false);

    lootTableCache.clear();
    fallbackLootTypes.clear();
    warnedLootTypes.clear();
    xpRangeCache = buildXpRangeCache();
    dropCatalogService.load();
    spawnerRecipeService.registerConfiguredRecipes();

    scheduleProcessingTask();
    scheduleSaveTask();
    refreshOpenSessionsFromConfig();
  }

  public void flushDirty() {
    if (persistenceService == null) {
      return;
    }

    persistenceService.queueUpserts(snapshotDirtySpawners(false));
  }

  public Map<String, SpawnerData> getSpawnerCache() {
    return spawnerCache;
  }

  public Map<UUID, GUISession> getGUISessions() {
    return guiSessions;
  }

  public GUISession getGuiSession(UUID playerId) {
    return guiSessions.get(playerId);
  }

  public void trackGuiSession(UUID playerId, GUISession session) {
    GUISession previous = guiSessions.put(playerId, session);
    if (previous != null) {
      unindexGuiSession(playerId, previous);
    }
    guiSessionsBySpawner
        .computeIfAbsent(session.getSpawnerId(), key -> ConcurrentHashMap.newKeySet())
        .add(playerId);
  }

  public void clearGuiSession(UUID playerId) {
    GUISession removed = guiSessions.remove(playerId);
    if (removed != null) {
      unindexGuiSession(playerId, removed);
    }
  }

  public Set<UUID> getGuiViewers(String spawnerId) {
    Set<UUID> viewers = guiSessionsBySpawner.get(spawnerId);
    if (viewers == null || viewers.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(viewers);
  }

  public String getStoredStorageFilter(UUID playerId, String spawnerId) {
    Map<String, String> perSpawner = storageFilterPreferences.get(playerId);
    if (perSpawner == null) {
      return null;
    }
    return perSpawner.get(spawnerId);
  }

  public void rememberStorageFilter(UUID playerId, String spawnerId, String selectedFilter) {
    if (playerId == null || spawnerId == null || spawnerId.isBlank()) {
      return;
    }

    storageFilterPreferences
        .computeIfAbsent(playerId, key -> new ConcurrentHashMap<>())
        .put(spawnerId, selectedFilter);
  }

  public int getMaxSpawnerStack() {
    return Math.max(1, getConfig().getInt("spawner.max-spawner-stack", 500));
  }

  public boolean registerSpawner(SpawnerData data) {
    if (data == null) {
      return false;
    }

    attachSpawner(data);
    SpawnerData existing = spawnerCache.putIfAbsent(data.getSpawnerId(), data);
    if (existing != null) {
      return false;
    }

    indexSpawner(data);
    if (persistenceService != null) {
      persistenceService.queueUpsert(data.snapshot());
    }
    return true;
  }

  public void unregisterSpawner(String spawnerId) {
    stopTicker(spawnerId);
    dirtySpawners.remove(spawnerId);

    SpawnerData removed = spawnerCache.remove(spawnerId);
    if (removed != null) {
      unindexSpawner(removed);
    }

    if (persistenceService != null) {
      persistenceService.queueDelete(spawnerId);
    }

    for (Map<String, String> perSpawner : storageFilterPreferences.values()) {
      perSpawner.remove(spawnerId);
    }
  }

  public List<SpawnerData> getSpawnersInChunk(String world, int chunkX, int chunkZ) {
    Set<String> spawnerIds = chunkIndex.get(new ChunkKey(world, chunkX, chunkZ));
    if (spawnerIds == null || spawnerIds.isEmpty()) {
      return List.of();
    }

    List<SpawnerData> result = new ArrayList<>(spawnerIds.size());
    for (String spawnerId : spawnerIds) {
      SpawnerData data = spawnerCache.get(spawnerId);
      if (data != null) {
        result.add(data);
      }
    }
    return result;
  }

  public void flushSpawner(SpawnerData data) {
    if (data == null || !data.isDirty() || !data.shouldQueuePersistence() || persistenceService == null) {
      return;
    }

    SpawnerRecord snapshot = data.snapshot();
    data.markQueuedForPersistence(snapshot.version());
    persistenceService.queueUpsert(snapshot);
  }

  public void markPersisted(String spawnerId, long version) {
    SpawnerData data = spawnerCache.get(spawnerId);
    if (data == null) {
      return;
    }

    data.markPersisted(version);
    if (!data.isDirty()) {
      dirtySpawners.remove(spawnerId);
    }
  }

  private boolean loadPersistedSpawners() {
    try (Connection connection = DatabaseStorage.openConnection(getDataFolder(), getLogger())) {
      if (connection == null) {
        return false;
      }

      Map<String, SpawnerData> loaded = DatabaseStorage.readAllSpawners(connection);
      spawnerCache.putAll(loaded);
      for (SpawnerData data : loaded.values()) {
        attachSpawner(data);
        indexSpawner(data);
      }
      return true;
    } catch (SQLException ex) {
      getLogger().severe("Could not close database connection cleanly after load.");
      ex.printStackTrace();
      return false;
    }
  }

  private void attachSpawner(SpawnerData data) {
    data.setDirtyListener(() -> dirtySpawners.add(data.getSpawnerId()));
  }

  private void indexSpawner(SpawnerData data) {
    chunkIndex
        .computeIfAbsent(
            new ChunkKey(data.getWorld(), data.getChunkX(), data.getChunkZ()),
            key -> ConcurrentHashMap.newKeySet())
        .add(data.getSpawnerId());
  }

  private void unindexSpawner(SpawnerData data) {
    ChunkKey key = new ChunkKey(data.getWorld(), data.getChunkX(), data.getChunkZ());
    Set<String> spawnerIds = chunkIndex.get(key);
    if (spawnerIds == null) {
      return;
    }

    spawnerIds.remove(data.getSpawnerId());
    if (spawnerIds.isEmpty()) {
      chunkIndex.remove(key);
    }
  }

  private boolean isSpawnerChunkLoaded(SpawnerData data) {
    World world = Bukkit.getWorld(data.getWorld());
    return world != null && world.isChunkLoaded(data.getChunkX(), data.getChunkZ());
  }

  private void activateSpawnersInLoadedChunks() {
    processingCursor = 0;
    processingBudget = 0.0d;
    for (SpawnerData data : spawnerCache.values()) {
      if (isSpawnerChunkLoaded(data)) {
        startTicker(data);
      }
    }
  }

  private void processActiveSpawners() {
    currentProcessingTick++;
    int activeCount = activeSpawnerOrder.size();
    if (activeCount == 0) {
      processingBudget = 0.0d;
      return;
    }

    long processingInterval =
        Math.max(1L, getConfig().getLong("spawner.processing-interval-ticks", 20L));
    processingBudget += activeCount / (double) processingInterval;

    int batchSize = Math.min(activeCount, (int) Math.floor(processingBudget));
    if (batchSize <= 0) {
      return;
    }
    processingBudget -= batchSize;

    int processed = 0;
    while (processed < batchSize && !activeSpawnerOrder.isEmpty()) {
      if (processingCursor >= activeSpawnerOrder.size()) {
        processingCursor = 0;
      }

      String spawnerId = activeSpawnerOrder.get(processingCursor);
      processingCursor++;
      processed++;

      SpawnerData data = spawnerCache.get(spawnerId);
      if (data == null) {
        stopTicker(spawnerId);
        continue;
      }

      long lastTick = data.getLastProcessingTick();
      long elapsedTicks =
          lastTick <= 0L ? processingInterval : Math.max(1L, currentProcessingTick - lastTick);
      data.setLastProcessingTick(currentProcessingTick);
      tickSpawner(data, elapsedTicks);
    }
  }

  private void tickSpawner(SpawnerData data, long elapsedTicks) {
    if (data == null) {
      return;
    }

    int stackAmount = data.getAmount();
    if (stackAmount <= 0) {
      return;
    }

    World world = Bukkit.getWorld(data.getWorld());
    if (world == null || !world.isChunkLoaded(data.getChunkX(), data.getChunkZ())) {
      return;
    }

    Block block = world.getBlockAt(data.getBlockX(), data.getBlockY(), data.getBlockZ());
    if (block.getType() != Material.SPAWNER) {
      return;
    }

    String spawnerType = SpawnerTypes.canonicalType(data.getSpawnerType());
    if (spawnerType.isBlank()) {
      return;
    }

    LootTable lootTable = resolveLootTable(spawnerType);
    IntRange xpRange = resolveXpRange(spawnerType);
    LootContext lootContext =
        new LootContext.Builder(block.getLocation().add(0.5, 0.5, 0.5)).build();
    ThreadLocalRandom random = ThreadLocalRandom.current();
    long configuredSpawnInterval =
        Math.max(1L, getConfig().getLong("spawner.tick-interval-ticks", 400L));
    double expectedAttempts =
        stackAmount * (Math.max(1L, elapsedTicks) / (double) configuredSpawnInterval);
    int attempts = samplePoisson(expectedAttempts, random);
    if (attempts <= 0) {
      return;
    }

    int maxItems = getConfig().getInt("spawner.max-items-per-spawner", 10000);
    long gainedXp = 0L;
    long overflow = 0L;
    boolean itemsAdded = false;

    for (int i = 0; i < attempts; i++) {
      gainedXp = addSaturated(gainedXp, xpRange.roll(random));
      if (maxItems > 0 && data.getTotalItemCount() >= maxItems) {
        continue;
      }

      Collection<ItemStack> drops = generateDrops(spawnerType, lootTable, lootContext, random);
      if (drops == null || drops.isEmpty()) {
        continue;
      }

      int before = data.getTotalItemCount();
      overflow += data.addDrops(new ArrayList<>(drops), maxItems);
      if (data.getTotalItemCount() > before) {
        itemsAdded = true;
      }
    }

    if (gainedXp > 0) {
      data.setExperience(addSaturated(data.getExperience(), gainedXp));
    }

    if (itemsAdded || gainedXp > 0) {
      SpawnerGUI.refreshOpenSessions(data);
    }

    if (overflow > 0 && getConfig().getBoolean("spawner.debug", false)) {
      getLogger()
          .info("[tick] " + data.getSpawnerId() + " - full, " + overflow + " item(s) discarded.");
    }
  }

  private int samplePoisson(double lambda, ThreadLocalRandom random) {
    if (lambda <= 0.0d) {
      return 0;
    }

    if (lambda > 30.0d) {
      double gaussian = random.nextGaussian();
      int rounded = (int) Math.round(lambda + gaussian * Math.sqrt(lambda));
      return Math.max(0, rounded);
    }

    double limit = Math.exp(-lambda);
    int k = 0;
    double product = 1.0d;
    do {
      k++;
      product *= random.nextDouble();
    } while (product > limit);
    return k - 1;
  }

  private Collection<ItemStack> generateDrops(
      String spawnerType, LootTable lootTable, LootContext lootContext, ThreadLocalRandom random) {
    Collection<ItemStack> configuredDrops = dropCatalogService.generateDrops(spawnerType, random);
    if (configuredDrops != null) {
      return configuredDrops;
    }

    String normalizedType = SpawnerTypes.canonicalType(spawnerType);
    if (fallbackLootTypes.contains(normalizedType)) {
      return fallbackDrops(normalizedType, random);
    }

    if (lootTable == null) {
      return fallbackDrops(normalizedType, random);
    }

    try {
      return lootTable.populateLoot(random, lootContext);
    } catch (IllegalArgumentException ex) {
      fallbackLootTypes.add(normalizedType);
      if (warnedLootTypes.add(normalizedType)) {
        getLogger()
            .warning(
                "Loot table for "
                    + normalizedType
                    + " requires unsupported context on this server; using fallback drops.");
      }
      return fallbackDrops(normalizedType, random);
    }
  }

  private Collection<ItemStack> fallbackDrops(String normalizedType, ThreadLocalRandom random) {
    List<ItemStack> drops = new ArrayList<>();
    switch (normalizedType) {
      case "ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER" ->
          drops.add(new ItemStack(Material.ROTTEN_FLESH, random.nextInt(1, 3)));
      case "SKELETON", "STRAY", "BOGGED" -> {
        drops.add(new ItemStack(Material.BONE, random.nextInt(1, 3)));
        drops.add(new ItemStack(Material.ARROW, random.nextInt(0, 3)));
      }
      case "WITHER_SKELETON" -> {
        drops.add(new ItemStack(Material.BONE, random.nextInt(1, 3)));
        if (random.nextInt(100) < 33) {
          drops.add(new ItemStack(Material.COAL, 1));
        }
        if (random.nextInt(1000) < 25) {
          drops.add(new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
        }
      }
      case "SPIDER", "CAVE_SPIDER" -> {
        drops.add(new ItemStack(Material.STRING, random.nextInt(0, 3)));
        if (random.nextInt(100) < 33) {
          drops.add(new ItemStack(Material.SPIDER_EYE, 1));
        }
      }
      case "CREEPER" -> drops.add(new ItemStack(Material.GUNPOWDER, random.nextInt(0, 3)));
      case "BLAZE" -> drops.add(new ItemStack(Material.BLAZE_ROD, random.nextInt(0, 2)));
      case "ENDERMAN" -> {
        if (random.nextInt(100) < 50) {
          drops.add(new ItemStack(Material.ENDER_PEARL, 1));
        }
      }
      case "WITCH" -> {
        Material[] pool = {
          Material.REDSTONE,
          Material.GLOWSTONE_DUST,
          Material.SUGAR,
          Material.GUNPOWDER,
          Material.SPIDER_EYE,
          Material.GLASS_BOTTLE,
          Material.STICK
        };
        drops.add(new ItemStack(pool[random.nextInt(pool.length)], random.nextInt(1, 3)));
      }
      case "SLIME" -> drops.add(new ItemStack(Material.SLIME_BALL, random.nextInt(0, 3)));
      case "MAGMA_CUBE" -> drops.add(new ItemStack(Material.MAGMA_CREAM, random.nextInt(0, 2)));
      case "PIG" -> drops.add(new ItemStack(Material.PORKCHOP, random.nextInt(1, 4)));
      case "COW", "MOOSHROOM" -> {
        drops.add(new ItemStack(Material.BEEF, random.nextInt(1, 4)));
        drops.add(new ItemStack(Material.LEATHER, random.nextInt(0, 3)));
      }
      case "SHEEP" -> {
        drops.add(new ItemStack(Material.MUTTON, random.nextInt(1, 3)));
        drops.add(new ItemStack(Material.WHITE_WOOL, 1));
      }
      case "CHICKEN" -> {
        drops.add(new ItemStack(Material.CHICKEN, random.nextInt(1, 3)));
        drops.add(new ItemStack(Material.FEATHER, random.nextInt(0, 3)));
      }
      case "RABBIT" -> {
        drops.add(new ItemStack(Material.RABBIT, random.nextInt(0, 2)));
        if (random.nextInt(100) < 10) {
          drops.add(new ItemStack(Material.RABBIT_FOOT, 1));
        }
      }
      default -> {}
    }
    return drops;
  }

  private LootTable resolveLootTable(String spawnerType) {
    return lootTableCache.computeIfAbsent(
        SpawnerTypes.canonicalType(spawnerType), this::lookupLootTable);
  }

  private LootTable lookupLootTable(String normalizedType) {
    String lookup = SpawnerTypes.lootTableType(normalizedType);
    try {
      return LootTables.valueOf(lookup).getLootTable();
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private IntRange resolveXpRange(String spawnerType) {
    IntRange range = xpRangeCache.get(SpawnerTypes.canonicalType(spawnerType));
    return range != null ? range : IntRange.ZERO;
  }

  private Map<String, IntRange> buildXpRangeCache() {
    Map<String, IntRange> ranges = new HashMap<>(defaultXpRanges());
    ConfigurationSection section = getConfig().getConfigurationSection("spawner.xp-ranges");
    if (section == null) {
      return ranges;
    }

    for (String key : section.getKeys(false)) {
      IntRange parsed = parseRange(section.get(key));
      if (parsed != null) {
        ranges.put(SpawnerTypes.canonicalType(key), parsed);
      }
    }
    return ranges;
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

  private void scheduleProcessingTask() {
    cancelProcessingTask();
    processingCursor = 0;
    processingBudget = 0.0d;
    processingTaskId =
        Bukkit.getScheduler().runTaskTimer(this, this::processActiveSpawners, 1L, 1L).getTaskId();
  }

  private void cancelProcessingTask() {
    if (processingTaskId != -1) {
      Bukkit.getScheduler().cancelTask(processingTaskId);
      processingTaskId = -1;
    }
  }

  private void scheduleSaveTask() {
    cancelSaveTask();
    long saveIntervalSecs = getConfig().getLong("spawner.save-interval-seconds", 60L);
    long saveIntervalTicks = Math.max(20L, saveIntervalSecs * 20L);
    saveTaskId =
        Bukkit.getScheduler().runTaskTimer(this, this::flushDirty, saveIntervalTicks, saveIntervalTicks).getTaskId();
  }

  private void cancelSaveTask() {
    if (saveTaskId != -1) {
      Bukkit.getScheduler().cancelTask(saveTaskId);
      saveTaskId = -1;
    }
  }

  private List<SpawnerRecord> snapshotDirtySpawners(boolean includeQueued) {
    if (dirtySpawners.isEmpty()) {
      return List.of();
    }

    List<SpawnerRecord> snapshots = new ArrayList<>();
    for (String spawnerId : new LinkedHashSet<>(dirtySpawners)) {
      SpawnerData data = spawnerCache.get(spawnerId);
      if (data == null) {
        dirtySpawners.remove(spawnerId);
        continue;
      }

      if (!data.isDirty()) {
        dirtySpawners.remove(spawnerId);
        continue;
      }

      if (!includeQueued && !data.shouldQueuePersistence()) {
        continue;
      }

      SpawnerRecord snapshot = data.snapshot();
      data.markQueuedForPersistence(snapshot.version());
      snapshots.add(snapshot);
    }
    return snapshots;
  }

  private void refreshOpenSessionsFromConfig() {
    Set<String> refreshed = new HashSet<>(guiSessionsBySpawner.keySet());
    for (String spawnerId : refreshed) {
      SpawnerData data = spawnerCache.get(spawnerId);
      if (data != null) {
        SpawnerGUI.refreshOpenSessions(data);
      }
    }
  }

  private void unindexGuiSession(UUID playerId, GUISession session) {
    Set<UUID> viewers = guiSessionsBySpawner.get(session.getSpawnerId());
    if (viewers == null) {
      return;
    }

    viewers.remove(playerId);
    if (viewers.isEmpty()) {
      guiSessionsBySpawner.remove(session.getSpawnerId());
    }
  }

  private long addSaturated(long base, long delta) {
    if (delta <= 0) {
      return base;
    }
    if (Long.MAX_VALUE - base < delta) {
      return Long.MAX_VALUE;
    }
    return base + delta;
  }

  private Map<String, IntRange> defaultXpRanges() {
    Map<String, IntRange> ranges = new HashMap<>();

    IntRange passive = new IntRange(1, 3);
    IntRange village = new IntRange(3, 6);
    IntRange commonHostile = new IntRange(5, 5);
    IntRange strongerHostile = new IntRange(5, 10);
    IntRange eliteHostile = new IntRange(10, 20);

    putRange(
        ranges,
        passive,
        "ALLAY",
        "ARMADILLO",
        "AXOLOTL",
        "BAT",
        "BEE",
        "CAMEL",
        "CAT",
        "CHICKEN",
        "COD",
        "COW",
        "DOLPHIN",
        "DONKEY",
        "FOX",
        "FROG",
        "GLOW_SQUID",
        "GOAT",
        "HORSE",
        "LLAMA",
        "MOOSHROOM",
        "MULE",
        "OCELOT",
        "PANDA",
        "PARROT",
        "PIG",
        "POLAR_BEAR",
        "PUFFERFISH",
        "RABBIT",
        "SALMON",
        "SHEEP",
        "SNIFFER",
        "SQUID",
        "TADPOLE",
        "TROPICAL_FISH",
        "TURTLE",
        "WOLF",
        "TRADER_LLAMA",
        "BREEZE");

    putRange(ranges, village, "IRON_GOLEM", "VILLAGER", "WANDERING_TRADER");
    putRange(ranges, new IntRange(0, 0), "SNOW_GOLEM");
    putRange(ranges, new IntRange(1, 1), "SILVERFISH", "ENDERMITE");
    putRange(ranges, new IntRange(1, 4), "SLIME", "MAGMA_CUBE");

    putRange(
        ranges,
        commonHostile,
        "CREEPER",
        "DROWNED",
        "ENDERMAN",
        "HUSK",
        "PIGLIN",
        "PILLAGER",
        "SKELETON",
        "SPIDER",
        "STRAY",
        "VEX",
        "VINDICATOR",
        "WITCH",
        "ZOMBIE",
        "ZOMBIE_VILLAGER",
        "ZOMBIFIED_PIGLIN",
        "HOGLIN",
        "ZOGLIN",
        "PHANTOM",
        "SHULKER",
        "GUARDIAN",
        "WITHER_SKELETON",
        "PIGLIN_BRUTE");

    putRange(
        ranges,
        strongerHostile,
        "BLAZE",
        "CAVE_SPIDER",
        "EVOKER",
        "GHAST",
        "RAVAGER",
        "ELDER_GUARDIAN");

    putRange(ranges, eliteHostile, "WARDEN");
    putRange(ranges, new IntRange(50, 50), "WITHER");
    putRange(ranges, new IntRange(12000, 12000), "ENDER_DRAGON");

    return ranges;
  }

  private void putRange(Map<String, IntRange> ranges, IntRange range, String... types) {
    for (String type : types) {
      ranges.put(SpawnerTypes.canonicalType(type), range);
    }
  }

  private static final class IntRange {
    private static final IntRange ZERO = new IntRange(0, 0);

    private final int min;
    private final int max;

    private IntRange(int min, int max) {
      int low = Math.max(0, Math.min(min, max));
      int high = Math.max(low, Math.max(min, max));
      this.min = low;
      this.max = high;
    }

    private long roll(ThreadLocalRandom random) {
      if (max <= min) {
        return min;
      }
      return min + random.nextInt(max - min + 1);
    }
  }
}
