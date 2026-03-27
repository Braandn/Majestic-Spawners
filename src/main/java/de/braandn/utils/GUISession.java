package de.braandn.utils;

import org.bukkit.inventory.Inventory;

public class GUISession {

  public enum Type {
    MAIN,
    STORAGE,
    STACK,
    CONFIRM
  }

  private final String spawnerId;
  private final boolean owner;
  private final Type type;
  private final Inventory inventory;
  private int page;
  private int pendingAmount;
  private String selectedStorageFilter;

  public GUISession(String spawnerId, boolean owner, Type type, Inventory inventory) {
    this.spawnerId = spawnerId;
    this.owner = owner;
    this.type = type;
    this.inventory = inventory;
    this.page = 0;
    this.pendingAmount = 0;
    this.selectedStorageFilter = "ALL";
  }

  public String getSpawnerId() {
    return spawnerId;
  }

  public boolean isOwner() {
    return owner;
  }

  public Type getType() {
    return type;
  }

  public Inventory getInventory() {
    return inventory;
  }

  public int getPage() {
    return page;
  }

  public void setPage(int page) {
    this.page = page;
  }

  public int getPendingAmount() {
    return pendingAmount;
  }

  public void setPendingAmount(int pendingAmount) {
    this.pendingAmount = Math.max(0, pendingAmount);
  }

  public String getSelectedStorageFilter() {
    return selectedStorageFilter;
  }

  public void setSelectedStorageFilter(String selectedStorageFilter) {
    this.selectedStorageFilter =
        selectedStorageFilter == null || selectedStorageFilter.isBlank()
            ? "ALL"
            : selectedStorageFilter;
  }
}
