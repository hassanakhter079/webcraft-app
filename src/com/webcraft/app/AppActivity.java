package com.webcraft.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

/** Runs one mini app full-window, in its own recents task. */
public class AppActivity extends Activity {
    private static final int RC_FILE = 1, RC_LOCATION = 2;

    private WebView web;
    private Store.App app;
    private int barColor;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    static Intent intentFor(android.content.Context c, String id) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("webcraft://app/" + id), c, AppActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Uri data = getIntent().getData();
        String id = data == null ? null : data.getLastPathSegment();
        Store st = Store.get(this);
        app = st.find(id);
        if (app == null) {
            Toast.makeText(this, "This app is no longer in WebCraft", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        app.opened = System.currentTimeMillis();
        app.opens++;
        st.save();

        // Paint the app's last known theme colour immediately: no white flash on launch
        barColor = app.color != 0 ? app.color : Color.rgb(15, 17, 21);
        getWindow().getDecorView().setBackgroundColor(barColor);
        applyBars();

        web = new WebView(this);
        web.setBackgroundColor(barColor);
        Web.setup(web);
        web.addJavascriptInterface(new Bridge(), "WebCraftNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        setContentView(web);

        if (saved != null) web.restoreState(saved);
        if (web.getUrl() == null) web.loadUrl(Web.urlFor(app.id));
        final android.content.Context ctx = getApplicationContext();
        new Thread(() -> Shortcuts.refresh(ctx)).start();
    }

    private void applyBars() {
        android.view.Window w = getWindow();
        w.setStatusBarColor(barColor);
        w.setNavigationBarColor(barColor);
        boolean light = luminance(barColor) > 0.6;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (app.full) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                   | View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        setTaskDescription(new ActivityManager.TaskDescription(app.name, null, barColor | 0xFF000000));
    }

    static double luminance(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && app != null && app.full) applyBars();
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause()  { if (web != null) web.onPause(); super.onPause(); }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("WebCraftNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ───────────────────────── WebView clients ─────────────────────────

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return Web.intercept(AppActivity.this, r);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (Web.isLocal(u)) return false;
            // External links open in the browser / relevant app instead of replacing the mini app
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            return true;
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            // Renderer crashed or was killed for memory: rebuild instead of crashing the app
            if (web == v) {
                web = null;
                if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
                v.destroy();
                recreate();
            }
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                cb.invoke(origin, true, false);
            } else {
                geoOrigin = origin;
                geoCallback = cb;
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}, RC_LOCATION);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                Intent i = p.createIntent();
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, RC_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        if (rc != RC_LOCATION || geoCallback == null) return;
        boolean ok = false;
        for (int r : res) ok |= r == PackageManager.PERMISSION_GRANTED;
        geoCallback.invoke(geoOrigin, ok, false);
        geoCallback = null;
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        if (rc != RC_FILE || fileCallback == null) { super.onActivityResult(rc, result, data); return; }
        Uri[] out = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                out = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    // ───────────────────────── JS bridge (window.WebCraftNative) ─────────────────────────

    private class Bridge {
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void vibrate(String json) {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb == null || !vb.hasVibrator()) return;
            try {
                long[] pattern;
                Object o = new org.json.JSONTokener(json).nextValue();
                if (o instanceof JSONArray) {
                    JSONArray a = (JSONArray) o;
                    pattern = new long[a.length() + 1];            // leading 0 = start immediately
                    for (int i = 0; i < a.length(); i++) pattern[i + 1] = Math.max(0, Math.min(10000, a.optLong(i)));
                } else {
                    pattern = new long[]{0, Math.max(0, Math.min(10000, ((Number) o).longValue()))};
                }
                long total = 0;
                for (long x : pattern) total += x;
                if (total == 0) { vb.cancel(); return; }
                if (pattern.length == 2) vb.vibrate(VibrationEffect.createOneShot(pattern[1], VibrationEffect.DEFAULT_AMPLITUDE));
                else vb.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void themeColor(int r, int g, int b) {
            final int c = Color.rgb(r, g, b);
            runOnUiThread(() -> {
                if (c == barColor || web == null) return;
                barColor = c;
                web.setBackgroundColor(c);
                getWindow().getDecorView().setBackgroundColor(c);
                applyBars();
                app.color = c;             // remembered for a flash-free next launch
                Store.get(AppActivity.this).save();
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                String where = Web.saveToDownloads(AppActivity.this, name, mime, Base64.decode(base64, Base64.DEFAULT));
                toast("Saved to " + where);
            } catch (Exception e) {
                toast("Could not save file: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void share(String title, String text, String url) {
            String body = text.isEmpty() ? url : (url.isEmpty() ? text : text + "\n" + url);
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body);
            if (!title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
            runOnUiThread(() -> startActivity(Intent.createChooser(i, title.isEmpty() ? "Share" : title)));
        }
    }

    private void toast(final String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
