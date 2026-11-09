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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GeneralsX @feature Android port 09/11/2026 Launch profiles.
 *
 * A profile is a named set of the choices that decide what the next start runs: the game, that
 * game's mod list (order and switches), the simulation rate (which engine library is loaded), the
 * renderer and the intro switch. Home shows the selected profile above Play and lets the player
 * keep several -- vanilla Zero Hour, Zero Hour with a mod, Generals at 60 Hz -- and move between
 * them in one tap instead of re-making the same four changes by hand.
 *
 * The launcher's settings stay where they always were (preferences and marker files the engine
 * reads), and they ARE the selected profile: nothing that reads a setting had to learn about
 * profiles. So the stored copy of the selected profile is refreshed from those settings every time
 * the list is loaded, before anything else is done with it, and picking another profile writes
 * that profile's values into the same settings. A setter anywhere in the launcher (the Mods
 * screen, the Graphics page, GeneralsOnline moving the rate to 60 Hz) therefore changes the
 * selected profile without calling into this class.
 *
 * Shared by every profile, on purpose: the game folders, the interface and language settings, and
 * what lives in each game's Options.ini.
 */
final class LaunchProfiles {
    private static final String PREF_PROFILES = "launch_profiles";
    private static final String PREF_ACTIVE = "launch_profile_active";
    private static final int MAX_NAME_LENGTH = 40;

    static final class Profile {
        final String id;
        String name = "";
        String game = SetupActivity.GAME_ZERO_HOUR;
        /** ModManager's stored list for {@link #game}: "1|folder" / "0|folder" lines, top first. */
        String modsOrder = "";
        int simHz = SetupActivity.SIM_HZ_RETAIL;
        String renderBackend = SetupActivity.RENDER_BACKEND_GLES;
        boolean skipIntro;

        Profile(String id) {
            this.id = id;
        }
    }

    /** The profiles in the player's order, and the one the launcher's settings belong to. */
    static final class State {
        final List<Profile> profiles = new ArrayList<>();
        Profile active;
    }

    private LaunchProfiles() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The stored profiles, the selected one carrying the launcher's settings as they are now.
     * An install that has none yet (every install before this feature) gets one made of its
     * current settings, so nothing changes for it until the player adds a second.
     */
    static State load(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        State state = new State();
        parse(prefs.getString(PREF_PROFILES, ""), state.profiles);
        state.active = find(state, prefs.getString(PREF_ACTIVE, ""));
        if (state.active == null) {
            // Also the way out of a list that names no selected profile: the settings on screen
            // become a profile of their own rather than overwriting one of the stored ones.
            state.active = new Profile(UUID.randomUUID().toString());
            state.active.name = ctx.getString(R.string.profiles_default_name);
            state.profiles.add(0, state.active);
        }
        readLauncher(ctx, state.active);
        save(ctx, state);
        return state;
    }

    /** Makes the given profile the selected one. False when it already is, or is gone. */
    static boolean select(Context ctx, String id) {
        State state = load(ctx);
        Profile target = find(state, id);
        if (target == null || target == state.active) {
            return false;
        }
        writeLauncher(ctx, target);
        state.active = target;
        save(ctx, state);
        return true;
    }

    /** A new profile, selected, that starts as a copy of the one selected until now. */
    static Profile create(Context ctx, String name) {
        State state = load(ctx);
        Profile created = new Profile(UUID.randomUUID().toString());
        created.name = cleanName(name, suggestName(ctx, state));
        created.game = state.active.game;
        created.modsOrder = state.active.modsOrder;
        created.simHz = state.active.simHz;
        created.renderBackend = state.active.renderBackend;
        created.skipIntro = state.active.skipIntro;
        state.profiles.add(created);
        // The launcher's settings already are this profile's: there is nothing to write.
        state.active = created;
        save(ctx, state);
        return created;
    }

    static void rename(Context ctx, String id, String name) {
        State state = load(ctx);
        Profile target = find(state, id);
        if (target == null) {
            return;
        }
        target.name = cleanName(name, target.name);
        save(ctx, state);
    }

    /**
     * Removes a profile; the last one stays. Deleting the selected one selects its neighbour and
     * puts that profile's settings in place. Only the profile goes: no game, mod or save is touched.
     */
    static boolean delete(Context ctx, String id) {
        State state = load(ctx);
        Profile target = find(state, id);
        if (target == null || state.profiles.size() < 2) {
            return false;
        }
        int index = state.profiles.indexOf(target);
        state.profiles.remove(index);
        if (target == state.active) {
            state.active = state.profiles.get(Math.min(index, state.profiles.size() - 1));
            writeLauncher(ctx, state.active);
        }
        save(ctx, state);
        return true;
    }

