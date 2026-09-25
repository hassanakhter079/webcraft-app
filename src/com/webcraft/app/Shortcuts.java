package com.webcraft.app;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Home-screen icons for mini apps: pinned shortcuts + the 4 most recent in the long-press menu. */
final class Shortcuts {

    static boolean pin(Context c, Store.App a) {
        if (Build.VERSION.SDK_INT < 26) return false;
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        return sm.requestPinShortcut(build(c, a), null);
    }

    static void refresh(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> out = new ArrayList<>();
            for (Store.App a : Store.get(c).list()) {
                if (a.opened == 0) continue;
                out.add(build(c, a));
                if (out.size() >= Math.min(4, sm.getMaxShortcutCountPerActivity())) break;
            }
            sm.setDynamicShortcuts(out);
            List<ShortcutInfo> upd = new ArrayList<>();
            for (Store.App a : Store.get(c).list()) upd.add(build(c, a));
            sm.updateShortcuts(upd);    // keeps pinned icons' names/colours current
        } catch (Exception ignored) {}
    }

    static void removed(Context c, Store.App a) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            List<String> ids = new ArrayList<>();
            ids.add(a.id);
            sm.disableShortcuts(ids, "This app was removed from WebCraft");
            sm.removeDynamicShortcuts(ids);
        } catch (Exception ignored) {}
    }

    private static ShortcutInfo build(Context c, Store.App a) {
        return new ShortcutInfo.Builder(c, a.id)
                .setShortLabel(a.name.length() > 24 ? a.name.substring(0, 24) : a.name)
                .setLongLabel(a.name)
                .setIcon(Icon.createWithAdaptiveBitmap(icon(a)))
                .setIntent(AppActivity.intentFor(c, a.id))
                .build();
    }

    /** Adaptive icon: the app's theme colour with its initial. */
    static Bitmap icon(Store.App a) {
        int size = 216;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int bg = a.color != 0 ? a.color : Color.rgb(20, 24, 33);
        cv.drawColor(bg | 0xFF000000);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(AppActivity.luminance(bg) > 0.6 ? Color.rgb(30, 30, 30) : Color.rgb(232, 176, 75));
        p.setTextSize(size * 0.36f);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        String letter = a.name.isEmpty() ? "?" : a.name.substring(0, a.name.offsetByCodePoints(0, 1)).toUpperCase();
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }

    private Shortcuts() {}
}
