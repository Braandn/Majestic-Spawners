package de.braandn.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.GUISession;
import de.braandn.utils.SpawnerData;
import de.braandn.utils.SpawnerItem;

public class SpawnerGUI implements Listener {

  private static final String ALL_SELECTION = "ALL";

  public static void openMainGui(Player player, SpawnerData data, boolean isOwner) {
    Inventory inv = Bukkit.createInventory(null, 27, buildMainTitle(data));
    renderMainInventory(inv, data);

    GUISession session = new GUISession(data.getSpawnerId(), isOwner, GUISession.Type.MAIN, inv);
    SpawnersPlugin.getInstance().trackGuiSession(player.getUniqueId(), session);
    player.openInventory(inv);
  }

  public static void openStorageGui(Player player, SpawnerData data, boolean isOwner, int page) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    String rememberedFilter = plugin.getStoredStorageFilter(player.getUniqueId(), data.getSpawnerId());
    openStorageGui(
        player,
        data,
        isOwner,
        page,
        rememberedFilter != null ? rememberedFilter : ALL_SELECTION);
  }

  private static void openStorageGui(
      Player player, SpawnerData data, boolean isOwner, int page, String selectedStorageFilter) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int normalizedPage = normalizeStoragePage(data, page);
    String normalizedSelection = normalizeStorageSelection(selectedStorageFilter);
    Inventory inv = Bukkit.createInventory(null, 54, buildStorageTitle(data, normalizedPage));
    renderStorageInventory(inv, data, normalizedPage, normalizedSelection);
    plugin.rememberStorageFilter(player.getUniqueId(), data.getSpawnerId(), normalizedSelection);

    GUISession session = new GUISession(data.getSpawnerId(), isOwner, GUISession.Type.STORAGE, inv);
    session.setPage(normalizedPage);
    session.setSelectedStorageFilter(normalizedSelection);
    plugin.trackGuiSession(player.getUniqueId(), session);
    player.openInventory(inv);
  }

  public static void openStackGui(Player player, SpawnerData data) {
    boolean isOwner = player.getUniqueId().toString().equals(data.getOwnerUuid());
    String title =
        SpawnerItem.color(
            SpawnersPlugin.getInstance()
                .getConfig()
                .getString("stack-gui.title", "&b%type% &7Spawner Stack")
                .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()))
                .replace("%amount%", String.valueOf(data.getAmount())));

    Inventory inv = Bukkit.createInventory(null, 9, title);
    renderStackInventory(inv, data);

    GUISession session = new GUISession(data.getSpawnerId(), isOwner, GUISession.Type.STACK, inv);
    SpawnersPlugin.getInstance().trackGuiSession(player.getUniqueId(), session);
    player.openInventory(inv);
  }

  public static void openConfirmGui(Player player, SpawnerData data, int pendingAmount) {
    boolean isOwner = player.getUniqueId().toString().equals(data.getOwnerUuid());
    int addableAmount = getAddableSpawnerAmount(data, pendingAmount);
    int previewAmount = getPreviewSpawnerAmount(data, pendingAmount);
    String title =
        SpawnerItem.color(
            SpawnersPlugin.getInstance()
                .getConfig()
                .getString("confirm-gui.title", "&eConfirm Stack")
                .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()))
                .replace("%amount%", String.valueOf(data.getAmount()))
                .replace("%new_amount%", String.valueOf(previewAmount))
                .replace("%add_amount%", String.valueOf(addableAmount)));

    Inventory inv = Bukkit.createInventory(null, 27, title);
    renderConfirmInventory(inv, data, pendingAmount);

    GUISession session = new GUISession(data.getSpawnerId(), isOwner, GUISession.Type.CONFIRM, inv);
    session.setPendingAmount(Math.max(1, pendingAmount));
    SpawnersPlugin.getInstance().trackGuiSession(player.getUniqueId(), session);
    player.openInventory(inv);
  }

  public static void refreshOpenSessions(SpawnerData data) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();

    for (UUID viewerId : plugin.getGuiViewers(data.getSpawnerId())) {
      GUISession session = plugin.getGuiSession(viewerId);
      if (session == null) {
        plugin.clearGuiSession(viewerId);
        continue;
      }

      Player player = Bukkit.getPlayer(viewerId);
      if (player == null) {
        plugin.clearGuiSession(viewerId);
        continue;
      }
      if (player.getOpenInventory() == null
          || player.getOpenInventory().getTopInventory() != session.getInventory()) {
        plugin.clearGuiSession(viewerId);
        continue;
      }

      switch (session.getType()) {
        case MAIN -> renderMainInventory(session.getInventory(), data);
        case STORAGE -> {
          int normalizedPage = normalizeStoragePage(data, session.getPage());
          String normalizedSelection = normalizeStorageSelection(session.getSelectedStorageFilter());
          session.setPage(normalizedPage);
          session.setSelectedStorageFilter(normalizedSelection);
          renderStorageInventory(
              session.getInventory(), data, normalizedPage, normalizedSelection);
        }
        case STACK -> renderStackInventory(session.getInventory(), data);
        case CONFIRM ->
            renderConfirmInventory(session.getInventory(), data, session.getPendingAmount());
      }
      player.updateInventory();
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onInventoryClick(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player)) return;
    Player player = (Player) e.getWhoClicked();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    GUISession session = plugin.getGuiSession(player.getUniqueId());
    if (session == null) return;

    if (e.getInventory() != session.getInventory()) {
      plugin.clearGuiSession(player.getUniqueId());
      return;
    }

    boolean storageSession = session.getType() == GUISession.Type.STORAGE;
    boolean clickTop = e.getClickedInventory() == session.getInventory();
    boolean controlRowClick = clickTop && e.getSlot() >= 45;
    boolean canModify = canModify(session, plugin);

    if (!storageSession || controlRowClick) {
      e.setCancelled(true);
    }

    SpawnerData data = plugin.getSpawnerCache().get(session.getSpawnerId());
    if (data == null) {
      player.closeInventory();
      return;
    }

    if (storageSession && !canModify && !controlRowClick) {
      e.setCancelled(true);
      return;
    }

    if (storageSession && !controlRowClick) {
      scheduleStorageSync(player, session);
      return;
    }

    int slot = e.getSlot();
    switch (session.getType()) {
      case MAIN -> handleMainClick(player, session, data, slot);
      case STORAGE -> handleStorageClick(player, session, data, e);
      case STACK -> handleStackClick(player, session, data, slot);
      case CONFIRM -> handleConfirmClick(player, session, data, slot);
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onInventoryDrag(InventoryDragEvent e) {
    if (!(e.getWhoClicked() instanceof Player)) return;
    Player player = (Player) e.getWhoClicked();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    GUISession session = plugin.getGuiSession(player.getUniqueId());
    if (session == null) return;
    if (e.getInventory() != session.getInventory()) return;

    if (session.getType() != GUISession.Type.STORAGE) {
      e.setCancelled(true);
      return;
    }

    if (!canModify(session, plugin)) {
      e.setCancelled(true);
      return;
    }

    for (int raw : e.getRawSlots()) {
      if (raw >= 45 && raw < 54) {
        e.setCancelled(true);
        return;
      }
    }

    scheduleStorageSync(player, session);
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent e) {
    if (!(e.getPlayer() instanceof Player)) return;
    Player player = (Player) e.getPlayer();
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    GUISession session = plugin.getGuiSession(player.getUniqueId());
    if (session == null) return;
    if (session.getType() == GUISession.Type.STORAGE) {
      SpawnerData data = plugin.getSpawnerCache().get(session.getSpawnerId());
      if (data != null) {
        syncStoragePage(session, data);
      }
    }
    if (e.getInventory() == session.getInventory()) {
      plugin.clearGuiSession(player.getUniqueId());
    }
  }

  private void handleMainClick(Player player, GUISession session, SpawnerData data, int slot) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int storageSlot = plugin.getConfig().getInt("main-gui.storage.slot", 12);
    int spawnerSlot = plugin.getConfig().getInt("main-gui.spawner.slot", 13);
    int xpSlot = plugin.getConfig().getInt("main-gui.experience.slot", 14);

    if (slot == storageSlot) {
      openStorageGui(player, data, session.isOwner(), 0);
    } else if (slot == spawnerSlot) {
      openStackGui(player, data);
    } else if (slot == xpSlot && plugin.getConfig().getBoolean("spawner.collect-xp", true)) {
      if (!canModify(session, plugin)) {
        SpawnerItem.sendConfiguredMessage(player, "messages.view-only");
        return;
      }
      collectXp(player, data);
      openMainGui(player, data, session.isOwner());
    }
  }

  private void handleStorageClick(
      Player player, GUISession session, SpawnerData data, InventoryClickEvent event) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    syncStoragePage(session, data);
    String normalizedSelection = normalizeStorageSelection(session.getSelectedStorageFilter());
    session.setSelectedStorageFilter(normalizedSelection);
    int slot = event.getSlot();
    int closeSlot = plugin.getConfig().getInt("storage-gui.close.slot", 45);
    int backSlot = plugin.getConfig().getInt("storage-gui.back.slot", 48);
    int collectSlot = plugin.getConfig().getInt("storage-gui.collect.slot", 49);
    int nextSlot = plugin.getConfig().getInt("storage-gui.next.slot", 50);
    int dropSlot = plugin.getConfig().getInt("storage-gui.drop.slot", 53);

    if (slot == closeSlot) {
      openMainGui(player, data, session.isOwner());
      return;
    }
    if (slot == backSlot) {
      if (session.getPage() > 0)
        openStorageGui(
            player,
            data,
            session.isOwner(),
            session.getPage() - 1,
            session.getSelectedStorageFilter());
      return;
    }
    if (slot == nextSlot) {
      int total = Math.max(1, (int) Math.ceil(data.getInventory().size() / 45.0));
      if (session.getPage() < total - 1)
        openStorageGui(
            player,
            data,
            session.isOwner(),
            session.getPage() + 1,
            session.getSelectedStorageFilter());
      return;
    }

    if (!canModify(session, plugin)) {
      SpawnerItem.sendConfiguredMessage(player, "messages.view-only");
      return;
    }

    if ((slot == collectSlot || slot == dropSlot) && event.isRightClick()) {
      cycleStorageSelection(session, data);
      plugin.rememberStorageFilter(
          player.getUniqueId(), data.getSpawnerId(), session.getSelectedStorageFilter());
      renderStorageInventory(
          session.getInventory(), data, session.getPage(), session.getSelectedStorageFilter());
      player.updateInventory();
      return;
    }

    if (slot == collectSlot) {
      collectItems(player, data, session.getSelectedStorageFilter());
      openStorageGui(player, data, session.isOwner(), 0, session.getSelectedStorageFilter());
    } else if (slot == dropSlot) {
      dropItems(player, data, session.getSelectedStorageFilter());
      openStorageGui(player, data, session.isOwner(), 0, session.getSelectedStorageFilter());
    }
  }

  private void handleStackClick(Player player, GUISession session, SpawnerData data, int slot) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int backSlot = plugin.getConfig().getInt("stack-gui.back.slot", 0);
    int takeSlot = plugin.getConfig().getInt("stack-gui.take.slot", 2);
    int takeAllSlot = plugin.getConfig().getInt("stack-gui.take-all.slot", 5);

    if (slot == backSlot) {
      openMainGui(player, data, session.isOwner());
      return;
    }

    if (slot != takeSlot && slot != takeAllSlot) return;

    if (!canModify(session, plugin)) {
      SpawnerItem.sendConfiguredMessage(player, "messages.view-only");
      return;
    }

    if (slot == takeSlot) {
      boolean removed = handleTake(player, data, plugin);
      if (!removed) openStackGui(player, data);
      return;
    }

    handleTakeAll(player, data, plugin);
  }

  private void handleConfirmClick(Player player, GUISession session, SpawnerData data, int slot) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int confirmSlot = plugin.getConfig().getInt("confirm-gui.confirm.slot", 11);
    int cancelSlot = plugin.getConfig().getInt("confirm-gui.cancel.slot", 15);

    if (slot == cancelSlot) {
      openMainGui(player, data, session.isOwner());
      return;
    }
    if (slot != confirmSlot) return;

    if (!canModify(session, plugin)) {
      SpawnerItem.sendConfiguredMessage(player, "messages.view-only");
      return;
    }

    if (handleAddFromHand(player, data, plugin)) {
      openMainGui(player, data, session.isOwner());
    }
  }

  private void collectXp(Player player, SpawnerData data) {
    long xp = data.getExperience();
    if (xp <= 0) return;
    spawnXpOrbsAtPlayer(player, xp);
    data.setExperience(0);
    refreshOpenSessions(data);

    SpawnerItem.sendConfiguredMessage(
        player, "messages.collected-xp", msg -> msg.replace("%amount%", String.valueOf(xp)));
  }

  private void collectItems(Player player, SpawnerData data, String selectedFilter) {
    FilteredInventory filteredInventory = splitInventoryBySelection(data, selectedFilter);
    if (filteredInventory.selectedItems().isEmpty()) {
      return;
    }

    List<ItemStack> remaining = new ArrayList<>(filteredInventory.remainingItems());
    for (ItemStack item : filteredInventory.selectedItems()) {
      if (item == null) {
        continue;
      }
      Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
      if (!leftover.isEmpty()) {
        remaining.addAll(leftover.values());
      }
    }

    data.setInventory(remaining);
    refreshOpenSessions(data);
  }

  private void dropItems(Player player, SpawnerData data, String selectedFilter) {
    FilteredInventory filteredInventory = splitInventoryBySelection(data, selectedFilter);
    if (filteredInventory.selectedItems().isEmpty()) {
      return;
    }

    for (ItemStack item : filteredInventory.selectedItems()) {
      if (item == null) continue;
      dropLikePlayerThrow(player, item);
    }
    data.setInventory(filteredInventory.remainingItems());
    refreshOpenSessions(data);
  }

  private boolean handleTake(Player player, SpawnerData data, SpawnersPlugin plugin) {
    ItemStack spawnerItem = SpawnerItem.buildSpawnerItem(data.getSpawnerType(), 1);
    Map<Integer, ItemStack> leftover = player.getInventory().addItem(spawnerItem);
    if (!leftover.isEmpty()) {
      leftover.values().forEach(it -> dropLikePlayerThrow(player, it));
    }

    int newAmount = data.getAmount() - 1;
    if (newAmount <= 0) {
      player.closeInventory();
      dropStoredLootAndXp(data);
      removeSpawnerBlockAndData(data, plugin);
    } else {
      data.setAmount(newAmount);
      updatePlacedAmount(data);
      refreshOpenSessions(data);
    }

    SpawnerItem.sendConfiguredMessage(
        player,
        "messages.spawner-taken",
        msg -> msg.replace("%type%", SpawnerItem.formatType(data.getSpawnerType())));
    return newAmount <= 0;
  }

  private void handleTakeAll(Player player, SpawnerData data, SpawnersPlugin plugin) {
    int packedAmount = Math.max(1, data.getAmount());
    ItemStack packed = SpawnerItem.buildSpawnerItem(data.getSpawnerType(), packedAmount);
    Map<Integer, ItemStack> leftover = player.getInventory().addItem(packed);
    if (!leftover.isEmpty()) {
      leftover.values().forEach(it -> dropLikePlayerThrow(player, it));
    }

    player.closeInventory();
    dropStoredLootAndXp(data);
    removeSpawnerBlockAndData(data, plugin);

    SpawnerItem.sendConfiguredMessage(
        player,
        "messages.spawner-taken-all",
        msg ->
            msg.replace("%amount%", String.valueOf(packedAmount))
                .replace("%type%", SpawnerItem.formatType(data.getSpawnerType())));
  }

  private boolean handleAddFromHand(Player player, SpawnerData data, SpawnersPlugin plugin) {
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (!SpawnerItem.isCustomSpawner(hand)) {
      SpawnerItem.sendConfiguredMessage(player, "messages.wrong-type");
      return false;
    }

    String handType = SpawnerItem.getSpawnerType(hand);
    if (!data.getSpawnerType().equals(handType)) {
      SpawnerItem.sendConfiguredMessage(player, "messages.wrong-type");
      return false;
    }

    int handAmount = SpawnerItem.getSpawnerAmount(hand);
    int addableAmount = getAddableSpawnerAmount(data, handAmount);
    if (addableAmount <= 0) {
      return false;
    }

    consumeSpawnerFromHand(player, hand, handType, handAmount, addableAmount);
    data.setAmount(data.getAmount() + addableAmount);
    updatePlacedAmount(data);
    refreshOpenSessions(data);

    SpawnerItem.sendConfiguredMessage(
        player,
        "messages.spawner-stacked",
        msg ->
            msg.replace("%amount%", String.valueOf(data.getAmount()))
                .replace("%type%", SpawnerItem.formatType(data.getSpawnerType())));

    return true;
  }

  private void consumeSpawnerFromHand(
      Player player,
      ItemStack hand,
      String handType,
      int handPackedAmount,
      int consumedPackedAmount) {
    if (consumedPackedAmount >= handPackedAmount) {
      if (hand.getAmount() > 1) {
        hand.setAmount(hand.getAmount() - 1);
      } else {
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
      }
      return;
    }

    int leftoverPackedAmount = handPackedAmount - consumedPackedAmount;
    ItemStack leftoverItem = SpawnerItem.buildSpawnerItem(handType, leftoverPackedAmount);

    if (hand.getAmount() <= 1) {
      player.getInventory().setItemInMainHand(leftoverItem);
      return;
    }

    ItemStack remainingOriginalItems = hand.clone();
    remainingOriginalItems.setAmount(hand.getAmount() - 1);
    player.getInventory().setItemInMainHand(leftoverItem);

    Map<Integer, ItemStack> overflow = player.getInventory().addItem(remainingOriginalItems);
    overflow.values().forEach(item -> dropLikePlayerThrow(player, item));
  }

  private void removeSpawnerBlockAndData(SpawnerData data, SpawnersPlugin plugin) {
    World world = Bukkit.getWorld(data.getWorld());
    if (world != null) {
      world.getBlockAt(data.getBlockX(), data.getBlockY(), data.getBlockZ()).setType(Material.AIR);
    }
    plugin.unregisterSpawner(data.getSpawnerId());
  }

  private void updatePlacedAmount(SpawnerData data) {
    World world = Bukkit.getWorld(data.getWorld());
    if (world == null) return;
    SpawnerItem.updatePlacedSpawnerAmount(
        world.getBlockAt(data.getBlockX(), data.getBlockY(), data.getBlockZ()), data.getAmount());
  }

  private void dropStoredLootAndXp(SpawnerData data) {
    World world = Bukkit.getWorld(data.getWorld());
    if (world == null) return;
    Location loc =
        new Location(world, data.getBlockX() + 0.5, data.getBlockY() + 0.5, data.getBlockZ() + 0.5);

    for (ItemStack item : new ArrayList<>(data.getInventory())) {
      if (item != null) world.dropItemNaturally(loc, item);
    }
    data.clearInventory();

    long xp = data.getExperience();
    if (xp > 0) {
      world.spawn(
          loc,
          ExperienceOrb.class,
          orb -> orb.setExperience((int) Math.min(xp, Integer.MAX_VALUE)));
    }

    data.setExperience(0L);
  }

  private void spawnXpOrbsAtPlayer(Player player, long totalXp) {
    if (totalXp <= 0) return;
    Location spawnLoc = player.getLocation().add(0.0d, 0.25d, 0.0d);
    long remaining = totalXp;
    while (remaining > 0) {
      int orbValue = getVanillaXpOrbValue(remaining);
      player.getWorld().spawn(spawnLoc, ExperienceOrb.class, orb -> orb.setExperience(orbValue));
      remaining -= orbValue;
    }
  }

  private int getVanillaXpOrbValue(long amount) {
    if (amount >= 2477) return 2477;
    if (amount >= 1237) return 1237;
    if (amount >= 617) return 617;
    if (amount >= 307) return 307;
    if (amount >= 149) return 149;
    if (amount >= 73) return 73;
    if (amount >= 37) return 37;
    if (amount >= 17) return 17;
    if (amount >= 7) return 7;
    if (amount >= 3) return 3;
    return 1;
  }

  private void dropLikePlayerThrow(Player player, ItemStack item) {
    if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;

    Location base = player.getLocation();
    double spawnY = player.getEyeLocation().getY() - 0.3d;
    Location spawnLoc = new Location(player.getWorld(), base.getX(), spawnY, base.getZ(), base.getYaw(), base.getPitch());

    Item dropped = player.getWorld().dropItem(spawnLoc, item.clone());
    dropped.setVelocity(buildNaturalDropVelocity(base.getYaw(), base.getPitch()));
    dropped.setPickupDelay(40);
    dropped.setThrower(player.getUniqueId());
  }

  private Vector buildNaturalDropVelocity(float yaw, float pitch) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    double pitchRad = Math.toRadians(pitch);
    double yawRad = Math.toRadians(yaw);

    double sinPitch = Math.sin(pitchRad);
    double cosPitch = Math.cos(pitchRad);
    double sinYaw = Math.sin(yawRad);
    double cosYaw = Math.cos(yawRad);

    double theta = random.nextDouble(0.0d, Math.PI * 2.0d);
    double jitter = 0.02d * random.nextDouble();

    double x = (-sinYaw * cosPitch * 0.3d) + (Math.cos(theta) * jitter);
    double y = (-sinPitch * 0.3d) + 0.1d + ((random.nextDouble() - random.nextDouble()) * 0.1d);
    double z = (cosYaw * cosPitch * 0.3d) + (Math.sin(theta) * jitter);

    return new Vector(x, y, z);
  }

  private void scheduleStorageSync(Player player, GUISession expectedSession) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              GUISession current = plugin.getGuiSession(player.getUniqueId());
              if (current == null || current != expectedSession) return;
              if (current.getType() != GUISession.Type.STORAGE) return;
              SpawnerData data = plugin.getSpawnerCache().get(current.getSpawnerId());
              if (data == null) return;
              syncStoragePage(current, data);
            });
  }

  private void syncStoragePage(GUISession session, SpawnerData data) {
    if (session.getType() != GUISession.Type.STORAGE) return;

    int start = Math.max(0, session.getPage() * 45);
    List<ItemStack> current = data.getInventory();
    int prefixEnd = Math.min(start, current.size());
    int suffixStart = Math.min(start + 45, current.size());

    List<ItemStack> rebuilt = new ArrayList<>(current.size() + 45);
    for (int i = 0; i < prefixEnd; i++) {
      ItemStack item = current.get(i);
      if (item != null && !item.getType().isAir()) rebuilt.add(item.clone());
    }

    for (int slot = 0; slot < 45; slot++) {
      ItemStack item = session.getInventory().getItem(slot);
      if (item != null && !item.getType().isAir()) {
        rebuilt.add(item.clone());
      }
    }

    for (int i = suffixStart; i < current.size(); i++) {
      ItemStack item = current.get(i);
      if (item != null && !item.getType().isAir()) rebuilt.add(item.clone());
    }

    data.setInventory(rebuilt);
  }

  private boolean canModify(GUISession session, SpawnersPlugin plugin) {
    if (session.isOwner()) return true;
    String access = plugin.getConfig().getString("spawner.non-owner-access", "normal");
    return access.equalsIgnoreCase("normal");
  }

  private static String buildMainTitle(SpawnerData data) {
    return SpawnerItem.color(
        SpawnersPlugin.getInstance()
            .getConfig()
            .getString("main-gui.title", "&b%amount%x &7%type% Spawner")
            .replace("%amount%", String.valueOf(data.getAmount()))
            .replace("%type%", SpawnerItem.formatType(data.getSpawnerType())));
  }

  private static String buildStorageTitle(SpawnerData data, int page) {
    int totalPages = Math.max(1, (int) Math.ceil(data.getInventory().size() / 45.0));
    int safePage = Math.max(0, Math.min(page, totalPages - 1));
    return SpawnerItem.color(
        SpawnersPlugin.getInstance()
            .getConfig()
            .getString("storage-gui.title", "&b%amount%x &7%type% | P%page%")
            .replace("%amount%", String.valueOf(data.getAmount()))
            .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()))
            .replace("%page%", String.valueOf(safePage + 1))
            .replace("%max_page%", String.valueOf(totalPages)));
  }

  private static void renderMainInventory(Inventory inv, SpawnerData data) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    ItemStack filler = buildItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
    for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler.clone());

    int storageSlot = plugin.getConfig().getInt("main-gui.storage.slot", 12);
    String storageName = plugin.getConfig().getString("main-gui.storage.name", "&bStorage");
    inv.setItem(storageSlot, buildItem(Material.CHEST, storageName, buildStorageLore(data)));

    int spawnerSlot = plugin.getConfig().getInt("main-gui.spawner.slot", 13);
    String spawnerName = plugin.getConfig().getString("main-gui.spawner.name", "&dSpawner");
    List<String> spawnerLore =
        plugin.getConfig().getStringList("main-gui.spawner.lore").stream()
            .map(
                l ->
                    SpawnerItem.color(
                        l.replace("%amount%", String.valueOf(data.getAmount()))
                            .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()))))
            .collect(Collectors.toList());
    inv.setItem(spawnerSlot, buildItem(Material.SPAWNER, spawnerName, spawnerLore));

    int xpSlot = plugin.getConfig().getInt("main-gui.experience.slot", 14);
    if (plugin.getConfig().getBoolean("spawner.collect-xp", true)) {
      String xpName = plugin.getConfig().getString("main-gui.experience.name", "&2Collect XP");
      List<String> xpLore =
          plugin.getConfig().getStringList("main-gui.experience.lore").stream()
              .map(
                  l ->
                      SpawnerItem.color(
                          l.replace("%amount%", String.valueOf(data.getExperience()))))
              .collect(Collectors.toList());
      inv.setItem(xpSlot, buildItem(Material.EXPERIENCE_BOTTLE, xpName, xpLore));
    } else {
      inv.setItem(xpSlot, filler.clone());
    }
  }

  private static void renderStorageInventory(
      Inventory inv, SpawnerData data, int page, String selectedStorageFilter) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();

    String fillStr =
        plugin.getConfig().getString("storage-gui.fill_item", "WHITE_STAINED_GLASS_PANE");
    Material fillMat = parseMaterial(fillStr, Material.WHITE_STAINED_GLASS_PANE);
    ItemStack filler = buildItem(fillMat, " ", Collections.emptyList());

    for (int i = 0; i < 45; i++) inv.setItem(i, null);
    for (int i = 45; i < 54; i++) inv.setItem(i, filler.clone());

    List<ItemStack> items = data.getInventory();
    int start = page * 45;
    for (int i = 0; i < 45 && (start + i) < items.size(); i++) {
      ItemStack item = items.get(start + i);
      if (item != null) inv.setItem(i, item.clone());
    }

    int totalPages = Math.max(1, (int) Math.ceil(items.size() / 45.0));
    boolean hasPreviousPage = page > 0;
    boolean hasNextPage = page < totalPages - 1;

    setButton(inv, "storage-gui.close", plugin, Material.BARRIER);
    setStorageNavButton(inv, "storage-gui.back", plugin, Material.PAPER, hasPreviousPage);
    setStorageNavButton(inv, "storage-gui.next", plugin, Material.PAPER, hasNextPage);
    setStorageActionButton(
        inv, "storage-gui.drop", plugin, Material.DROPPER, data, selectedStorageFilter);
    setStorageActionButton(
        inv, "storage-gui.collect", plugin, Material.CHEST, data, selectedStorageFilter);
  }

  private static void renderStackInventory(Inventory inv, SpawnerData data) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    ItemStack filler = buildItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
    for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler.clone());

    setButton(inv, "stack-gui.back", plugin, Material.ARROW);
    setButton(inv, "stack-gui.take", plugin, Material.SPAWNER);
    setButton(inv, "stack-gui.take-all", plugin, Material.DROPPER);
  }

  private static void renderConfirmInventory(Inventory inv, SpawnerData data, int pendingAmount) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int addableAmount = getAddableSpawnerAmount(data, pendingAmount);
    int previewAmount = getPreviewSpawnerAmount(data, pendingAmount);

    ItemStack filler = buildItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
    for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler.clone());

    setButton(inv, "confirm-gui.confirm", plugin, Material.LIME_DYE);
    setButton(inv, "confirm-gui.cancel", plugin, Material.RED_DYE);

    int infoSlot = plugin.getConfig().getInt("confirm-gui.preview.slot", 13);
    String infoName =
        plugin.getConfig()
            .getString("confirm-gui.preview.name", "&bAdd &f%add_amount%x &7%type%")
            .replace("%add_amount%", String.valueOf(addableAmount))
            .replace("%amount%", String.valueOf(data.getAmount()))
            .replace("%new_amount%", String.valueOf(previewAmount))
            .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()));
    List<String> infoLore =
        plugin.getConfig().getStringList("confirm-gui.preview.lore").stream()
            .map(
                line ->
                    line.replace("%add_amount%", String.valueOf(addableAmount))
                        .replace("%amount%", String.valueOf(data.getAmount()))
                        .replace("%new_amount%", String.valueOf(previewAmount))
                        .replace("%type%", SpawnerItem.formatType(data.getSpawnerType())))
            .collect(Collectors.toList());
    inv.setItem(infoSlot, buildItem(Material.SPAWNER, infoName, infoLore));
  }

  private static int getAddableSpawnerAmount(SpawnerData data, int pendingAmount) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    int handAmount = Math.max(0, pendingAmount);
    int currentAmount = Math.max(0, data.getAmount());
    int remainingCapacity = Math.max(0, plugin.getMaxSpawnerStack() - currentAmount);
    return Math.min(handAmount, remainingCapacity);
  }

  private static int getPreviewSpawnerAmount(SpawnerData data, int pendingAmount) {
    return Math.max(0, data.getAmount()) + getAddableSpawnerAmount(data, pendingAmount);
  }

  private static ItemStack buildItem(Material mat, String name, List<String> lore) {
    ItemStack item = new ItemStack(mat);
    ItemMeta meta = item.getItemMeta();
    if (meta == null) return item;
    meta.setDisplayName(SpawnerItem.color(name));
    if (!lore.isEmpty()) {
      meta.setLore(lore.stream().map(SpawnerItem::color).collect(Collectors.toList()));
    }
    meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
    item.setItemMeta(meta);
    return item;
  }

  private static void setButton(
      Inventory inv, String path, SpawnersPlugin plugin, Material fallback) {
    if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;
    int slot = plugin.getConfig().getInt(path + ".slot", 0);
    String name = plugin.getConfig().getString(path + ".name", "");
    List<String> lore = plugin.getConfig().getStringList(path + ".lore");
    Material mat = parseMaterial(plugin.getConfig().getString(path + ".material", ""), fallback);
    inv.setItem(slot, buildItem(mat, name, lore));
  }

  private static void setStorageNavButton(
      Inventory inv, String path, SpawnersPlugin plugin, Material fallback, boolean hasTargetPage) {
    if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;

    if (!plugin.getConfig().getBoolean(path + ".show-always", false) && !hasTargetPage) {
      return;
    }

    setButton(inv, path, plugin, fallback);
  }

  private static void setStorageActionButton(
      Inventory inv,
      String path,
      SpawnersPlugin plugin,
      Material fallback,
      SpawnerData data,
      String selectedStorageFilter) {
    if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;

    int slot = plugin.getConfig().getInt(path + ".slot", 0);
    String name = plugin.getConfig().getString(path + ".name", "");
    Material mat = parseMaterial(plugin.getConfig().getString(path + ".material", ""), fallback);
    String normalizedSelection = normalizeStorageSelection(selectedStorageFilter);
    String selectedItemsLabel = formatSelectedItemsLabel(data, normalizedSelection);
    String selectedItemsLine =
        plugin
            .getConfig()
            .getString(
                path + ".selected_items_format",
                "&7Selected Item Types: &f%selected_items%")
            .replace("%selected_items%", selectedItemsLabel);
    String currentSyntax =
        plugin
            .getConfig()
            .getString(
                path + ".filter_syntax_current",
                "&a> &b%selected%");
    String otherSyntax =
        plugin
            .getConfig()
            .getString(
                path + ".filter_syntax_other",
                "&7> &b%other%");

    List<String> lore = new ArrayList<>();
    for (String line : plugin.getConfig().getStringList(path + ".lore")) {
      if ("%selected_items%".equals(line)) {
        lore.add(selectedItemsLine);
        continue;
      }
      if ("%filter_syntax%".equals(line)) {
        lore.addAll(buildFilterSyntaxLines(data, normalizedSelection, currentSyntax, otherSyntax));
        continue;
      }
      lore.add(line.replace("%selected_items%", selectedItemsLabel));
    }

    inv.setItem(slot, buildItem(mat, name, lore));
  }

  private static Material parseMaterial(String name, Material fallback) {
    try {
      return Material.valueOf(name.toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      return fallback;
    }
  }

  private static List<String> buildStorageLore(SpawnerData data) {
    SpawnersPlugin plugin = SpawnersPlugin.getInstance();
    List<String> config = plugin.getConfig().getStringList("main-gui.storage.lore");
    String syntax =
        plugin.getConfig().getString("main-gui.storage.item_list_syntax", "&b%amount%x &7%item%");
    int total = data.getTotalItemCount();
    int max = plugin.getConfig().getInt("spawner.max-items-per-spawner", 10000);
    double pct = max > 0 ? (total * 100.0 / max) : 0.0;

    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ItemStack item : data.getInventory()) {
      if (item == null) continue;
      String key =
          (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
              ? item.getItemMeta().getDisplayName()
              : SpawnerItem.formatType(item.getType().name());
      counts.merge(key, item.getAmount(), Integer::sum);
    }

    List<String> result = new ArrayList<>();
    for (String line : config) {
      if (line.equals("%item_list%")) {
        counts.forEach(
            (name, amt) ->
                result.add(
                    SpawnerItem.color(
                        syntax.replace("%amount%", String.valueOf(amt)).replace("%item%", name))));
      } else {
        result.add(
            SpawnerItem.color(
                line.replace("%percentage%", String.format("%.1f%%", pct))
                    .replace("%amount%", String.valueOf(data.getAmount()))
                    .replace("%type%", SpawnerItem.formatType(data.getSpawnerType()))));
      }
    }
    return result;
  }

  private void cycleStorageSelection(GUISession session, SpawnerData data) {
    String currentSelection = normalizeStorageSelection(session.getSelectedStorageFilter());
    List<String> availableSelections = getStorageSelectionOrder(data, currentSelection);
    if (availableSelections.isEmpty()) {
      session.setSelectedStorageFilter(ALL_SELECTION);
      return;
    }

    int currentIndex = availableSelections.indexOf(currentSelection);
    int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % availableSelections.size();
    session.setSelectedStorageFilter(availableSelections.get(nextIndex));
  }

  private static String normalizeStorageSelection(String selectedStorageFilter) {
    if (selectedStorageFilter == null || selectedStorageFilter.isBlank()) {
      return ALL_SELECTION;
    }
    return selectedStorageFilter;
  }

  private static List<String> getStorageSelectionOrder(
      SpawnerData data, String selectedStorageFilter) {
    Set<String> itemTypes = new LinkedHashSet<>();
    for (ItemStack item : data.getInventory()) {
      String key = getStorageSelectionKey(item);
      if (key != null) {
        itemTypes.add(key);
      }
    }

    List<String> order = new ArrayList<>();
    order.add(ALL_SELECTION);
    if (!ALL_SELECTION.equals(selectedStorageFilter)
        && selectedStorageFilter != null
        && !selectedStorageFilter.isBlank()) {
      itemTypes.add(selectedStorageFilter);
    }
    order.addAll(itemTypes.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());

    return order;
  }

  private static String formatSelectedItemsLabel(SpawnerData data, String selectedStorageFilter) {
    String normalized = normalizeStorageSelection(selectedStorageFilter);
    return ALL_SELECTION.equals(normalized) ? "ALL" : normalized;
  }

  private static List<String> buildFilterSyntaxLines(
      SpawnerData data, String selectedStorageFilter, String currentSyntax, String otherSyntax) {
    List<String> lines = new ArrayList<>();
    for (String option : getStorageSelectionOrder(data, selectedStorageFilter)) {
      if (option.equals(selectedStorageFilter)) {
        lines.add(currentSyntax.replace("%selected%", option));
      } else {
        lines.add(otherSyntax.replace("%other%", option));
      }
    }
    return lines;
  }

  private FilteredInventory splitInventoryBySelection(SpawnerData data, String selectedFilter) {
    String normalizedSelection = normalizeStorageSelection(selectedFilter);
    if (ALL_SELECTION.equals(normalizedSelection)) {
      return new FilteredInventory(new ArrayList<>(data.getInventory()), List.of());
    }

    List<ItemStack> selected = new ArrayList<>();
    List<ItemStack> remaining = new ArrayList<>();
    for (ItemStack item : data.getInventory()) {
      if (item == null) {
        continue;
      }

      if (normalizedSelection.equals(getStorageSelectionKey(item))) {
        selected.add(item.clone());
      } else {
        remaining.add(item.clone());
      }
    }
    return new FilteredInventory(selected, remaining);
  }

  private static String getStorageSelectionKey(ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return null;
    }

    if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
      return item.getItemMeta().getDisplayName();
    }
    return SpawnerItem.formatType(item.getType().name());
  }

  private static int normalizeStoragePage(SpawnerData data, int page) {
    int totalPages = Math.max(1, (int) Math.ceil(data.getInventory().size() / 45.0));
    return Math.max(0, Math.min(page, totalPages - 1));
  }

  private record FilteredInventory(List<ItemStack> selectedItems, List<ItemStack> remainingItems) {}
}
