package de.braandn.commands;

import de.braandn.SpawnersPlugin;
import de.braandn.utils.SpawnerItem;
import de.braandn.utils.SpawnerTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class MajesticSpawnersCommand implements TabExecutor {

  private static final String RELOAD_PERMISSION = "majesticspawners.reload";
  private static final String GIVE_PERMISSION = "majesticspawners.give";

  private final SpawnersPlugin plugin;

  public MajesticSpawnersCommand(SpawnersPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      sendUsage(sender, label);
      return true;
    }

    return switch (args[0].toLowerCase(Locale.ROOT)) {
      case "reload" -> handleReload(sender, label, args);
      case "give" -> handleGive(sender, label, args);
      default -> {
        sendUsage(sender, label);
        yield true;
      }
    };
  }

  private boolean handleReload(CommandSender sender, String label, String[] args) {
    if (args.length != 1) {
      sendConfiguredMessage(sender, "reload.usage", msg -> msg.replace("%label%", label));
      return true;
    }

    if (!sender.hasPermission(RELOAD_PERMISSION)) {
      sendConfiguredMessage(sender, "reload.no-permission");
      return true;
    }

    try {
      plugin.reloadPluginState();
      sendConfiguredMessage(sender, "reload.success");
    } catch (Exception ex) {
      sendConfiguredMessage(sender, "reload.failure");
      plugin.getLogger().severe("Could not reload MajesticSpawners.");
      ex.printStackTrace();
    }

    return true;
  }

  private boolean handleGive(CommandSender sender, String label, String[] args) {
    if (!sender.hasPermission(GIVE_PERMISSION)) {
      sendConfiguredMessage(sender, "give.no-permission");
      return true;
    }

    if (args.length < 3 || args.length > 4) {
      sendConfiguredMessage(sender, "give.usage", msg -> msg.replace("%label%", label));
      return true;
    }

    Player target = resolveOnlinePlayer(args[1]);
    if (target == null) {
      sendConfiguredMessage(
          sender, "give.player-offline", msg -> msg.replace("%player%", args[1]));
      return true;
    }

    String spawnerType = SpawnerTypes.canonicalType(args[2]);
    if (!SpawnerTypes.isSupportedSpawnerType(spawnerType)) {
      sendConfiguredMessage(
          sender, "give.invalid-type", msg -> msg.replace("%type%", args[2]));
      return true;
    }

    int amount = 1;
    if (args.length == 4) {
      try {
        amount = Integer.parseInt(args[3]);
      } catch (NumberFormatException ex) {
        sendConfiguredMessage(sender, "give.invalid-amount", msg -> msg.replace("%amount%", args[3]));
        return true;
      }
    }

    int maxStack = plugin.getMaxSpawnerStack();
    if (amount < 1 || amount > maxStack) {
      final String amountValue = String.valueOf(amount);
      sendConfiguredMessage(
          sender,
          "give.amount-out-of-range",
          msg ->
              msg.replace("%amount%", amountValue)
                  .replace("%max%", String.valueOf(maxStack)));
      return true;
    }

    ItemStack spawnerItem = SpawnerItem.buildSpawnerItem(spawnerType, amount);
    Map<Integer, ItemStack> leftover = target.getInventory().addItem(spawnerItem);
    boolean droppedAtFeet = !leftover.isEmpty();
    leftover
        .values()
        .forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

    String displayType = SpawnerItem.formatType(spawnerType);
    final String amountValue = String.valueOf(amount);
    sendConfiguredMessage(
        sender,
        droppedAtFeet ? "give.sender-success-overflow" : "give.sender-success",
        msg ->
            msg.replace("%player%", target.getName())
                .replace("%amount%", amountValue)
                .replace("%type%", displayType));

    if (sender != target) {
      sendConfiguredMessage(
          target,
          "give.target-success",
          msg -> msg.replace("%amount%", amountValue).replace("%type%", displayType));
      if (droppedAtFeet) {
        sendConfiguredMessage(target, "give.target-overflow");
      }
    }

    return true;
  }

  private Player resolveOnlinePlayer(String input) {
    Player exact = Bukkit.getPlayerExact(input);
    if (exact != null) {
      return exact;
    }

    List<Player> matches = Bukkit.matchPlayer(input);
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private void sendUsage(CommandSender sender, String label) {
    sendConfiguredMessage(sender, "help.header");
    if (sender.hasPermission(RELOAD_PERMISSION)) {
      sendConfiguredMessage(sender, "help.reload", msg -> msg.replace("%label%", label));
    }
    if (sender.hasPermission(GIVE_PERMISSION)) {
      sendConfiguredMessage(sender, "help.give", msg -> msg.replace("%label%", label));
    }
  }

  private void sendConfiguredMessage(CommandSender sender, String path) {
    sendConfiguredMessage(sender, path, null);
  }

  private void sendConfiguredMessage(
      CommandSender sender, String path, java.util.function.UnaryOperator<String> formatter) {
    if (sender == null || path == null || path.isBlank()) {
      return;
    }

    String raw = plugin.getConfig().getString("messages.commands." + path);
    if (raw == null || raw.isBlank()) {
      raw = defaultMessage(path);
    }
    if (raw == null || raw.isBlank()) {
      return;
    }

    String formatted = formatter != null ? formatter.apply(raw) : raw;
    if (formatted == null || formatted.isBlank()) {
      return;
    }

    sender.sendMessage(SpawnerItem.color(formatted));
  }

  private String defaultMessage(String path) {
    return switch (path) {
      case "help.header" -> ChatColor.RED + "Usage:";
      case "help.reload" -> ChatColor.RED + "/%label% reload";
      case "help.give" -> ChatColor.RED + "/%label% give <player> <type> [amount]";
      case "reload.no-permission", "give.no-permission" ->
          ChatColor.RED + "You do not have permission to do that.";
      case "reload.usage" -> ChatColor.RED + "Usage: /%label% reload";
      case "reload.success" -> ChatColor.GREEN + "MajesticSpawners reloaded successfully.";
      case "reload.failure" -> ChatColor.RED + "Failed to reload MajesticSpawners. Check console.";
      case "give.usage" -> ChatColor.RED + "Usage: /%label% give <player> <type> [amount]";
      case "give.player-offline" -> ChatColor.RED + "That player must be online.";
      case "give.invalid-type" -> ChatColor.RED + "Invalid spawner type: %type%";
      case "give.invalid-amount" -> ChatColor.RED + "Amount must be a whole number.";
      case "give.amount-out-of-range" ->
          ChatColor.RED + "Amount must be between 1 and %max%.";
      case "give.sender-success" ->
          ChatColor.GREEN + "Gave %player% a %amount%x %type% spawner.";
      case "give.sender-success-overflow" ->
          ChatColor.GREEN
              + "Gave %player% a %amount%x %type% spawner. Inventory was full, so it was dropped at their feet.";
      case "give.target-success" -> ChatColor.GREEN + "You received a %amount%x %type% spawner.";
      case "give.target-overflow" ->
          ChatColor.YELLOW + "Your inventory was full, so the spawner was dropped at your feet.";
      default -> null;
    };
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> subcommands = new ArrayList<>();
      if (sender.hasPermission(RELOAD_PERMISSION)) {
        subcommands.add("reload");
      }
      if (sender.hasPermission(GIVE_PERMISSION)) {
        subcommands.add("give");
      }
      return filterByPrefix(subcommands, args[0]);
    }

    if (!args[0].equalsIgnoreCase("give") || !sender.hasPermission(GIVE_PERMISSION)) {
      return List.of();
    }

    if (args.length == 2) {
      List<String> playerNames =
          Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
      return filterByPrefix(playerNames, args[1]);
    }

    if (args.length == 3) {
      return filterByPrefix(SpawnerTypes.supportedSpawnerTypes(), args[2]);
    }

    if (args.length == 4) {
      return filterByPrefix(suggestAmounts(), args[3]);
    }

    return List.of();
  }

  private List<String> suggestAmounts() {
    int maxStack = plugin.getMaxSpawnerStack();
    if (maxStack <= 16) {
      List<String> smallSuggestions = new ArrayList<>();
      for (int amount = 1; amount <= maxStack; amount++) {
        smallSuggestions.add(String.valueOf(amount));
      }
      return smallSuggestions;
    }

    List<String> suggestions = new ArrayList<>();
    int[] common = {1, 2, 5, 10, 16, 32, 64, 128, 256};
    for (int amount : common) {
      if (amount <= maxStack) {
        suggestions.add(String.valueOf(amount));
      }
    }

    String maxValue = String.valueOf(maxStack);
    if (!suggestions.contains(maxValue)) {
      suggestions.add(maxValue);
    }
    return suggestions;
  }

  private List<String> filterByPrefix(List<String> values, String input) {
    String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
    return values.stream()
        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
        .collect(Collectors.toList());
  }
}
