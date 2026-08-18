# Ship Materials

A RuneLite plugin for OSRS Sailing: tracks which materials you need for ship upgrades
(parsed from the chat message you get when clicking a requirement in-game), and lets you
highlight those items in your bank with a toggle button.

## Features

- **Requirement tracking**: click a ship upgrade's requirements in-game (e.g. in the ship
  customisation screen). The game sends a chat message like:
  `Oak mast and linen sail materials: Oak logs x5, Iron nails x 20, Bolt of linen x5.`
  This plugin parses that message and adds it to a tracked list, viewable (and clearable)
  in the sidebar panel.
- **Bank highlight**: a small toggle button is injected into the top-right corner of the
  bank window (next to the close button, matching where Quest Helper places its own bank
  button). Toggling it on draws a colored border around every bank item that's part of a
  tracked requirement, and dims everything else.

## Building

```
gradle jar
```

Produces `build/libs/ship-materials-1.0.0.jar`.

## Testing against your real account (dev mode)

```
gradle run
```

This launches the actual RuneLite client in-process with the plugin already loaded
(`ExternalPluginManager.loadBuiltin` + `RuneLite.main`), so you can log in and test against
your real account. It restores your last saved profile/session the same way the normal
client does.

Notes:
- The `run` task is pinned to a Temurin 25 JDK and passes the `--add-opens` flags RuneLite's
  event bus needs; on very new JDKs (26+) without those flags, event subscriber registration
  can silently fall back to slower reflection and log `LambdaConversionException` warnings.
  If `gradle run` can't find that JDK path on your machine, edit the `executable` line in
  `build.gradle`'s `run` task to point at a JDK 11-21 `java` binary you have installed.
- To restart after a code change: kill the running dev-client process
  (`pgrep -fl ShipMaterialsPluginTest`, then `kill <pid>` - a plain SIGTERM is enough, it
  shuts down cleanly) and run `gradle run` again.

## Installing for normal play (sideloading)

Official RuneLite (the release client, not this dev-mode launcher) scans
`~/.runelite/sideloaded-plugins/` for external plugin jars:

```
mkdir -p ~/.runelite/sideloaded-plugins
cp build/libs/ship-materials-1.0.0.jar ~/.runelite/sideloaded-plugins/
```

Then start RuneLite normally and enable "Ship Materials" from the plugin list if it isn't
already on.

## Implementation notes

- Widget IDs use the current, non-deprecated `net.runelite.api.gameval.InterfaceID` constants
  rather than the deprecated `WidgetID` class. These were verified against the actual
  `runelite-api` jar (1.12.35) rather than guessed - that jar already ships Sailing's own
  interfaces (`InterfaceID.SailingCustomisation`, etc.), confirming this API version is
  current enough for Sailing content.
- The bank button's position (`BankButtonManager`) was matched against Quest Helper's own
  bank button placement by decompiling the quest-helper plugin jar already present in this
  RuneLite install - it anchors to `InterfaceID.Bankmain.UNIVERSE` at a fixed pixel offset
  near the top-right corner rather than to any specific "close button" widget.
- Item name -> item ID resolution uses `ItemManager.search()`, matched case-insensitively
  against the parsed material name. If a name doesn't resolve (e.g. a typo, or a very new
  item not yet in the client's item name index), it still shows in the sidebar panel (in red)
  but won't be highlightable in the bank until it resolves.
