/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
**
**  This program is distributed in the hope that it will be useful,
**  but WITHOUT ANY WARRANTY; without even the implied warranty of
**  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**  GNU General Public License for more details.
**
**  You should have received a copy of the GNU General Public License
**  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.generalsx.zerohour;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * GeneralsX @feature Android port 21/10/2026 Import player-made custom maps.
 *
 * The engine's user map root is {@link DataPackInstaller#userDataDir()} plus
 * "Maps" -- that is what MapCache::getUserMapDir() scans (getPath_UserData()
 * + "Maps" in MapUtil.cpp). Copying to a "MapsZH" folder would put the files
 * where nothing scans: MapsZH.big is an archive location, not a scan root.
 *
 * Layout matters as much as the location: hasValidMapLeafLayout() (MapUtil.cpp)
 * refuses a .map that is not inside a folder named after it, so a flat copy to
 * Maps/ would never appear in any list either. The import therefore writes
 * Maps/&lt;Name&gt;/&lt;Name&gt;.map, exactly what every ZH map folder looks like.
 *
 * A same-leaf .tga picked alongside the map is imported as the preview the UI
 * shows: getMapPreviewImage() looks for a &lt;dir&gt;/&lt;leaf&gt;.tga sidecar next
 * to the .map, and without it the map list shows the placeholder question
 * mark (see the QM-map analysis). The _art folders belong to in-game model
 * data, not previews, and are not touched.
 *
 * Doctrine matches ModImportService: user-selected content is untrusted, so
 * everything is staged outside the scan root (a transactional
 * &lt;userdata&gt;/.gx_map_importing/&lt;uuid&gt; temp dir), the destination path is
 * constructed from a sanitized leaf only (no provider path segments are ever
 * taken), and the final file becomes visible under Maps/ only when the copy
 * is complete. The engine picks new and replaced maps up by itself: the next
 * MapCache::updateCache() scan compares size/timestamp (addMap) and loads or
 * reloads what changed, so imported maps appear on the next menu visit.
 */
final class MapImporter {

    private MapImporter() {}

    /** Safety cap per selected file; a real ZH map is at most a few tens of MB. */
    private static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    /** Total cap for one import run. */
    private static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;
    /** Longest map-leaf name accepted; Windows leaf limits apply to map hosts too. */
    private static final int MAX_LEAF_LEN = 96;

    private static final int BUFFER_SIZE = 64 * 1024;

    /** Destination is never inside the scan root while staging runs. */
    private static final String STAGING_ROOT = ".gx_map_importing";

    /**
     * SAF picker request. A plain any-type pick rather than "one of these mime
     * types": .map and .tga have no standard content type, and EXTRA_MIME_TYPES
     * with octet-stream hides the previews on most providers.
     * CATEGORY_OPENABLE is what grants the temporary read the copy uses, so
     * nothing needs a persistent permission.
     */
    static Intent buildPickIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** One file accepted, refused, or merged into an existing map folder. */
    enum Kind { MAP, PREVIEW }

    static final class Result {
        int imported;
        int skipped;
        int failed;
        /** Human-readable reasons for every refused/skipped file, player-facing. */
        final List<String> notes = new ArrayList<>();
    }

    /**
     * Copies every selected document into the engine's user map tree.
     * Blocking I/O -- run it off the UI thread (see SetupActivity).
     */
    static Result importContent(Context context, List<Uri> uris) {
        Result result = new Result();
        if (uris == null || uris.isEmpty()) {
            return result;
        }

        ContentResolver resolver = context.getContentResolver();
        File userData = DataPackInstaller.userDataDir();
        File mapsRoot = new File(userData, "Maps");
        File stagingRoot = new File(userData, STAGING_ROOT);
        File stagingDir = new File(stagingRoot, UUID.randomUUID().toString());

        // Pass 1 -- plan. Classify everything first so a preview picked BEFORE
        // its map still lands beside it; the order from the picker is not ours.
        boolean sawAny = false;
        try {
            if (!stagingDir.isDirectory() && !stagingDir.mkdirs()) {
                result.failed = uris.size();
                result.notes.add("could not create staging dir");
                return result;
            }

            Map<String, StagedMap> stagedMaps = new LinkedHashMap<>();

            long totalCopied = 0;
            try {
                for (Uri uri : uris) {
                    totalCopied += importOne(resolver, uri, mapsRoot, stagingDir, stagedMaps, result);
                    sawAny = true;
                }
            } finally {
                // Pass 2 -- commit. Maps first (so folders exist), previews after.
                for (StagedMap map : stagedMaps.values()) {
                    commit(map.mapStaged, map.mapFile(mapsRoot), result);
                    for (File tga : map.tgas) {
                        if (tga != null) {
                            commit(tga, map.previewFile(mapsRoot, tga.getName()), result);
                        }
                    }
                }
            }
        } finally {
            // A crash mid-import must not leave partial maps in the scan root;
            // staged bytes live outside Maps/ and die here either way.
            deleteTree(stagingDir);
        }

        if (!sawAny && result.imported == 0 && result.failed == 0) {
            result.notes.add("nothing selected");
        }
        return result;
    }

