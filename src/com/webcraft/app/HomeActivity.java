package com.webcraft.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The library. Its UI is assets/home.html, driven through window.WC. */
public class HomeActivity extends Activity {
    private static final int RC_IMPORT = 10, RC_RESTORE = 11;

    private WebView web;
    private String restoreId;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? Color.rgb(15, 17, 21) : Color.rgb(246, 244, 239);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setBackgroundColor(bg);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        Web.setup(web);
        web.addJavascriptInterface(new WC(), "WC");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return Web.intercept(HomeActivity.this, r);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !Web.isLocal(r.getUrl());
            }
        });
        setContentView(web);
        web.loadUrl(Web.HOME);
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIncoming(i);
    }

    @Override protected void onResume() { super.onResume(); web.onResume(); emit("changed", null); }
    @Override protected void onPause()  { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }

    @Override
    public void onBackPressed() {
        // Let the page close an open sheet/editor first
        web.evaluateJavascript("window.wcBack ? wcBack() : false", v -> {
            if (!"true".equals(v)) HomeActivity.super.onBackPressed();
        });
    }

    /** "Open with WebCraft" / share of an .html file: import it and run it. */
    private void handleIncoming(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        final Uri uri = u;
        i.setAction(Intent.ACTION_MAIN);    // don't re-import on rotation / recreate
        new Thread(() -> {
            try {
                Store.App a = Store.get(this).importUri(uri);
                runOnUiThread(() -> startActivity(AppActivity.intentFor(this, a.id)));
            } catch (Exception e) {
                toast("Could not open file: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        if (result != RESULT_OK || data == null) return;
        if (rc == RC_IMPORT) {
            final java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            new Thread(() -> {
                int ok = 0;
                String last = null;
                for (Uri u : uris) {
                    try { last = Store.get(this).importUri(u).id; ok++; }
                    catch (Exception e) { toast("Skipped a file: " + e.getMessage()); }
                }
                toast(ok == 1 ? "Imported" : "Imported " + ok + " apps");
                emit("changed", last);
            }).start();
        } else if (rc == RC_RESTORE && restoreId != null) {
            final Store.App a = Store.get(this).find(restoreId);
            restoreId = null;
            if (a == null) return;
            try {
                String json = new String(Store.readAll(getContentResolver().openInputStream(data.getData())), StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(json);          // validate before touching anything
                if (!o.has("localStorage")) throw new Exception("Not a WebCraft backup");
                runHidden(a, "__wc/restore", json, res -> {
                    if (res.startsWith("error")) toast("Restore failed: " + res);
                    else toast("Restored " + res + " saved items into " + a.name);
                });
            } catch (Exception e) {
                toast("Restore failed: " + e.getMessage());
            }
        }
    }

    interface Done { void on(String result); }

    /** Load a helper page on the app's own origin (the only place its storage is reachable). */
    private void runHidden(Store.App a, String path, final String payload, final Done done) {
        final WebView w = new WebView(this);
        Web.setup(w);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return Web.intercept(HomeActivity.this, r);
            }
        });
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface public String data() { return payload == null ? "{}" : payload; }
            @JavascriptInterface public void done(String r) {
                runOnUiThread(() -> { done.on(r); w.destroy(); });
            }
        }, "WCB");
        w.loadUrl(Web.urlFor(a.id) + path);
    }

    private void emit(String event, String data) {
        final String js = "window.wcEvent && wcEvent(" + JSONObject.quote(event) + "," + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void toast(final String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    // ───────────────────────── window.WC ─────────────────────────

    private class WC {
        @JavascriptInterface
        public String list() {
            JSONArray arr = new JSONArray();
            for (Store.App a : Store.get(HomeActivity.this).list()) {
                JSONObject o = a.toJson();
                try {
                    o.put("linked", a.src != null);
                    o.put("hex", a.color == 0 ? "" : String.format("#%06x", a.color & 0xFFFFFF));
                    o.remove("src");
                } catch (Exception ignored) {}
                arr.put(o);
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void importFiles() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            runOnUiThread(() -> startActivityForResult(i, RC_IMPORT));
        }

        @JavascriptInterface
        public String create(String name, String html) {
            return Store.get(HomeActivity.this).add(name.trim().isEmpty() ? "New app" : name.trim(), html, null, 0).id;
        }

        @JavascriptInterface
        public void open(String id) {
            if (Store.get(HomeActivity.this).find(id) != null)
                runOnUiThread(() -> startActivity(AppActivity.intentFor(HomeActivity.this, id)));
        }

        @JavascriptInterface
        public void rename(String id, String name) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null || name.trim().isEmpty()) return;
            a.name = name.trim();
            Store.get(HomeActivity.this).save();
            Shortcuts.refresh(HomeActivity.this);
        }

        @JavascriptInterface
        public void remove(String id) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return;
            Shortcuts.removed(HomeActivity.this, a);
            Store.get(HomeActivity.this).remove(a);
            // Also wipe its saved data (localStorage, IndexedDB…) — it has its own origin
            final String origin = Web.urlFor(id);
            runOnUiThread(() -> android.webkit.WebStorage.getInstance().deleteOrigin(origin.substring(0, origin.length() - 1)));
        }

        @JavascriptInterface
        public void unlink(String id) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null || a.src == null) return;
            try { getContentResolver().releasePersistableUriPermission(Uri.parse(a.src), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            a.src = null;
            Store.get(HomeActivity.this).save();
        }

        @JavascriptInterface
        public void setFull(String id, boolean full) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return;
            a.full = full;
            Store.get(HomeActivity.this).save();
        }

        @JavascriptInterface
        public void pin(String id) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return;
            if (!Shortcuts.pin(HomeActivity.this, a)) toast("Your launcher doesn't support home-screen shortcuts");
        }

        @JavascriptInterface
        public String source(String id) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            return a == null ? "" : Store.get(HomeActivity.this).read(a);
        }

        @JavascriptInterface
        public boolean save(String id, String html) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return false;
            Store.get(HomeActivity.this).write(a, html);
            if (a.src != null) toast("Saved in WebCraft. Note: this app is linked to a file — editing that file will replace these changes.");
            return true;
        }

        @JavascriptInterface
        public void exportHtml(String id) {
            Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return;
            try {
                String where = Web.saveToDownloads(HomeActivity.this, a.name + ".html", "text/html",
                        Store.get(HomeActivity.this).read(a).getBytes(StandardCharsets.UTF_8));
                toast("Exported to " + where);
            } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
        }

        @JavascriptInterface
        public void backup(String id) {
            final Store.App a = Store.get(HomeActivity.this).find(id);
            if (a == null) return;
            runOnUiThread(() -> runHidden(a, "__wc/backup", null, json -> {
                try {
                    JSONObject o = new JSONObject()
                            .put("webcraft", 2).put("app", a.name)
                            .put("time", System.currentTimeMillis())
                            .put("localStorage", new JSONObject(json));
                    String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
                    String where = Web.saveToDownloads(HomeActivity.this, a.name + " backup " + stamp + ".json",
                            "application/json", o.toString(1).getBytes(StandardCharsets.UTF_8));
                    toast("Backed up " + o.getJSONObject("localStorage").length() + " items to " + where);
                } catch (Exception e) { toast("Backup failed: " + e.getMessage()); }
            }));
        }

        @JavascriptInterface
        public void restore(String id) {
            restoreId = id;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            runOnUiThread(() -> startActivityForResult(i, RC_RESTORE));
        }

        @JavascriptInterface
        public String cacheInfo() {
            return String.valueOf(Web.cacheSize(HomeActivity.this));
        }

        @JavascriptInterface
        public void clearCache() {
            Web.clearCache(HomeActivity.this);
            toast("Offline font/library cache cleared");
        }
    }
}
