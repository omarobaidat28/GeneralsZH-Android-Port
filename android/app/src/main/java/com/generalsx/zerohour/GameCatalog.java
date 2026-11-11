package com.generalsx.zerohour;

import android.content.Context;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// GeneralsX @feature Android port 11/11/2026 PLAN-031: the launcher as a hub of engines.
//
// The model is the one this app already follows for Generals and Zero Hour, and the one
// PojavLauncher follows for Minecraft: the player copies the files of a game they own, and the
// launcher runs them on an engine built for the phone. What this class adds is that the files
// themselves say which game they are. Every game the launcher knows is listed once, with the
// files that identify its folder and -- when it can run today -- the native engine libraries
// that run it. A game listed without engine libraries is recognised but not yet runnable, so a
// player who copies its folder is told which engine is coming instead of "this is not a game
// folder".
//
// Adding a game is one entry here plus its engine (libraries in the APK, a native entry point).
// Nothing else in the launcher should name a game's files or libraries.
final class GameCatalog {

    static final class Game {
        final String id;
        /** String resource of the name when the launcher has one, else 0 and {@link #name} is shown. */
        final int nameRes;
        final String name;
        /** Base names for System.loadLibrary, 30 Hz first and 60 Hz second; empty when no engine yet. */
        final String[] engineLibraries;
        /** A folder is this game when it holds every one of these (relative paths, any case)... */
        final String[] allOf;
        /** ...and none of these. */
        final String[] noneOf;
        /** For a game without an engine yet: what is planned for it (string resource). */
        final int engineNoteRes;

        Game(String id, int nameRes, String name, String[] engineLibraries,
                String[] allOf, String[] noneOf, int engineNoteRes) {
            this.id = id;
            this.nameRes = nameRes;
            this.name = name;
            this.engineLibraries = engineLibraries;
            this.allOf = allOf;
            this.noneOf = noneOf;
            this.engineNoteRes = engineNoteRes;
        }

        boolean isRunnable() {
            return engineLibraries.length > 0;
        }

        String title(Context ctx) {
            return nameRes != 0 ? ctx.getString(nameRes) : name;
        }

        String engineNote(Context ctx) {
            return engineNoteRes != 0 ? ctx.getString(engineNoteRes) : "";
        }
    }

    /** A recognised game and the folder that holds it (the one picked, or one below it). */
    static final class Found {
        final Game game;
        final File dir;

        Found(Game game, File dir) {
            this.game = game;
            this.dir = dir;
        }
    }

    private static final String[] NONE = new String[0];

    // Order matters: the first entry a folder matches wins, so an expansion comes before the game
    // it extends (Zero Hour before Generals, Yuri's Revenge before Red Alert 2).
    private static final List<Game> GAMES = Collections.unmodifiableList(Arrays.asList(
        // Runs today. The ids are the values SetupActivity.PREF_GAME has always held.
        new Game(SetupActivity.GAME_ZERO_HOUR, R.string.setup_game_zero_hour, "Zero Hour",
            new String[] { "main", "main60" },
            new String[] { "INIZH.big" }, NONE, 0),
        new Game(SetupActivity.GAME_GENERALS, R.string.setup_game_generals, "Generals",
            new String[] { "generals", "generals60" },
            new String[] { "INI.big" }, new String[] { "INIZH.big" }, 0),

        // Recognised, engine planned. EA published the source of these under the GPL (2020 and 2025).
        new Game("renegade", 0, "C&C Renegade", NONE,
            new String[] { "Data/always.dat" }, NONE, R.string.setup_engine_note_renegade),
        new Game("redalert", 0, "C&C Red Alert", NONE,
            new String[] { "REDALERT.MIX" }, NONE, R.string.setup_engine_note_conquer),
        new Game("tiberiandawn", 0, "C&C Tiberian Dawn", NONE,
            new String[] { "CONQUER.MIX" }, NONE, R.string.setup_engine_note_conquer),

        // Recognised only: no source has been released, so there is no engine to port.
        new Game("yuri", 0, "C&C Yuri's Revenge", NONE,
            new String[] { "RA2MD.MIX" }, NONE, R.string.setup_engine_note_no_source),
        new Game("redalert2", 0, "C&C Red Alert 2", NONE,
            new String[] { "RA2.MIX" }, NONE, R.string.setup_engine_note_no_source),
        new Game("tiberiansun", 0, "C&C Tiberian Sun", NONE,
            new String[] { "TIBSUN.MIX" }, NONE, R.string.setup_engine_note_no_source)
    ));

    private GameCatalog() {
    }

    static List<Game> all() {
        return GAMES;
    }

    /** The game with this id, or Zero Hour (the launcher's default) for an unknown one. */
    static Game byId(String id) {
        for (Game g : GAMES) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return GAMES.get(0);
    }

    static boolean isRunnableId(String id) {
        for (Game g : GAMES) {
            if (g.id.equals(id)) {
                return g.isRunnable();
            }
        }
        return false;
    }

    /** Which game this exact folder holds, or null. */
    static Game identify(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        for (Game g : GAMES) {
            if (matches(dir, g)) {
                return g;
            }
        }
        return null;
    }

    /**
     * The game in the picked folder, or -- the common mistake of picking the folder an archive was
     * unpacked into -- the only game folder up to three levels below it. Null when there is none, or
     * more than one below (then the player has to choose). Bounded, because shared storage behind
     * FUSE is slow to list.
     */
    static Found find(File dir) {
        Game here = identify(dir);
        if (here != null) {
            return new Found(here, dir);
        }
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(dir);
        depths.add(0);
        Found found = null;
        int listed = 0;
        while (!queue.isEmpty() && listed < 300) {
            File d = queue.poll();
            int depth = depths.poll();
            File[] children = d.listFiles(File::isDirectory);
            listed++;
            if (children == null) {
                continue;
            }
            for (File c : children) {
                Game g = identify(c);
                if (g != null) {
                    if (found != null) {
                        return null;
                    }
                    found = new Found(g, c);
                } else if (depth + 1 < 3 && !c.getName().startsWith(".")) {
                    queue.add(c);
                    depths.add(depth + 1);
                }
            }
        }
        return found;
    }

    /** Names of the recognised games without an engine yet, for the Home page. */
    static String plannedNames(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Game g : GAMES) {
            if (!g.isRunnable()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(g.title(ctx));
            }
        }
        return sb.toString();
    }

    private static boolean matches(File dir, Game g) {
        for (String path : g.allOf) {
            if (!existsIgnoreCase(dir, path)) {
                return false;
            }
        }
        for (String path : g.noneOf) {
            if (existsIgnoreCase(dir, path)) {
                return false;
            }
        }
        return true;
    }

    // Copies made on Windows keep Windows' case-insensitive names in any spelling (INI.big, ini.big,
    // Ini.BIG), and Android's shared storage is case-sensitive, so each path segment is matched
    // against the directory listing rather than opened by name.
    private static boolean existsIgnoreCase(File dir, String relativePath) {
        File current = dir;
        for (String segment : relativePath.split("/")) {
            String[] names = current.list();
            if (names == null) {
                return false;
            }
            File next = null;
            for (String n : names) {
                if (n.equalsIgnoreCase(segment)) {
                    next = new File(current, n);
                    break;
                }
            }
            if (next == null) {
                return false;
            }
            current = next;
        }
        return true;
    }
}
