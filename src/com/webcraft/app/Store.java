package com.webcraft.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mini-app library: metadata in apps.json, each page in apps/<id>.html. */
final class Store {

    static final class App {
        String id, name, src;       // src = linked content:// uri (auto-sync), may be null
        long srcMod, added, opened, size;
        int opens, color;           // color = last theme-color the page reported (0 = unknown)
        boolean full;               // immersive fullscreen

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("name", name).put("src", src == null ? "" : src)
                 .put("srcMod", srcMod).put("added", added).put("opened", opened)
                 .put("size", size).put("opens", opens).put("color", color).put("full", full);
            } catch (Exception ignored) {}
            return o;
        }

        static App from(JSONObject o) {
            App a = new App();
            a.id = o.optString("id");
            a.name = o.optString("name", "App");
            a.src = o.optString("src", "");
            if (a.src.isEmpty()) a.src = null;
            a.srcMod = o.optLong("srcMod");
            a.added = o.optLong("added");
            a.opened = o.optLong("opened");
            a.size = o.optLong("size");
            a.opens = o.optInt("opens");
            a.color = o.optInt("color");
            a.full = o.optBoolean("full");
            return a;
        }
    }

    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final File dir, meta;
    private final List<App> apps = new ArrayList<>();

    private Store(Context c) {
        ctx = c;
        dir = new File(c.getFilesDir(), "apps");
        dir.mkdirs();
        meta = new File(c.getFilesDir(), "apps.json");
        try {
            JSONArray arr = new JSONArray(new String(readAll(new java.io.FileInputStream(meta)), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) apps.add(App.from(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
    }

    synchronized List<App> list() {
        List<App> out = new ArrayList<>(apps);
        Collections.sort(out, (a, b) -> Long.compare(Math.max(b.opened, b.added), Math.max(a.opened, a.added)));
        return out;
    }

    synchronized App find(String id) {
        if (id == null) return null;
        for (App a : apps) if (a.id.equals(id)) return a;
        return null;
    }

    synchronized void save() {
        JSONArray arr = new JSONArray();
        for (App a : apps) arr.put(a.toJson());
        File tmp = new File(meta.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            fo.getFD().sync();
        } catch (Exception e) { return; }
        tmp.renameTo(meta);   // atomic replace: never a half-written library
    }

    File file(App a) { return new File(dir, a.id + ".html"); }

    String read(App a) {
        try { return new String(readAll(new java.io.FileInputStream(file(a))), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    synchronized void write(App a, String html) {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        File f = file(a), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(b); }
        catch (Exception e) { return; }
        tmp.renameTo(f);
        a.size = b.length;
        save();
    }

    synchronized App add(String name, String html, String src, long srcMod) {
        App a = new App();
        a.id = newId();
        a.name = name;
        a.src = src;
        a.srcMod = srcMod;
        a.added = System.currentTimeMillis();
        apps.add(a);
        write(a, html);
        return a;
    }

    synchronized void remove(App a) {
        apps.remove(a);
        file(a).delete();
        save();
    }

    /** Import a picked/shared file. If the same source is already in the library, refresh it instead. */
    App importUri(Uri uri) throws Exception {
        String src = null;
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            src = uri.toString();
        } catch (Exception ignored) {}   // not persistable (e.g. a one-off share): import a snapshot only
        String html = new String(readAll(ctx.getContentResolver().openInputStream(uri)), StandardCharsets.UTF_8);
        long mod = lastModified(uri);
        if (src != null) {
            synchronized (this) {
                for (App a : apps) if (src.equals(a.src)) { a.srcMod = mod; write(a, html); return a; }
            }
        }
        return add(prettyName(displayName(uri), html), html, src, mod);
    }

    /**
     * Keep a linked app in step with its source file (e.g. Download/after-salah-zikr.html).
     * Called from the WebView's IO thread right before the page is served.
     */
    void syncFromSource(App a) {
        if (a.src == null) return;
        Uri uri = Uri.parse(a.src);
        long mod = lastModified(uri);
        if (mod <= 0 || mod == a.srcMod) return;
        try {
            String html = new String(readAll(ctx.getContentResolver().openInputStream(uri)), StandardCharsets.UTF_8);
            if (html.trim().isEmpty()) return;
            a.srcMod = mod;
            write(a, html);
        } catch (Exception ignored) {}   // source moved/deleted: keep the saved copy
    }

    long lastModified(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).lastModified();
        return 0;
    }

    private String displayName(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s == null ? "App" : s;
    }

    /** Prefer the page's <title>, else a tidied file name. */
    static String prettyName(String file, String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<title[^>]*>([^<]{1,80})</title>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String t = android.text.Html.fromHtml(m.group(1).trim(), 0).toString().trim();
            if (!t.isEmpty()) return t;
        }
        String n = file.replaceAll("(?i)\\.html?$", "").replaceAll("[_-]+", " ").trim();
        return n.isEmpty() ? "App" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String newId() {
        // Lowercase letters/digits only: the id doubles as a hostname (per-app storage origin)
        byte[] b = new byte[5];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder("a");
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
