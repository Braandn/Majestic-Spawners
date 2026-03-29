package de.braandn.events;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import de.braandn.SpawnersPlugin;

public class PlayerJoin implements Listener {

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent e) {
    if (e.getPlayer().isOp() || e.getPlayer().hasPermission("*")) {
      SpawnersPlugin.getInstance().getVersionChecker().notifyIfOutdated(e.getPlayer());
    }
  }

}
