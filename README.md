Static to Sand
==============

This NeoForge 1.21.1 mod adds a `Static to Sand` item. Right-click a block
with the item to select its connected cluster (up to 1024 blocks). The cluster
is moved one block per server tick: first away from the selection center, then
back toward it. Sneak-right-clicking starts directly with the centering phase.

The item is available in the mod's creative tab and has the ID
`statictosand:static_to_sand`.

Building
--------

Run `gradlew.bat build` on Windows.

Mapping names
-------------

The project uses the official Minecraft 1.21.1 mappings through NeoForge
ModDevGradle.
