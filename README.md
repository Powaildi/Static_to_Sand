Static to Sand
==============

This NeoForge 1.21.1 mod adds a `Static to Sand` item. Right-click a block
with the item to select its connected cluster (up to 1024 blocks). The cluster
is moved one block per server tick away from the selection center. A normal
right-click task ends when Phase 1 can no longer move. Sneak-right-clicking
uses the player's position as the center and starts a new task directly in
Phase 2; when a phase-one task is already running, only the newly selected
blocks are flagged for Phase 2.

Horizontal movement uses the four cardinal directions and Chebyshev distance.
After the configured number of horizontal moves, an entry enters the waiting
zone. The default `horizontalMoveLimit` is 32 and can be changed in
`run/config/statictosand-common.toml`.
The `fluidsAreAir` option controls whether fluids can be used as movement
targets; it is disabled by default.
The `replaceFallingBlockWithFluid` option controls whether a downward-moving
block leaves a neighboring horizontal fluid source in its old position; it is
disabled by default.

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
