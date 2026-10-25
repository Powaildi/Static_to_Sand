Static to Sand
==============

This NeoForge 1.21.1 mod adds a `Static to Sand` item. Right-click a block
with the item to select its connected cluster (up to 1024 blocks by default).
While held in the main hand, the item increases block interaction range by 10
blocks.
The item limit is configurable with `staticToSandMaxBlocks`. Other callers can
post a `StaticToSandEvent` with their own connected-block limit. The cluster
is moved one block per server tick away from the selection center. A normal
right-click task ends when Phase 1 can no longer move. Sneak-right-clicking
uses the player's position as the center and starts a new task directly in
Phase 2; when a phase-one task is already running, only the newly selected
blocks are flagged for Phase 2.

Horizontal movement uses the four cardinal directions and Chebyshev distance.
The `StaticToSandEvent` is posted on `NeoForge.EVENT_BUS` and accepts the server
level, trigger position, player, and maximum connected-block count. Its
approximate-matching overload can select connected blocks sharing configured
block tags instead of requiring the exact same block.
Events can also set `protectBlockEntities` to preserve block entity data while
selected blocks move; it defaults to `false`. An event triggered at an air block
is canceled immediately.
The `approximateMatchingTags` configuration defaults to `minecraft:dirt`,
`statictosand:approximate_stone`, and `minecraft:terracotta` (which includes
plain and colored terracotta). The latter includes stone, andesite, granite,
diorite, deepslate, tuff, dripstone, vanilla ores, and optional `c:ores` and
`forge:ores` tags for ores added by other mods.
While holding `static_to_sand`, Shift+left-click toggles approximate matching
for the player. Shift+scroll wheel doubles or halves the player's selection
limit; halving rounds up, and the value is clamped between 1 and
`staticToSandMaxBlocks`.
Gravity blocks can also be handled as events with `gravityBlockMode`: `NONE`
(default), `WHITELIST`, `BLACKLIST`, or `ALL`. The whitelist and blacklist use
the `statictosand:gravity_block_whitelist` and
`statictosand:gravity_block_blacklist` block tags. The default whitelist
contains sand, red sand, and gravel; the default blacklist contains all
anvils, the dragon egg, suspicious sand, and suspicious gravel. These blocks
are not handled in blacklist mode. `gravityBlockMaxBlocks` controls the
default selection limit for these events and defaults to 8.
The gravity hook is injected into `FallingBlock.tick` before the vanilla
falling-block entity is created, so handled blocks never become falling-block
entities. Brushable gravity blocks such as suspicious sand and suspicious
gravel are handled through a corresponding `BrushableBlock.tick` hook.
After the configured number of horizontal moves, an entry enters the waiting
zone. The default `horizontalMoveLimit` is 32 and can be changed in
`run/config/statictosand-common.toml`.
The `fluidsAreAir` option controls whether fluids can be used as movement
targets; it is disabled by default.
The `replaceFallingBlockWithFluid` option controls whether a downward-moving
block leaves a neighboring horizontal fluid source in its old position; it is
disabled by default.
The `activeBlockLimit` option limits the active-zone blocks to 4096 by default.
When the limit is exceeded, the oldest active blocks are moved to the waiting
zone. The `waitingBlockLimit` option limits the waiting-zone blocks to 16384 by
default; when it is exceeded, the oldest waiting blocks are removed from the
task.

The item is available in the vanilla Tools & Utilities creative tab and has the
ID `statictosand:static_to_sand`.

Building
--------

Run `gradlew.bat build` on Windows.

Game tests
----------

Run `gradlew.bat runGameTestServer` to execute the movement tests. They cover
the one-way Phase 1 path and the sneak-right-click Phase 2 path.

VS Code
-------

ModDevGradle generates VS Code launch configurations that load the mod from the
Eclipse-style `bin/main` directory. The VS Code Gradle Build Server compiles to
`build/classes/java/main` instead, which leaves `bin/main` stale (and skips the
`prepare*Run` tasks for the non-client runs). For that reason this workspace
disables the Gradle Build Server in `.vscode/settings.json`:

```json
"java.gradle.buildServer.enabled": "off"
```

After changing that setting, run **Developer: Reload Window** once. If the Java
language server still reports the old layout, run **Java: Clean Java Language
Server Workspace** and reload again. The Client, Server, Data and GameTestServer
configurations are then available under Run and Debug.

The generated Run and Debug configurations run the `Sync VS Code mod output`
pre-launch task automatically, which refreshes `bin/main` from the latest
Gradle output before every launch. Running the server requires `eula=true` in
`run/eula.txt`.

Mapping names
-------------

The project uses the official Minecraft 1.21.1 mappings through NeoForge
ModDevGradle.