    private static long importOne(ContentResolver resolver, Uri uri, File mapsRoot,
                                  File stagingDir, Map<String, StagedMap> stagedMaps,
                                  Result result) {
        String displayName = displayNameOf(resolver, uri);
        if (displayName == null || displayName.isEmpty()) {
            result.failed++;
            result.notes.add("unnamed file refused: " + uri.getLastPathSegment());
            return 0;
        }

        boolean isMap = endsWithIgnoreCase(displayName, ".map");
        boolean isPreview = endsWithIgnoreCase(displayName, ".tga");
        if (!isMap && !isPreview) {
            result.skipped++;
            result.notes.add("not a .map or .tga: " + displayName);
            return 0;
        }

        String leaf = sanitizeLeaf(stripExtension(displayName));
        if (leaf.isEmpty() || isReservedName(leaf)) {
            result.failed++;
            result.notes.add("unusable map name: " + displayName);
            return 0;
        }
        String suffix = isMap ? ".map" : ".tga";
        leaf = clampLeaf(leaf);

        File staged = new File(stagingDir, leaf + suffix);
        long bytes;
        try {
            bytes = copyStreamToFile(resolver.openInputStream(uri), staged, isMap);
        } catch (IOException | SecurityException e) {
            // Throw away the partial staging copy: a half-written map is worse
            // than none, even though staging sits outside the scan root. Only
            // this file dies -- other staged maps in the run stay untouched.
            // noinspection ResultOfMethodCallIgnored
            staged.delete();
            result.failed++;
            result.notes.add("copy failed: " + displayName);
            return 0;
        }

        if (bytes < 0) {
            result.failed++;
            result.notes.add("too large or unreadable: " + displayName);
            return 0;
        }

        StagedMap entry = stagedMaps.get(leaf);
        if (entry == null) {
            entry = new StagedMap();
            stagedMaps.put(leaf, entry);
        }
        if (isMap) {
            if (entry.mapStaged != null) {
                result.skipped++;
                result.notes.add("second .map named '" + leaf + "' ignored");
            } else {
                entry.mapStaged = staged;
            }
        } else {
            entry.tgas.add(staged);
        }
        return bytes;
    }

    /** Moves a staged file into Maps/ (replace only when the bytes differ). */
    private static void commit(File staged, File dest, Result result) {
        if (staged == null) {
            return;
        }
        File destDir = dest.getParentFile();
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            result.failed++;
            result.notes.add("could not create map folder: " + destDir.getName());
            return;
        }
        if (dest.isFile() && dest.length() == staged.length()) {
            // Already imported (same size) or re-picked intentionally -> skip
            // quietly instead of rewriting an identical file on every run.
            result.skipped++;
            result.notes.add("already present: " + dest.getName());
            return;
        }
        if (dest.isFile() && !dest.delete()) {
            result.failed++;
            result.notes.add("could not replace: " + dest.getName());
            return;
        }
        if (staged.renameTo(dest)) {
            result.imported++;
        } else {
            result.failed++;
            result.notes.add("could not move into place: " + dest.getName());
        }
    }

    private static final class StagedMap {
        File mapStaged;
        final List<File> tgas = new ArrayList<>();

        File mapFile(File mapsRoot) {
            String leaf = leafOf(mapStaged.getName());
            return new File(mapsRoot, leaf + File.separator + leaf + ".map");
        }

        File previewFile(File mapsRoot, String previewStagedName) {
            return new File(mapsRoot, leafOf(mapStaged.getName()) + File.separator + previewStagedName);
        }
    }

    // ---------------------------------------------------------------- utilities

    private static String displayNameOf(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getString(idx);
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Fall through to the last path segment below.
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    /**
     * The leaf becomes folder and file names on every platform the map can be
     * played on, including Windows when a PC player imports the same file, and
     * the engine builds filesystem paths from it verbatim. Windows-illegal
     * characters are therefore replaced rather than merely tolerated.
     */
    private static String sanitizeLeaf(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                c = '_';
            }
            out.append(c);
        }
        String leaf = out.toString().trim();
        while (leaf.endsWith(".") || leaf.endsWith(" ")) {
            leaf = leaf.substring(0, leaf.length() - 1);
        }
        return leaf;
    }

    private static String clampLeaf(String leaf) {
        return leaf.length() > MAX_LEAF_LEN ? leaf.substring(0, MAX_LEAF_LEN) : leaf;
    }

    private static boolean isReservedName(String leaf) {
        Locale locale = Locale.ROOT;
        String upper = leaf.toUpperCase(locale);
        switch (upper) {
            case "CON": case "PRN": case "AUX": case "NUL":
                return true;
            default:
                if (upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) {
                    return true;
                }
                return false;
        }
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        return s.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String leafOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Streams the document to the staged file. Returns the byte count, or -1
     * when the file overshoots the safety cap mid-read (checked during streaming,
     * not after: a hostile provider could advertise a small size).
     */
    private static long copyStreamToFile(InputStream in, File dest, boolean isMap)
            throws IOException {
        if (in == null) {
            throw new IOException("provider returned no stream");
        }
        long total = 0;
        try (InputStream source = in; OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = source.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FILE_BYTES) {
                    return -1;
                }
                out.write(buffer, 0, read);
            }
        } finally {
            if (total < 0 || (isMap && total == 0)) {
                // noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
        if (isMap && total == 0) {
            throw new IOException("empty map file");
        }
        return total;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
