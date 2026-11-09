/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * GeneralsX @feature Android port 09/11/2026 On-screen button layouts.
 *
 * The engine has drawn and handled an overlay of key buttons since 21/10/2026 (gxvirtualkeys in
 * SDL3GameEngine.cpp): each button presses a keyboard key through the same SDL event queue a
 * physical keyboard uses. It is configured by gx_virtual_keys.ini in the game folder, and nothing
 * wrote that file -- the overlay could only be reached by composing the file by hand. This is the
 * launcher's half: named layouts the player edits on screen (ControlsEditorActivity), kept in the
 * launcher's preferences, the selected one written to the folder of the game about to start.
 *
 * The file's grammar is the engine's, including its limits: at most {@link #MAX_BUTTONS} buttons,
 * one key=value per line, values without spaces, labels in ASCII (the overlay draws them with a
 * Latin font). Positions are the button's top-left corner as a fraction of the display; a button
 * is a square of 8.5% of the display's height times the layout's scale times its own size.
 */
final class ControlsLayouts {
    /** gxvirtualkeys::MAX_BUTTONS. */
    static final int MAX_BUTTONS = 16;
    /** A button's side as a fraction of the display height at scale 1, size 1 (layoutRects()). */
    static final float UNIT_OF_HEIGHT = 0.085f;

    static final int OPACITY_MIN = 10;
    static final int OPACITY_MAX = 80;
    static final float SCALE_MIN = 0.6f;
    static final float SCALE_MAX = 2.0f;
    static final float SIZE_MIN = 0.5f;
    static final float SIZE_MAX = 2.5f;
    static final int LABEL_MAX = 8;

    private static final String INI_NAME = "gx_virtual_keys.ini";
    // First line of a file this class wrote. A file without it is somebody's own and is set
    // aside, never overwritten (writeForGame()).
    private static final String OWNER_LINE = "# Written by the launcher (Interface > On-screen buttons); edits are overwritten.";
    private static final String PREF_LAYOUTS = "controls_layouts";
    private static final String PREF_ACTIVE = "controls_layout_active";
    private static final String PREF_ENABLED = "controls_enabled";

    // What the engine's key= understands and a single word can say: SDL's scancode names, plus
    // the engine's own names for the modifiers (scancodeFromNameOrFallback()). Each with the label
    // a button gets when the player gives it none.
    private static final String[][] KEY_TABLE = buildKeyTable();

    private static String[][] buildKeyTable() {
        List<String[]> keys = new ArrayList<>();
        for (char c = '1'; c <= '9'; c++) {
            keys.add(new String[] { String.valueOf(c), String.valueOf(c) });
        }
        keys.add(new String[] { "0", "0" });
        for (char c = 'A'; c <= 'Z'; c++) {
            keys.add(new String[] { String.valueOf(c), String.valueOf(c) });
        }
        // A modifier as a button is held with one finger while another presses a key.
        keys.add(new String[] { "LCTRL", "Ctrl" });
        keys.add(new String[] { "LSHIFT", "Shift" });
        keys.add(new String[] { "LALT", "Alt" });
        keys.add(new String[] { "Space", "Space" });
        keys.add(new String[] { "Return", "Enter" });
        keys.add(new String[] { "Escape", "Esc" });
        keys.add(new String[] { "Tab", "Tab" });
        keys.add(new String[] { "Backspace", "Bksp" });
        keys.add(new String[] { "Delete", "Del" });
        keys.add(new String[] { "Insert", "Ins" });
        keys.add(new String[] { "Home", "Home" });
        keys.add(new String[] { "End", "End" });
        keys.add(new String[] { "PageUp", "PgUp" });
        keys.add(new String[] { "PageDown", "PgDn" });
        keys.add(new String[] { "Up", "Up" });
        keys.add(new String[] { "Down", "Down" });
        keys.add(new String[] { "Left", "Left" });
        keys.add(new String[] { "Right", "Right" });
        for (int i = 1; i <= 12; i++) {
            keys.add(new String[] { "F" + i, "F" + i });
        }
        return keys.toArray(new String[0][]);
    }

    static final class Button {
        String key = "Q";
        String label = "Q";
        boolean shift;
        boolean ctrl;
        boolean alt;
        float x;
        float y;
        float size = 1f;
    }

    static final class Layout {
        final String id;
        String name = "";
        int opacity = 40;
        float scale = 1f;
        boolean autoHide = true;
        final List<Button> buttons = new ArrayList<>();

        Layout(String id) {
            this.id = id;
        }
    }

    static final class State {
        final List<Layout> layouts = new ArrayList<>();
        Layout active;
    }

    private ControlsLayouts() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ keys

    static int keyCount() {
        return KEY_TABLE.length;
    }

    static String keyAt(int index) {
        return KEY_TABLE[index][0];
    }

    /** The label a button for this key carries by default: "Q", "Ctrl", "Enter". */
    static String defaultLabel(String key) {
        return KEY_TABLE[keyIndex(key)][1];
    }

    /** The key's place in the table; an unknown name counts as the first key. */
    static int keyIndex(String key) {
        for (int i = 0; i < KEY_TABLE.length; i++) {
            if (KEY_TABLE[i][0].equalsIgnoreCase(key)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * A label the engine can read and draw: printable ASCII with no space (a value ends at the
     * first one), no '=' or '#', at most {@link #LABEL_MAX} characters. Empty in, default out.
     */
    static String cleanLabel(String label, String key) {
        StringBuilder sb = new StringBuilder();
        if (label != null) {
            for (int i = 0; i < label.length() && sb.length() < LABEL_MAX; i++) {
                char c = label.charAt(i);
                if (c > ' ' && c < 0x7F && c != '=' && c != '#' && c != '[' && c != ';') {
                    sb.append(c);
                }
            }
        }
        return sb.length() == 0 ? defaultLabel(key) : sb.toString();
    }

    // --------------------------------------------------------------- storage

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    /** Switching the buttons on makes the starting layout if the player has none yet. */
    static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            load(ctx);
        }
    }

    /** Whether the launcher has layouts at all, i.e. the player has used this feature. */
    static boolean hasLayouts(Context ctx) {
        return !prefs(ctx).getString(PREF_LAYOUTS, "").isEmpty();
    }

    /** The stored layouts; the first use makes the starting one. */
    static State load(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        State state = new State();
        parse(prefs.getString(PREF_LAYOUTS, ""), state.layouts);
        String activeId = prefs.getString(PREF_ACTIVE, "");
        for (Layout layout : state.layouts) {
            if (layout.id.equals(activeId)) {
                state.active = layout;
            }
        }
        if (state.layouts.isEmpty()) {
            state.layouts.add(startingLayout(ctx.getString(R.string.profiles_default_name)));
        }
        if (state.active == null) {
            state.active = state.layouts.get(0);
        }
        save(ctx, state);
        return state;
    }

    static void save(Context ctx, State state) {
        JSONArray array = new JSONArray();
        try {
            for (Layout layout : state.layouts) {
                JSONObject o = new JSONObject();
                o.put("id", layout.id);
                o.put("name", layout.name);
                o.put("opacity", layout.opacity);
                o.put("scale", layout.scale);
                o.put("auto_hide", layout.autoHide);
                JSONArray buttons = new JSONArray();
                for (Button b : layout.buttons) {
                    JSONObject jb = new JSONObject();
                    jb.put("key", b.key);
                    jb.put("label", b.label);
                    jb.put("shift", b.shift);
                    jb.put("ctrl", b.ctrl);
                    jb.put("alt", b.alt);
                    jb.put("x", b.x);
                    jb.put("y", b.y);
                    jb.put("size", b.size);
                    buttons.put(jb);
                }
                o.put("buttons", buttons);
                array.put(o);
            }
        } catch (JSONException e) {
            android.util.Log.w("ControlsLayouts", "could not store the layouts", e);
            return;
        }
        prefs(ctx).edit().putString(PREF_LAYOUTS, array.toString())
            .putString(PREF_ACTIVE, state.active.id).apply();
    }

    private static void parse(String stored, List<Layout> into) {
        if (stored == null || stored.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                String id = o == null ? "" : o.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                Layout layout = new Layout(id);
                layout.name = o.optString("name", "");
                layout.opacity = clamp(o.optInt("opacity", 40), OPACITY_MIN, OPACITY_MAX);
                layout.scale = clamp((float) o.optDouble("scale", 1.0), SCALE_MIN, SCALE_MAX);
                layout.autoHide = o.optBoolean("auto_hide", true);
                JSONArray buttons = o.optJSONArray("buttons");
                for (int j = 0; buttons != null && j < buttons.length() && j < MAX_BUTTONS; j++) {
                    JSONObject jb = buttons.optJSONObject(j);
                    if (jb == null) {
                        continue;
                    }
                    Button b = new Button();
                    b.key = keyAt(keyIndex(jb.optString("key", "")));
                    b.label = cleanLabel(jb.optString("label", ""), b.key);
                    b.shift = jb.optBoolean("shift", false);
                    b.ctrl = jb.optBoolean("ctrl", false);
                    b.alt = jb.optBoolean("alt", false);
                    b.x = clamp((float) jb.optDouble("x", 0.0), 0f, 1f);
                    b.y = clamp((float) jb.optDouble("y", 0.0), 0f, 1f);
                    b.size = clamp((float) jb.optDouble("size", 1.0), SIZE_MIN, SIZE_MAX);
                    layout.buttons.add(b);
                }
                into.add(layout);
            }
        } catch (JSONException e) {
            // Unreadable: load() starts again from the starting layout.
            android.util.Log.w("ControlsLayouts", "could not read the stored layouts", e);
            into.clear();
        }
    }

    /** A new layout, selected, that starts as a copy of the one selected until now. */
    static Layout create(Context ctx, State state, String name) {
        Layout created = new Layout(UUID.randomUUID().toString());
        created.name = name;
        created.opacity = state.active.opacity;
        created.scale = state.active.scale;
        created.autoHide = state.active.autoHide;
        for (Button b : state.active.buttons) {
            Button copy = new Button();
            copy.key = b.key;
            copy.label = b.label;
            copy.shift = b.shift;
            copy.ctrl = b.ctrl;
            copy.alt = b.alt;
            copy.x = b.x;
            copy.y = b.y;
            copy.size = b.size;
            created.buttons.add(copy);
        }
        state.layouts.add(created);
        state.active = created;
        save(ctx, state);
        return created;
    }

    /** Removes the selected layout and selects its neighbour; the last one stays. */
    static boolean deleteActive(Context ctx, State state) {
        if (state.layouts.size() < 2) {
            return false;
        }
        int index = state.layouts.indexOf(state.active);
        state.layouts.remove(index);
        state.active = state.layouts.get(Math.min(index, state.layouts.size() - 1));
        save(ctx, state);
        return true;
    }

    /** "Layout 2", "Layout 3", ...: the first such name no layout carries yet. */
    static String suggestName(Context ctx, State state) {
        for (int n = state.layouts.size() + 1; ; n++) {
            String candidate = ctx.getString(R.string.controls_numbered, n);
            boolean taken = false;
            for (Layout layout : state.layouts) {
                if (layout.name.equalsIgnoreCase(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    // The control groups down the left edge with Ctrl under them (Ctrl held + a number makes a
    // group), four command keys and Shift down the right edge. Both columns end above the
    // control bar; every one of them can be moved, changed or removed in the editor.
    private static Layout startingLayout(String name) {
        Layout layout = new Layout(UUID.randomUUID().toString());
        layout.name = name;
        String[] left = { "1", "2", "3", "4", "5", "LCTRL" };
        String[] right = { "Q", "W", "E", "R", "LSHIFT" };
        for (int i = 0; i < left.length; i++) {
            layout.buttons.add(startingButton(left[i], 0.03f, 0.08f + i * 0.105f));
        }
        for (int i = 0; i < right.length; i++) {
            layout.buttons.add(startingButton(right[i], 0.93f, 0.08f + i * 0.105f));
        }
        return layout;
    }

    private static Button startingButton(String key, float x, float y) {
        Button b = new Button();
        b.key = key;
        b.label = defaultLabel(key);
        b.x = x;
        b.y = y;
        return b;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------- the engine's file

    /**
     * Right before a game starts: the selected layout as gx_virtual_keys.ini in that game's
     * folder (the engine's working directory), or the same file saying "off".
     *
     * Nothing is written for a player who never used this feature. A file in the folder that
     * this class did not write -- composed by hand while that was the only way -- is renamed
     * aside first, not overwritten.
     */
    static void writeForGame(Context ctx, File gameDir) {
        if (gameDir == null || !gameDir.isDirectory() || !hasLayouts(ctx)) {
            return;
        }
        File ini = new File(gameDir, INI_NAME);
        if (ini.isFile() && !OWNER_LINE.equals(firstLine(ini)) && !setAside(ini)) {
            android.util.Log.w("ControlsLayouts", "left alone: " + ini + " is not the launcher's and could not be set aside");
            return;
        }
        Layout layout = load(ctx).active;
        boolean enabled = isEnabled(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(OWNER_LINE).append('\n');
        sb.append("[virtualkeys]\n");
        sb.append("enabled=").append(enabled ? 1 : 0).append('\n');
        sb.append("opacity=").append(layout.opacity).append('\n');
        sb.append("scale=").append(number(layout.scale)).append('\n');
        sb.append("AutoHideOnPhysicalInput=").append(layout.autoHide ? 1 : 0).append('\n');
        int n = 0;
        for (Button b : layout.buttons) {
            if (!enabled || n >= MAX_BUTTONS) {
                break;
            }
            n++;
            sb.append("\n[button").append(n).append("]\n");
            sb.append("label=").append(cleanLabel(b.label, b.key)).append('\n');
            sb.append("key=").append(b.key).append('\n');
            String mods = (b.shift ? ",lshift" : "") + (b.ctrl ? ",lctrl" : "") + (b.alt ? ",lalt" : "");
            if (!mods.isEmpty()) {
                sb.append("mods=").append(mods.substring(1)).append('\n');
            }
            sb.append("x=").append(number(b.x)).append('\n');
            sb.append("y=").append(number(b.y)).append('\n');
            sb.append("size=").append(number(b.size)).append('\n');
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(ini, false), StandardCharsets.US_ASCII)) {
            w.write(sb.toString());
        } catch (IOException e) {
            android.util.Log.w("ControlsLayouts", "could not write " + ini, e);
        }
    }

    // The engine reads these with atof(): a decimal point whatever the phone's language is.
    private static String number(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String firstLine(File file) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            android.util.Log.w("ControlsLayouts", "could not read " + file, e);
            return "";
        }
    }

    /** Renames a file that is not ours to the first free "<name>.bak", ".bak2", ... */
    private static boolean setAside(File ini) {
        for (int i = 1; i <= 9; i++) {
            File target = new File(ini.getParentFile(), ini.getName() + (i == 1 ? ".bak" : ".bak" + i));
            if (!target.exists()) {
                return ini.renameTo(target);
            }
        }
        return false;
    }
}
