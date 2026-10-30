package com.dalcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.format.DateUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Past counts, kept only on this phone (app files/history): index.json plus <id>.jpg and <id>_t.jpg. */
final class History {

    static final class Entry {
        final String id;
        long date;
        int count, toCheck, ignored;

        Entry(String id, long date, int count, int toCheck, int ignored) {
            this.id = id;
            this.date = date;
            this.count = count;
            this.toCheck = toCheck;
            this.ignored = ignored;
        }

        String title() {
            return count == 1 ? "1 grain" : count + " grains";
        }

        String subtitle(Context c) {
            String when = DateUtils.getRelativeDateTimeString(c, date, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS, 0).toString();
            return when + (toCheck > 0 ? " · " + toCheck + " not checked" : " · checked");
        }
    }

    private static History instance;

    static History get(Context c) {
        if (instance == null) instance = new History(c.getApplicationContext());
        return instance;
    }

    /** Ids are UUIDs made by this app; anything else in the index is ignored and never used as a file name. */
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{8,64}");

    private final File dir;
    private final List<Entry> entries = new ArrayList<Entry>();
    /** Thumbnails kept in memory, at most ~8 MB. */
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** One worker for every file write and delete, so they always happen in the order they were asked for. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private History(Context c) {
        dir = new File(c.getFilesDir(), "history");
        dir.mkdirs();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(index().toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!validId(o.optString("id"))) continue;
                entries.add(new Entry(o.getString("id"), o.getLong("date"), o.getInt("count"), o.optInt("toCheck"),
                        o.optInt("ignored")));
            }
        } catch (Exception ignored) {
            // first run or unreadable index: start empty
        }
    }

    List<Entry> entries() {
        return entries;
    }

    File image(String id) {
        return new File(dir, id + ".jpg");
    }

    private File thumbFile(String id) {
        return new File(dir, id + "_t.jpg");
    }

    private File index() {
        return new File(dir, "index.json");
    }

    /** Adds or replaces an entry and its picture (files are written on a background thread). */
    void save(Entry e, final Bitmap picture) {
        if (!validId(e.id)) return;
        int at = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(e.id)) at = i;
        if (at >= 0) entries.set(at, e);
        else entries.add(0, e);
        float k = 240f / Math.max(1, Math.min(picture.getWidth(), picture.getHeight()));
        final Bitmap thumb = Bitmap.createScaledBitmap(picture, Math.max(1, Math.round(picture.getWidth() * k)),
                Math.max(1, Math.round(picture.getHeight() * k)), true);
        thumbs.put(e.id, thumb);
        final String id = e.id;
        final String json = toJson();
        io.execute(new Runnable() {
            @Override
            public void run() {
                write(image(id), picture, 90);
                write(thumbFile(id), thumb, 80);
                writeText(index(), json);
            }
        });
    }

    void delete(final String id) {
        if (!validId(id)) return;
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).id.equals(id)) entries.remove(i);
        thumbs.remove(id);
        final String json = toJson();
        io.execute(new Runnable() { // after any pending save of the same entry, never before it
            @Override
            public void run() {
                image(id).delete();
                thumbFile(id).delete();
                writeText(index(), json);
            }
        });
    }

    Bitmap thumbnail(String id) {
        Bitmap b = thumbs.get(id);
        if (b == null) {
            b = BitmapFactory.decodeFile(thumbFile(id).getPath());
            if (b != null) thumbs.put(id, b);
        }
        return b;
    }

    private String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("date", e.date);
                o.put("count", e.count);
                o.put("toCheck", e.toCheck);
                o.put("ignored", e.ignored);
                a.put(o);
            }
        } catch (Exception ignored) {
            // JSONObject.put only throws for NaN numbers, which never occur here
        }
        return a.toString();
    }

    private static void write(File f, Bitmap b, int quality) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, quality, os);
        } catch (Exception ignored) {
            return; // a missing picture only shows as an empty thumbnail
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** Written to a temp file and renamed, so a crash mid-write never leaves a half index behind. */
    private static void writeText(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return; // keep the in-memory list; it is written again on the next save
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
