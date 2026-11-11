# PLAN-031: The launcher as a hub of engines

Status: **step 1 done** (11/11/2026, not yet on a device): the launcher recognises a game by its files.
Steps 2-4 are engine ports, each its own piece of work.

## Request (11/11/2026)

"An engine like PojavLauncher that runs all the old games by moving the game files into the engine."
Of the two ways to get there -- one emulation layer for every Windows game (Wine + Box64, what
Winlator does), or one native engine per game behind one launcher -- the owner chose the second:
it is what this app already is for Generals and Zero Hour, it runs at native speed on weak phones,
and it ships no game data (the player copies the files of a game they own, as PojavLauncher does).

## The model

- **One launcher, many engines.** Each engine is a native library in the APK with an SDL entry point
  (`SDL_main`), loaded by `GeneralsZHActivity.getLibraries()`. Today: `libmain*.so` (Zero Hour) and
  `libgenerals*.so` (Generals), 30 Hz and 60 Hz each.
- **The files say which game they are.** `GameCatalog.java` lists every game the launcher knows, with
  the files that identify its folder (any case, since copies come from Windows) and, when it runs
  today, the engine libraries. Nothing else in the launcher should name a game's files or libraries.
- **Recognised before runnable.** A game is listed as soon as its folder can be told apart, without
  an engine. A player who copies its folder is told which game it is and what is planned for it,
  instead of "this is not a game folder".

## Step 1 (done): recognition

- `GameCatalog`: Zero Hour (`INIZH.big`), Generals (`INI.big` without `INIZH.big`) runnable;
  Renegade (`Data/always.dat`), Red Alert (`REDALERT.MIX`), Tiberian Dawn (`CONQUER.MIX`) planned;
  Yuri's Revenge (`RA2MD.MIX`), Red Alert 2 (`RA2.MIX`), Tiberian Sun (`TIBSUN.MIX`) recognised only.
  Expansions are listed before their base game: the first match wins.
- Picking a folder (`SetupActivity.onGameFolderPicked`): the folder, or the only game folder up to
  three levels below it, is identified. The other runnable game -> a dialog offers to switch to it
  (or keep the selected one, which then runs the old checks). A game without an engine -> a dialog
  names it and its engine plan, and nothing is saved.
- `getSelectedGame` / `setSelectedGame` / `engineLibraries` read the catalog. The stored ids (`zh`,
  `generals`) are unchanged, so existing installs keep their game and folders.
- Home page: one line saying the folder decides the game, and which games are recognised but not
  yet runnable.

## Step 2: Renegade (EA, GPL-3, released 02/2025)

The closest relative: Renegade's WWVegas/WW3D2 is the ancestor of the libraries Generals is built
on, so the DX8 -> DXVK/GLES layer, the OpenAL audio and the SDL3 platform code of this port apply.
Work: a CMake target `librenegade.so` for the `android-vulkan` preset, the platform layer swapped as
for Generals, touch controls for a first-person game (a new scheme, not the RTS one), GameSpy
networking removed. Largest of the three, highest reuse.

## Step 3: Tiberian Dawn and Red Alert (EA, GPL-3, released 2020)

Through Vanilla Conquer, the community's cross-platform build of that source (SDL2 + OpenAL). Work:
build it against this APK's SDL3 (`sdl2-compat` or a port of its few SDL calls), one library per
game (`libvanillatd.so`, `libvanillara.so`), the data folder passed as its working directory, and a
touch layer (a 2D RTS: tap select, drag box, the same gestures as `TouchInput.cpp` in spirit).

## Step 4: what the launcher still assumes

Before a third runnable game ships:
- `GeneralsZHActivity` writes Generals-specific markers (art path, base archives, language) for
  every launch; these move behind the game's catalog entry.
- `userDataDir`, `gamePathPref`, the external markers and the folder checks are written for two games;
  each needs a per-game form taken from the catalog.
- The Home page's two-way switch becomes a list of the runnable games.
- `FolderPickerActivity`'s hint judges only the selected game; it can name the game it sees.

## Out of scope

Games whose source was never released (Tiberian Sun, Red Alert 2, Yuri's Revenge, and most other
old PC games) have no engine to port. Running them means emulating Windows (Wine + Box64 + DXVK),
which is a different product -- Winlator and GameHub already exist. The catalog says so to the
player rather than pretending.