    /** "Profile 2", "Profile 3", ...: the first such name no profile carries yet. */
    static String suggestName(Context ctx) {
        return suggestName(ctx, load(ctx));
    }

    private static String suggestName(Context ctx, State state) {
        for (int n = state.profiles.size() + 1; ; n++) {
            String candidate = ctx.getString(R.string.profiles_numbered, n);
            boolean taken = false;
            for (Profile p : state.profiles) {
                if (p.name.equalsIgnoreCase(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    private static String cleanName(String name, String fallback) {
        String cleaned = name == null ? "" : name.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH).trim() : cleaned;
    }

    private static Profile find(State state, String id) {
        for (Profile p : state.profiles) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    private static void readLauncher(Context ctx, Profile into) {
        into.game = SetupActivity.getSelectedGame(ctx);
        into.modsOrder = ModManager.storedOrder(ctx, into.game);
        into.simHz = SetupActivity.getSimHz(ctx);
        into.renderBackend = SetupActivity.getRenderBackendChoice(ctx);
        into.skipIntro = SetupActivity.isSkipIntroEnabled(ctx);
    }

    private static void writeLauncher(Context ctx, Profile from) {
        SetupActivity.setSelectedGame(ctx, from.game);
        ModManager.storeOrder(ctx, from.game, from.modsOrder);
        SetupActivity.setSimHz(ctx, from.simHz);
        prefs(ctx).edit().putBoolean(SetupActivity.PREF_SKIP_INTRO, from.skipIntro).apply();
        // A profile saved while the experimental Vulkan renderer was offered must not bring it
        // back after that switch was turned off (SetupActivity.RENDER_BACKEND_CHOICES_NO_VULKAN).
        String backend = from.renderBackend;
        if (SetupActivity.RENDER_BACKEND_VULKAN.equals(backend) && !SetupActivity.isVulkanUnlocked(ctx)) {
            backend = SetupActivity.RENDER_BACKEND_GLES;
        }
        if (!backend.equals(SetupActivity.getRenderBackendChoice(ctx))) {
            String failure = SetupActivity.storeRenderBackend(ctx, backend);
            if (failure != null) {
                android.util.Log.w("LaunchProfiles", "could not write the renderer of a profile: " + failure);
            }
        }
    }

    private static void parse(String stored, List<Profile> into) {
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
                Profile p = new Profile(id);
                p.name = cleanName(o.optString("name", ""), id);
                p.game = SetupActivity.GAME_GENERALS.equals(o.optString("game", ""))
                    ? SetupActivity.GAME_GENERALS : SetupActivity.GAME_ZERO_HOUR;
                p.modsOrder = o.optString("mods", "");
                p.simHz = o.optInt("sim_hz", SetupActivity.SIM_HZ_RETAIL) == SetupActivity.SIM_HZ_CROSSPLAY
                    ? SetupActivity.SIM_HZ_CROSSPLAY : SetupActivity.SIM_HZ_RETAIL;
                String backend = o.optString("backend", "");
                p.renderBackend = SetupActivity.RENDER_BACKEND_VULKAN.equals(backend)
                        || SetupActivity.RENDER_BACKEND_GLES_ANGLE.equals(backend)
                    ? backend : SetupActivity.RENDER_BACKEND_GLES;
                p.skipIntro = o.optBoolean("skip_intro", false);
                into.add(p);
            }
        } catch (JSONException e) {
            // Unreadable: load() starts over from the launcher's current settings, which are intact.
            android.util.Log.w("LaunchProfiles", "could not read the stored profiles", e);
            into.clear();
        }
    }

    private static void save(Context ctx, State state) {
        JSONArray array = new JSONArray();
        try {
            for (Profile p : state.profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("game", p.game);
                o.put("mods", p.modsOrder);
                o.put("sim_hz", p.simHz);
                o.put("backend", p.renderBackend);
                o.put("skip_intro", p.skipIntro);
                array.put(o);
            }
        } catch (JSONException e) {
            android.util.Log.w("LaunchProfiles", "could not store the profiles", e);
            return;
        }
        String serialized = array.toString();
        SharedPreferences prefs = prefs(ctx);
        // load() runs every time Home is drawn; most of those change nothing.
        if (serialized.equals(prefs.getString(PREF_PROFILES, ""))
                && state.active.id.equals(prefs.getString(PREF_ACTIVE, ""))) {
            return;
        }
        prefs.edit().putString(PREF_PROFILES, serialized).putString(PREF_ACTIVE, state.active.id).apply();
    }
}
