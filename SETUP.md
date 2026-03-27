# MajesticSpawners Setup Guide

This guide covers installation, configuration, admin commands, and balancing for MajesticSpawners.

If you want the plugin overview first, read the main [README](https://github.com/Braandn/MajesticSpawners/blob/main/README.md).

## Server Requirements

- Paper `1.21+`
- Java `21`

## Quick Start

1. Build or download `MajesticSpawners.jar`.
2. Move the jar into your server's `plugins` folder.
3. Start the server once so MajesticSpawners generates its files.
4. Stop the server.
5. Edit `plugins/MajesticSpawners/config.yml` and `plugins/MajesticSpawners/drops.yml`.
6. Start the server again.

After changing the configuration, you can reload it with:

```text
/majesticspawners reload
```

## Installation Walkthrough

### 1. Install the jar

Place `MajesticSpawners.jar` into:

```text
plugins/
```

### 2. Generate the plugin files

Start the server once. The plugin will create:

```text
plugins/MajesticSpawners/config.yml
plugins/MajesticSpawners/drops.yml
plugins/MajesticSpawners/spawners.db
```

### 3. Configure your economy and gameplay

Most servers should review these values before going live:

- `spawner.max-spawner-stack`
- `spawner.max-items-per-spawner`
- `spawner.tick-interval-ticks`
- `spawner.collect-xp`
- `spawner.silktouch-needed`
- `spawner.non-owner-access`
- `recipes.type-change.enabled`

### 4. Give players their first custom spawners

Use:

```text
/majesticspawners give <player> <type> [amount]
```

Examples:

```text
/majesticspawners give Steve ZOMBIE
/majesticspawners give Alex WITHER_SKELETON 25
```

### 5. Reload after config edits

Use:

```text
/majesticspawners reload
```

For major edits, a full restart is still the safest choice before production use.

## How MajesticSpawners Works

MajesticSpawners does not use vanilla mob-farm behavior for managed spawners. Instead:

1. Players place a custom spawner item created by the plugin.
2. That block becomes a managed spawner with an owner, mob type, stack amount, storage, and XP pool.
3. While the chunk is loaded, the plugin generates loot and XP over time.
4. Players right-click the spawner to open the GUI.
5. They collect loot, collect XP, add matching spawners, or remove spawners from the stack based on permissions.

Important: managed spawners only produce while their chunk is loaded.

## First Config Pass

### `config.yml`

Location:

```text
plugins/MajesticSpawners/config.yml
```

Core defaults:

```yml
spawner:
  processing-interval-ticks: 20
  tick-interval-ticks: 400
  save-interval-seconds: 60
  max-items-per-spawner: 15000
  max-spawner-stack: 500
  silktouch-needed: true
  collect-xp: true
  non-owner-access: "normal"
  xp-ranges: {}
```

Recommended starting interpretation:

- `processing-interval-ticks`: how often active managed spawners are processed
- `tick-interval-ticks`: the main pacing value for how quickly one logical spawner generates rewards
- `save-interval-seconds`: how often dirty spawner data is queued for persistence
- `max-items-per-spawner`: total stored item count before extra loot is discarded
- `max-spawner-stack`: maximum logical size of one placed stack
- `silktouch-needed`: requires Silk Touch for survival players to mine managed spawners
- `collect-xp`: shows or hides the XP collection button in the GUI
- `non-owner-access`: controls whether other players can use someone else's spawner

Valid `non-owner-access` values:

- `normal`
- `view-only`
- `blocked`

### XP overrides

`spawner.xp-ranges` supports these formats:

- `5`
- `"3-6"`
- `[3, 6]`
- `{ min: 3, max: 6 }`

Example:

```yml
spawner:
  xp-ranges:
    ZOMBIE: "3-6"
    BLAZE: [6, 10]
    WITHER: 50
```

### `drops.yml`

Location:

```text
plugins/MajesticSpawners/drops.yml
```

Example:

```yml
drops:
  ZOMBIE:
    - material: ROTTEN_FLESH
      amount: "0-2"
    - material: IRON_INGOT
      amount: 1
      chance: 2.5
```

Each drop entry supports:

- `material`
- `amount`
- `chance` (optional)

Supported `amount` formats:

- `2`
- `"1-3"`
- `"1..3"`
- `[1, 3]`
- `{ min: 1, max: 3 }`

Supported `chance` formats:

- `35`
- `"35"`
- `0.35`
- `"35%"`

Rules:

- If `chance` is omitted, the drop is always rolled.
- Values between `0` and `1` are treated as percentages of `100`.

### Missing mob entry vs `[]`

These are different on purpose:

- Missing mob entry: the plugin tries the Paper loot table first, then falls back to emergency hardcoded drops for supported types.
- Present mob entry with `[]`: the spawner generates no item drops.

This makes it easy to create:

- custom drop tables
- mostly vanilla-like drops
- XP-only spawners

## Recipes

MajesticSpawners can let players convert a base spawner into a typed custom spawner through `recipes.type-change`.

Important options:

- `enabled`
- `allow-base-input`
- `main`
- `overrides`

Valid `allow-base-input` values:

- `VANILLA_ONLY`
- `VANILLA_OR_SINGLE_CUSTOM`
- `CUSTOM_ONLY`

Example use case:

- Keep the default recipe for most spawners.
- Add overrides for premium or rare spawners such as `WITHER_SKELETON`.

## Commands

```text
/majesticspawners reload
/majesticspawners give <player> <type> [amount]
/mspawners reload
/mspawners give <player> <type> [amount]
```

### Reload

`/majesticspawners reload` refreshes:

- `config.yml`
- `drops.yml`
- recipe registrations
- cached XP ranges
- open GUI rendering

### Give

`/majesticspawners give <player> <type> [amount]`

- `<player>` must be online
- `<type>` must be a supported living mob type
- `[amount]` defaults to `1`
- amount must be between `1` and `spawner.max-spawner-stack`

If the target inventory is full, the custom spawner item is dropped at the player's feet.

## Permissions

```text
majesticspawners.command
majesticspawners.give
majesticspawners.reload
```

Defaults from `plugin.yml`:

- `majesticspawners.command`: `op`
- `majesticspawners.give`: `false`
- `majesticspawners.reload`: `false`

`majesticspawners.command` also grants:

- `majesticspawners.give`
- `majesticspawners.reload`

## Supported Spawner Types

MajesticSpawners accepts every living `EntityType` except `PLAYER` and `UNKNOWN`.

Alias handling includes:

- `MUSHROOM_COW` -> `MOOSHROOM`
- `PIG_ZOMBIE` -> `ZOMBIFIED_PIGLIN`
- `SNOWMAN` -> `SNOW_GOLEM`

## Player-Facing Notes

- Managed spawners store loot and XP internally instead of filling the area with mobs.
- A stacked spawner acts as one block with one owner, one shared storage inventory, and one shared XP pool.
- Matching custom spawners can be added into an existing placed stack.
- Players can remove one spawner or the full stack through the stack GUI.
- If the final spawner is removed, stored items and XP are dropped.

## Persistence

Spawner data is saved in:

```text
plugins/MajesticSpawners/spawners.db
```

Stored data includes:

- world location
- owner UUID
- mob type
- placed stack amount
- stored XP
- stored inventory contents

## Build From Source

Requirements:

- Java `21`
- Maven

Build with:

```bash
mvn -q -DskipTests package
```

Output:

```text
target/MajesticSpawners.jar
```

## Troubleshooting

### Spawners are not producing

Check these first:

- the spawner was created by MajesticSpawners, not as a plain vanilla item
- the chunk is loaded
- `drops.yml` is not set to `[]` for that mob unless you want XP-only behavior
- item storage is not full

### Players cannot break their spawners

Check:

- `spawner.silktouch-needed`
- whether the player is in Creative mode
- whether they are using a Silk Touch pickaxe

### Non-owners cannot access a spawner

Review:

- `spawner.non-owner-access`
- whether your intended behavior is `normal`, `view-only`, or `blocked`
