![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Releases](https://img.shields.io/github/v/release/Braandn/Majestic-Spawners)
![License](https://shields.io/github/license/Braandn/Majestic-Spawners)

# Majestic-Spawners

Majestic-Spawners is a Paper spawner plugin for survival servers, lifesteal servers, SMPs, and economy-focused Minecraft communities that want a DonutSMP-style spawner plugin without the mob lag and grinder clutter. It turns mob spawners into managed, stackable production blocks that generate virtual loot and XP over time, then lets players collect everything through an in-game GUI.

It is inspired by DonutSMP, but it is not an official clone.

If you are searching for a DonutSMP spawner plugin, virtual spawner plugin, stacked spawner plugin, Minecraft spawner plugin, or Paper spawner plugin, Majestic-Spawners is built for that gameplay style.

## Why Server Owners Use It

Majestic-Spawners keeps the progression loop players want from spawner-based gameplay while avoiding the downside of traditional mob farms. Players still get stacking, upgrading, loot collection, and XP farming, but the server stays cleaner because production is handled virtually instead of filling chunks with entities.

For server owners, that means a survival server plugin that fits lifesteal and economy gameplay, gives control over access rules and drop balance, and scales better than entity-heavy mob spawner systems.

## What It Does

- Creates custom typed spawner items with packed amounts and mob metadata
- Turns placed spawners into managed virtual production blocks
- Generates virtual loot and XP instead of real mob spawning
- Supports stacked spawners so multiple matching spawners act as one placed block
- Opens GUI-based storage, loot collection, and XP collection
- Applies owner access rules for `normal`, `view-only`, and `blocked` interaction
- Supports recipe-based type changes for custom progression
- Persists spawner data with SQLite
- Produces only while the chunk is loaded for intentional, performance-friendly gameplay
- Uses one global processing loop instead of one task per spawner

## How It Feels In Game

A player gets a custom spawner item, places it, and immediately has a managed spawner with an owner, mob type, stack amount, loot storage, and XP pool.

From there, the loop is simple and satisfying:

- right-click the spawner to open the GUI
- collect stored loot
- collect stored XP
- stack more matching spawners into it
- remove one spawner or the full stack when needed
- keep progressing without building laggy mob rooms

That makes Majestic-Spawners a strong fit for grind-focused survival servers, lifesteal servers, economy SMPs, and DonutSMP-inspired communities.

## Best For

- Survival servers
- Lifesteal servers
- Economy SMPs
- Grind-focused progression servers
- DonutSMP-inspired Minecraft servers
- Servers that want stacked, virtual, GUI-driven spawner gameplay

## Core Features

- Custom spawner items with typed metadata and packed amounts
- Virtual loot generation with per-mob balancing through `drops.yml`
- Virtual XP generation with configurable per-mob XP ranges
- Shared storage and XP pools for placed stacked spawners
- Ownership and access control for protected or shared use
- Recipe-driven spawner type conversion
- SQLite-backed persistence
- Chunk-loaded production that keeps behavior predictable
- Performance-friendly architecture designed for scale

## Setup

For installation, configuration, commands, balancing, and admin workflow, use the setup guide:

[SETUP.md](https://github.com/Braandn/Majestic-Spawners/blob/main/SETUP.md)

## Compatibility

- Paper `1.21+`
- Java `21`

## License

This project is licensed under the GNU General Public License v3.0.
See [LICENSE](LICENSE).
