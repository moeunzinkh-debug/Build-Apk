package com.xtr3.downloader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process progress events and a small, private completed-download index. */
public final class DownloadStore {
    public static final class State {
        public boolean active;
        public int progress;
        public int phase = 0;
        public String title = "";
        public String error;
        public long bytes;
        public long total = -1;
        public String finishedUri;
        public String finishedMime;
        public String note = "";
    }
    public static final class Record {
        public String id, name, uri, mime, note;
        public long size, time;
    }
    public interface Listener { void changed(State state); }
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile State state = new State();

    private DownloadStore() { }
    public static State state() { return state; }
    public static void publish(State next) {
        state = next;
        for (Listener listener : listeners) listener.changed(next);
    }
    public static void listen(Listener listener) { listeners.addIfAbsent(listener); }
    public static void unlisten(Listener listener) { listeners.remove(listener); }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("completed", Context.MODE_PRIVATE);
    }
    public static synchronized void add(Context context, Record record) {
        try {
            JSONArray array = new JSONArray(prefs(context).getString("items", "[]"));
            JSONObject item = new JSONObject();
            item.put("id", record.id).put("name", record.name).put("uri", record.uri)
                    .put("mime", record.mime).put("size", record.size).put("time", record.time)
                    .put("note", record.note == null ? "" : record.note);
            JSONArray updated = new JSONArray();
            updated.put(item);
            for (int i = 0; i < Math.min(array.length(), 29); i++) updated.put(array.get(i));
            prefs(context).edit().putString("items", updated.toString()).apply();
        } catch (JSONException ignored) { /* Download itself has already succeeded. */ }
    }
    public static List<Record> all(Context context) {
        List<Record> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString("items", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Record r = new Record();
                r.id = o.optString("id"); r.name = o.optString("name"); r.uri = o.optString("uri");
                r.mime = o.optString("mime"); r.size = o.optLong("size"); r.time = o.optLong("time");
                r.note = o.optString("note");
                records.add(r);
            }
        } catch (JSONException ignored) { }
        return records;
    }
    public static synchronized void remove(Context context, String id) {
        try {
            JSONArray array = new JSONArray(prefs(context).getString("items", "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                if (!id.equals(o.optString("id"))) updated.put(o);
            }
            prefs(context).edit().putString("items", updated.toString()).apply();
        } catch (JSONException ignored) { }
    }
}
