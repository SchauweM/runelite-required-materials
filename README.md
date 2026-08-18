# Required Materials

A RuneLite plugin that tracks what you still need to build things in **Sailing** and
**Construction**. Click a build in-game and it lands in a side panel showing your bank counts
against the required amounts, plus the skill levels it needs. Open your bank and a toggle
rearranges the grid to put exactly those items in front of you.

## How it works

### Tracking something

Click any build in-game and it gets tracked. Four places work:

| Where | What to click |
|---|---|
| Sailing skill guide | any entry |
| Construction skill guide | any entry |
| Boat Customisation | **Build**, or **Check Materials** |
| Furniture Creation menu | **Build** |

Both the old and new skill guide layouts are supported. Clicking a build you're already
tracking refreshes it and moves it to the bottom of its list.

### Where the numbers come from

Requirements are read from the item's [OSRS Wiki](https://oldschool.runescape.wiki) page —
specifically its `{{Recipe}}` template, which carries the materials and the skill levels. The
game itself only reliably tells us *which* item you clicked, so the wiki fills in the rest.

Nothing is scraped from the game's interfaces, which matters because the same information is
presented three different ways depending on where you click, and one of those (Boat
Customisation) shows levels only as unlabelled icons and numbers.

A few Construction activities aren't documented with a `{{Recipe}}` — Mahogany Homes,
Birdhouses, STASH units, and the eternal fires. Those fall back to reading the materials out
of the game's chat message and the levels out of the skill guide, so they still track.

### The side panel

Tracked builds are grouped into a collapsible section per skill, with the most recently
tracked skill on top. Each build shows:

- **Skill levels**, green once you meet them, grey while you don't.
- **Materials**, as `have/need` — green when you have enough, orange when you have some,
  grey at zero.

Grey rather than red at zero is deliberate: a count of zero could equally mean "you own none"
or "you haven't opened your bank this session", and the plugin can't tell those apart.

Boat parts that come in several sizes — `(raft)`, `(skiff)`, `(sloop)` — share a single card
with a dropdown instead of appearing as three lookalike entries. A raft's *base* and a
skiff/sloop's *hull* are the same slot under different names, so they're merged too.

Quantities update live as your bank changes, and level colours update when you level up.

### The bank view

A small **F** button sits in the top-right of the bank window. Toggling it on rebuilds the
bank grid so tracked materials come first, grouped under a heading per build, each with a
`have / need` label and a tick or cross. Everything else in your bank drops below under
**Other items**.

This reuses the bank's real item slots rather than drawing an overlay, so withdrawing works
normally. Toggle it off and your bank is exactly as it was.

## Installing

Build the jar and drop it in RuneLite's sideload folder:

```bash
gradle jar
```

```bash
mkdir -p ~/.runelite/sideloaded-plugins && cp build/libs/required-materials-1.0.0.jar ~/.runelite/sideloaded-plugins/
```

Start RuneLite and enable **Required Materials** in the plugin list. The plugin has no
settings — tracking is driven entirely by what you click in-game.

## How to contribute

### Running it locally

```bash
gradle run
```

This launches the real RuneLite client in-process with the plugin already loaded, so you can
log in and test against your own account. It restores your usual profile and session the same
way the normal client does.

By default this runs on whatever JDK Gradle itself is using. RuneLite doesn't get along with
very new JDKs — on Java 26 the event bus fails to register subscribers — so if `gradle run`
misbehaves, point it at an older one without editing the build:

```bash
gradle run -PrunJdk=/path/to/jdk/bin/java
```

To avoid passing that every time, put it in a `gradle.properties` at the project root, which
is gitignored:

```
runJdk=/path/to/jdk/bin/java
```

The `--add-opens` flags in the `run` task mirror the official launcher's own. Without them,
event subscriber registration silently falls back to slower reflection and logs
`LambdaConversionException` warnings, so leave them in place if you change the JDK.

To restart after a code change, kill the running dev client and start it again — a plain
`SIGTERM` shuts it down cleanly:

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
| `RequiredMaterialsPanel` | The side panel |
| `BankGroupedView` | Rebuilds the bank grid |
| `BankButtonManager` | The bank toggle button |

### Things worth knowing before you change something

- **Client thread.** Most RuneLite `Client` calls assert they're on the client thread, and
  Swing listeners are not. Anything touching game state from a UI callback has to go through
  `ClientThread.invoke`. Wiki lookups come back on an HTTP thread and need the same treatment.
- **Widget IDs** come from `net.runelite.api.gameval.InterfaceID`, not the deprecated
  `WidgetID`.
- **Dynamic widget children.** Some interface text lives on dynamically-created children whose
  `getId()` reports their static ancestor. The skill guide title is one of these — reading it
  directly always returns blank; you have to walk `getDynamicChildren()`.
- **Item IDs.** `ItemManager.search()` only covers GE-tradeable items, so non-tradeable
  materials fall back to a scan of the client's own item definitions. Anything that still
  doesn't resolve shows in red in the panel and can't be highlighted in the bank.
- **Wiki quirks.** An omitted `matNquantity` means 1, and a page with no recipe may be a
  disambiguation page whose first link is the real one.
