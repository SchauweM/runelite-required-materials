# Required Materials

Tracks what you still need to build things in **Sailing** and **Construction**.

## Adding something to your list

Click anything you want to build:

| Where | What to click |
|---|---|
| Sailing skill guide | any entry |
| Construction skill guide | any entry |
| Boat Customisation | **Build** or **Check Materials** |
| Furniture Creation menu | **Build** |

Clicking **Build** on something you can already make won't add it — there's nothing left to
collect.

## Reading your list

The plugin checks your bank and inventory against what each build needs.

<img src="docs/side-panel.png" alt="The side panel showing tracked builds" width="300">

Upgrades need the previous version built first. Visit your house once in building mode and the
plugin remembers what's in there, so those lines turn green too.

## Using it at the bank

The hammer button near the scrollbar regroups your bank around what you're collecting. Click it
again to put your bank back.

<img src="docs/bank-view.png" alt="The bank grouped by build" width="600">

## Settings

| Setting | Default | What it does |
|---|---|---|
| Don't track builds you can already make | on | Clicking **Build** on something you have everything for won't add it to your list |
| Clear builds when you make them | off | Building something takes it off your list |

## Where the numbers come from

The [OSRS Wiki](https://oldschool.runescape.wiki), so they stay correct without the plugin
needing an update. Only the name of the thing you clicked is ever sent.

Mahogany Homes, bird houses, STASH units and the eternal fires aren't on the wiki in a readable
form, so those are read from the game instead.

## Installing

```bash
gradle jar
mkdir -p ~/.runelite/sideloaded-plugins && cp build/libs/required-materials-1.0.0.jar ~/.runelite/sideloaded-plugins/
```

Then enable **Required Materials** in RuneLite's plugin list.

## How to contribute

### Running it locally

```bash
gradle run
```

Launches the real client with the plugin loaded, against your own account.

RuneLite doesn't get along with very new JDKs — on Java 26 the event bus fails to register
subscribers. Point it at an older one if `gradle run` misbehaves:

```bash
gradle run -PrunJdk=/path/to/jdk/bin/java
```

Or put `runJdk=/path/to/jdk/bin/java` in a `gradle.properties` at the project root, which is
gitignored.

The `--add-opens` flags in the `run` task mirror the official launcher's. Without them event
subscriber registration silently falls back to slower reflection, so leave them in place.

Restart with:

```bash
pkill -f RequiredMaterialsPluginTest && gradle run
```

### How the code is laid out

| Class | Does what |
|---|---|
| `RequiredMaterialsPlugin` | Event handling; decides what got clicked and in which skill |
| `WikiRecipeService` | Fetches and parses `{{Recipe}}` templates, cached per page |
| `ChatMaterialsParser` | Fallback: materials from chat messages |
| `GuideLevelReader` | Fallback: levels from skill guide widgets |
| `MaterialsManager` | Stores tracked builds, resolves item IDs, persists to config |
| `BankSnapshot` | Remembers bank contents between sessions |
| `RequiredMaterialsPanel` | The side panel |
| `BankGroupedView` | Rebuilds the bank grid |
| `BankButtonManager` | The bank toggle button |

### Things worth knowing before you change something

- **Client thread.** Most `Client` calls assert they're on it; Swing listeners and wiki
  callbacks are not. Go through `ClientThread.invoke`.
- **Widget IDs** come from `net.runelite.api.gameval.InterfaceID`, not the deprecated `WidgetID`.
- **Dynamic widget children.** Some interface text lives on dynamically-created children whose
  `getId()` reports their static ancestor. The skill guide title is one — reading it directly
  returns blank, you have to walk `getDynamicChildren()`.
- **Widget positions.** `getRelativeX/Y` are relative to a widget's own parent, and aren't
  usable until the interface has finished building.
- **Item IDs.** `ItemManager.search()` only covers GE-tradeable items; everything else falls
  back to scanning the client's item definitions. Unresolved items show red in the panel.
- **Wiki quirks.** An omitted `matNquantity` means 1, and a page with no recipe may be a
  disambiguation page whose first link is the real one.
