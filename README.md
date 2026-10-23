Static to Sand
==============

This NeoForge 1.21.1 mod adds a `Static to Sand` item. Right-click a block
with the item to select its connected cluster (up to 1024 blocks). The cluster
is moved one block per server tick: first away from the selection center, then
back toward it. Sneak-right-clicking uses the player's position as the center
and starts a new task directly in the centering phase; when a phase-one task is
already running, only the newly selected blocks are flagged for phase two.

The item is available in the vanilla Tools & Utilities creative tab and has the
ID `statictosand:static_to_sand`.

Building
--------

Run `gradlew.bat build` on Windows.

Game tests
----------

Run `gradlew.bat runGameTestServer` to execute the movement tests. They cover
the full away/return cycle and the sneak-right-click phase-two path.

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

`gradlew.bat syncVsCodeOutput` refreshes `bin/main` from the Gradle output if
needed. Running the server requires `eula=true` in `run/eula.txt`.

Mapping names
-------------

The project uses the official Minecraft 1.21.1 mappings through NeoForge
ModDevGradle.
