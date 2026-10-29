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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    private final File dir;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final HashMap<String, Bitmap> thumbs = new HashMap<String, Bitmap>();

    private History(Context c) {
        dir = new File(c.getFilesDir(), "history");
        dir.mkdirs();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(index().toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                write(image(id), picture, 90);
                write(thumbFile(id), thumb, 80);
                writeText(index(), json);
            }
        }).start();
    }

    void delete(String id) {
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).id.equals(id)) entries.remove(i);
        thumbs.remove(id);
        image(id).delete();
        thumbFile(id).delete();
        writeText(index(), toJson());
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
        try (OutputStream os = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, quality, os);
        } catch (Exception ignored) {
            // a missing picture only shows as an empty thumbnail
        }
    }

    private static void writeText(File f, String s) {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // keep the in-memory list; it is written again on the next save
        }
    }
}
