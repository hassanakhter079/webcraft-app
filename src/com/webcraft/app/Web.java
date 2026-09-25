package com.webcraft.app;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves mini apps from https://<id>.webcraft.local/ — a stable, secure origin per app,
 * so localStorage / IndexedDB persist reliably and never leak between apps.
 * Also caches font/CDN files so pages work offline after their first run.
 */
final class Web {
    static final String SUFFIX = ".webcraft.local";
    static final String HOME = "https://home" + SUFFIX + "/";

    static String urlFor(String id) { return "https://" + id + SUFFIX + "/"; }

    static boolean isLocal(Uri u) {
        return u != null && u.getHost() != null && u.getHost().endsWith(SUFFIX);
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);          // everything is served through the interceptor
        s.setAllowContentAccess(false);
        s.setTextZoom(100);                   // pages render as designed, regardless of system font scale
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);   // smoother first frames / scrolling
        w.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
    }

    // ───────────────────────── request routing ─────────────────────────

    static WebResourceResponse intercept(Context c, WebResourceRequest req) {
        Uri u = req.getUrl();
        String host = u.getHost();
        if (host == null) return null;
        if (host.endsWith(SUFFIX)) return local(c, host.substring(0, host.length() - SUFFIX.length()), u.getPath());
        if ("GET".equalsIgnoreCase(req.getMethod()) && isCdn(host)) return cdn(c, req);
        return null;
    }

    private static WebResourceResponse local(Context c, String id, String path) {
        if (path == null || path.isEmpty()) path = "/";
        try {
            if ("home".equals(id)) {
                if (path.equals("/")) return html(Store.readAll(c.getAssets().open("home.html")));
                return notFound();
            }
            if (path.equals("/__wc/backup")) return html(BACKUP_PAGE.getBytes(StandardCharsets.UTF_8));
            if (path.equals("/__wc/restore")) return html(RESTORE_PAGE.getBytes(StandardCharsets.UTF_8));
            if (path.equals("/") || path.equals("/index.html")) {
                Store st = Store.get(c);
                Store.App a = st.find(id);
                if (a == null) return html(("<meta name=viewport content='width=device-width'>"
                        + "<body style='font:16px sans-serif;padding:24px'>This app was removed from WebCraft.").getBytes(StandardCharsets.UTF_8));
                st.syncFromSource(a);
                return html(inject(st.read(a)).getBytes(StandardCharsets.UTF_8));
            }
            if (path.equals("/favicon.ico")) return notFound();
        } catch (Exception ignored) {}
        return notFound();
    }

    /** Put the compatibility shim first in <head>, before any page script runs. */
    static String inject(String html) {
        String tag = "<script>" + SHIM + "</script>";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<head[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return html.substring(0, m.end()) + tag + html.substring(m.end());
        m = java.util.regex.Pattern.compile("<html[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return html.substring(0, m.end()) + tag + html.substring(m.end());
        return tag + html;
    }

    private static WebResourceResponse html(byte[] b) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, new ByteArrayInputStream(b));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    // ───────────────────────── offline CDN cache ─────────────────────────

    private static final String[] CDN = {
        "fonts.googleapis.com", "fonts.gstatic.com", "cdnjs.cloudflare.com", "cdn.jsdelivr.net",
        "unpkg.com", "code.jquery.com", "cdn.tailwindcss.com", "use.fontawesome.com", "ka-f.fontawesome.com",
        "stackpath.bootstrapcdn.com", "maxcdn.bootstrapcdn.com", "rsms.me", "api.fontshare.com", "cdn.fontshare.com"
    };

    private static boolean isCdn(String host) {
        for (String h : CDN) if (host.equals(h)) return true;
        return false;
    }

    /**
     * Cache-first: once a font/library has been fetched it is served from disk,
     * so pages start faster and keep their fonts with no internet.
     */
    private static WebResourceResponse cdn(Context c, WebResourceRequest req) {
        String url = req.getUrl().toString();
        File dir = new File(c.getFilesDir(), "cdn");
        String key = sha1(url);
        File body = new File(dir, key), type = new File(dir, key + ".t");
        try {
            if (body.exists() && type.exists()) return cached(body, type);
        } catch (Exception ignored) {}

        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                // UA matters: Google Fonts picks the font format by user agent
                if (!e.getKey().equalsIgnoreCase("Accept-Encoding")) con.setRequestProperty(e.getKey(), e.getValue());
            }
            if (con.getResponseCode() != 200) return null;
            byte[] data = Store.readAll(con.getInputStream());
            String ct = con.getContentType();
            if (ct == null) ct = "application/octet-stream";
            dir.mkdirs();
            File tmp = new File(dir, key + ".part");
            try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }
            try (FileOutputStream fo = new FileOutputStream(type)) { fo.write(ct.getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(body);
            return respond(ct, new ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;   // let WebView handle it (e.g. offline and never cached)
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static WebResourceResponse cached(File body, File type) throws Exception {
        String ct = new String(Store.readAll(new FileInputStream(type)), StandardCharsets.UTF_8);
        return respond(ct, new FileInputStream(body));
    }

    private static WebResourceResponse respond(String contentType, java.io.InputStream in) {
        String mime = contentType, enc = null;
        int i = contentType.indexOf(';');
        if (i >= 0) {
            mime = contentType.substring(0, i).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset=([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentType);
            if (m.find()) enc = m.group(1);
        }
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");   // fonts are fetched with CORS
        h.put("Cache-Control", "public, max-age=31536000");
        return new WebResourceResponse(mime, enc, 200, "OK", h, in);
    }

    static long cacheSize(Context c) {
        long n = 0;
        File[] fs = new File(c.getFilesDir(), "cdn").listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    static void clearCache(Context c) {
        File[] fs = new File(c.getFilesDir(), "cdn").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    // ───────────────────────── saving files ─────────────────────────

    /** Save into Download/WebCraft/. Returns a human-readable location. */
    static String saveToDownloads(Context c, String name, String mime, byte[] data) throws Exception {
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebCraft");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("Could not create file");
            try (OutputStream o = c.getContentResolver().openOutputStream(uri)) { o.write(data); }
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WebCraft");
            d.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(d, name))) { o.write(data); }
        }
        return "Download/WebCraft/" + name;
    }

    // ───────────────────────── injected scripts ─────────────────────────

    /**
     * Runs before the page's own scripts. Fills gaps in Android WebView:
     * Screen Wake Lock, reliable vibration, file downloads, Web Share,
     * and reports <meta name="theme-color"> so the system bars follow the page's theme.
     */
    static final String SHIM =
        "(function(){var N=window.WebCraftNative;if(!N)return;" +
        // Screen Wake Lock API (missing in WebView)
        "if(!('wakeLock' in navigator)){var held=0;" +
        "function Sentinel(){var l={},s=this;this.type='screen';this.released=false;this.onrelease=null;" +
        "this.addEventListener=function(t,f){(l[t]=l[t]||[]).push(f)};" +
        "this.removeEventListener=function(t,f){var a=l[t]||[],i=a.indexOf(f);if(i>=0)a.splice(i,1)};" +
        "this.release=function(){if(!s.released){s.released=true;held=Math.max(0,held-1);if(!held)N.keepAwake(false);" +
        "var e={type:'release',target:s};(l.release||[]).forEach(function(f){try{f.call(s,e)}catch(x){}});" +
        "if(typeof s.onrelease==='function')try{s.onrelease(e)}catch(x){}}return Promise.resolve()};" +
        "Sentinel.all.push(this)}Sentinel.all=[];" +
        "try{Object.defineProperty(navigator,'wakeLock',{configurable:true,value:{request:function(t){" +
        "if(document.visibilityState!=='visible')return Promise.reject(new DOMException('Page not visible','NotAllowedError'));" +
        "held++;N.keepAwake(true);return Promise.resolve(new Sentinel())}}})}catch(e){}" +
        // Spec: locks are released when the page is hidden
        "document.addEventListener('visibilitychange',function(){if(document.visibilityState==='hidden'){" +
        "var a=Sentinel.all;Sentinel.all=[];a.forEach(function(s){s.release()})}})}" +
        // Vibration through the native Vibrator (works without a fresh user gesture)
        "try{Object.defineProperty(navigator,'vibrate',{configurable:true,value:function(p){" +
        "try{N.vibrate(JSON.stringify(p==null?0:p))}catch(e){}return true}})}catch(e){}" +
        // Web Share
        "if(!navigator.share){try{Object.defineProperty(navigator,'share',{configurable:true,value:function(d){d=d||{};" +
        "N.share(String(d.title||''),String(d.text||''),String(d.url||''));return Promise.resolve()}})}catch(e){}}" +
        // <a download> for blob:/data: links → Download/WebCraft
        "function save(name,blob){var r=new FileReader();r.onload=function(){var s=String(r.result);" +
        "N.saveFile(name,blob.type||'',s.slice(s.indexOf(',')+1))};r.readAsDataURL(blob)}" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;" +
        "if(!a)return;var h=a.href||'';if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return;e.preventDefault();" +
        "var n=a.getAttribute('download')||'download';fetch(h).then(function(r){return r.blob()}).then(function(b){save(n,b)})},true);" +
        "var ac=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){" +
        "if(this.hasAttribute('download')&&/^(blob|data):/.test(this.href||'')){var n=this.getAttribute('download')||'download';" +
        "fetch(this.href).then(function(r){return r.blob()}).then(function(b){save(n,b)});return}return ac.apply(this,arguments)};" +
        // theme-color → system bars (resolved to rgb by the browser, so any CSS color works)
        "var last='';function tc(){var m=document.querySelector('meta[name=\"theme-color\"]');if(!m)return;" +
        "var c=m.getAttribute('content')||'';if(c===last)return;last=c;var d=document.createElement('i');d.style.color=c;" +
        "(document.body||document.documentElement).appendChild(d);var v=getComputedStyle(d).color;d.remove();" +
        "var p=v.match(/[\\d.]+/g);if(p&&p.length>=3)N.themeColor(+p[0],+p[1],+p[2])}" +
        "document.addEventListener('DOMContentLoaded',function(){tc();if(document.head)" +
        "new MutationObserver(tc).observe(document.head,{subtree:true,childList:true,attributes:true,attributeFilter:['content']})});" +
        "window.WebCraft={version:'2.0',native:true};" +
        "})();";

    private static final String BACKUP_PAGE =
        "<!doctype html><script>var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k)}" +
        "WCB.done(JSON.stringify(o))</script>";

    private static final String RESTORE_PAGE =
        "<!doctype html><script>try{var d=JSON.parse(WCB.data());var s=d.localStorage||d;var n=0;" +
        "for(var k in s)if(Object.prototype.hasOwnProperty.call(s,k)){localStorage.setItem(k,String(s[k]));n++}" +
        "WCB.done(String(n))}catch(e){WCB.done('error:'+e.message)}</script>";

    private Web() {}
}
