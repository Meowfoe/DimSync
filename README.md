# DimSync

Keeps your group together when someone changes dimension. If a player steps into the Nether, End, or any modded dimension, everyone else online gets pulled to that exact spot. No more losing someone because they walked through a portal first.

Works with modded dimensions too by figuring out what dimensions exist on your server and remembering them.

I made this mod specifically because of horror mods like untitled.log which teleport players but dont sync for everyone.

## What it does

- Someone changes dimension → everyone else teleports to their exact position, same block, same facing direction
- Works for vanilla and modded dimensions alike
- Keeps track of every dimension it's seen on your server (survives restarts)
- Can be turned off/on with a command if you want to split up for a bit

## Commands

- `/dimsync toggle` - turn it on/off (op only)
- `/dimsync status` - check if it's on, and how many dimensions it knows about
- `/dimsync list` - see every dimension it's found so far

## Requires

- Fabric Loader
- Fabric API
- Minecraft 1.20.1

## Installing (for players)

Drop the built jar into your `mods` folder along with Fabric API. No config needed.

## Notes

- Everyone lands on the same exact block as whoever moved, it doesn't try to spread people out
- If you're offline when the group moves, you just stay where you logged out

## Project layout

```
dimsync-mod/
  build.gradle
  settings.gradle
  gradle.properties
  src/main/java/com/dimsync/
    DimSyncMod.java          - entrypoint, the dimension-change listener
    DimensionRegistry.java   - discovery + persistence of dimension -> ID
    DimSyncCommand.java      - /dimsync toggle|on|off|status|list
  src/main/resources/
    fabric.mod.json
    assets/dimsync/icon.png
```

## Building from source

1. Install a JDK 17.
2. Bootstrap the Gradle wrapper once (see below if `gradlew`/`gradlew.bat` aren't present yet).
3. Run:
   - Windows: `gradlew.bat build`
   - macOS/Linux: `./gradlew build`
4. Grab the built jar from `build/libs/dimsync-<version>.jar`.

### Bootstrapping the wrapper

If `gradlew`/`gradlew.bat` are missing, install Gradle 8.5 (via SDKMAN, Homebrew, or the manual zip on Windows), then run:

```
gradle wrapper --gradle-version 8.5
```

This generates `gradlew`, `gradlew.bat`, and the wrapper jar, so from then on you only ever use `./gradlew` and never need Gradle installed system-wide again.

Before building, double check the versions in `gradle.properties` against whatever's current at https://fabricmc.net/develop/, since Yarn mappings, loader, and Fabric API get patch updates fairly often.
