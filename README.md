# WebCraft — Android Container for Mini Web Apps

**Compatibility:** Any Android Device (Phones, Tablets, Foldables) · Android 7.0+ (API 24–34+)  
**Display:** Dynamic Dark / Light theme, Edge-to-edge system bars, Multi-window & Foldable responsive  
**Architecture:** Universal (pure Java + Android WebView · Zero bloat · Tiny ~44 KB APK footprint)  

---

## 📲 Download APK

Download the latest pre-compiled, signed APK directly from the [Releases](https://github.com/hassanakhter079/webcraft-app/releases/latest) page:
- 📥 **[Download WebCraft.apk (v2.0.0)](https://github.com/hassanakhter079/webcraft-app/releases/latest/download/WebCraft.apk)**

---

## 💡 What is WebCraft?

**WebCraft** turns standalone single-file HTML/CSS/JavaScript tools and interactive web apps into first-class Android applications.

Instead of running web apps inside a shared browser tab with mixed cookies and fragile storage, WebCraft gives each web tool its own dedicated, persistent runtime environment, its own entry in Android's Recents overview, and home-screen launcher shortcuts.

---

## ✨ Key Features

| Feature | Implementation |
|---|---|
| **Per-App Origin Isolation** | Serves each app from `https://<id>.webcraft.local/` — guaranteed isolated `localStorage`, `IndexedDB`, and cache boundaries with zero cross-app data leakage. |
| **Offline CDN & Font Caching** | Intercepts remote font and script requests (Google Fonts, unpkg, cdnjs) on first launch and caches them locally so your apps function 100% offline. |
| **Independent Multi-Tasking** | Configured with `documentLaunchMode="intoExisting"` so each active mini-app appears as an independent card in the Android Recents app switcher. |
| **Live Source Synchronization** | Links with Android's Storage Access Framework (SAF) via persistable URI permissions; updates to source `.html` files in storage sync automatically on launch. |
| **Home Screen Pinning & Shortcuts** | Automatically generates adaptive launcher icons using the app's initial and `theme-color` for home screen pinning and long-press launcher shortcuts. |
| **Dynamic System Bar Tinting** | Watches `<meta name="theme-color">` dynamically via `MutationObserver` and seamlessly colors the Android status bar and navigation bar. |
| **Native Web API Polyfills** | Full support for modern web APIs: `navigator.wakeLock` (screen wake lock), `navigator.vibrate` (haptics), `navigator.share` (system share sheet). |
| **Direct File Downloads** | Handles `<a download>` clicks for `blob:` and `data:` URLs, saving files directly into `Download/WebCraft/` with native toast notifications. |
| **Backup & State Migration** | One-tap JSON export and import for `localStorage` and app state per mini-app. |
| **Built-in Live Code Editor** | Edit HTML/JS code directly within WebCraft, with syntax formatting, tab indenting, and instant hot-reloading. |

---

## 🏗️ Project Structure

```
webcraft-app/
├── AndroidManifest.xml          # Package, permissions, activities, and intent filters
├── build.sh                     # On-device & CI build script (aapt, javac, d8, apksigner)
├── assets/
│   └── home.html                # WebCraft Library UI (management, import, editor)
├── original/
│   └── after-salah-zikr.original.html
├── res/                         # App icon, styles, and string resources
├── src/com/webcraft/app/
│   ├── HomeActivity.java        # Library dashboard, file picker, backup & restore
│   ├── AppActivity.java         # Dedicated mini-app host activity & native bridge
│   ├── Store.java               # App catalog, file storage, and SAF auto-sync
│   ├── Web.java                 # Custom URL scheme, CDN caching, and polyfill injectors
│   └── Shortcuts.java           # Android ShortcutManager integration and adaptive icons
└── stubs/                       # Compile-time stubs for lambda desugaring (D8)
```

---

## 🛠️ Building from Source

### Prerequisites
- Android SDK Build Tools (`aapt`, `d8` / `r8`, `zipalign`, `apksigner`)
- Java Development Kit (JDK 8 or higher)
- Android SDK platform jar (`android.jar` API 24+)

### 1. Build via `build.sh`
WebCraft includes a self-contained, lightning-fast build script capable of running inside Linux or directly on an Android device via Termux:

```bash
chmod +x build.sh
./build.sh
```

The script performs:
1. `aapt` package and `R.java` generation
2. `javac` Java 8 bytecode compilation
3. `d8` DEX generation with min-API 24 desugaring
4. Resource alignment with `zipalign`
5. V2/V3 APK signing with `apksigner`

Output APK will be generated at:
```
build/WebCraft.apk
```

### 2. Install on Device
```bash
adb install -r build/WebCraft.apk
```
Or transfer `WebCraft.apk` to your phone or tablet and install via your file manager.

---

## 🌐 JavaScript Native Bridge API

WebCraft exposes native capabilities to mini-apps while keeping the environment secure:

| JavaScript Call | Native Behavior |
|---|---|
| `navigator.wakeLock.request('screen')` | Acquires Android `FLAG_KEEP_SCREEN_ON` |
| `navigator.vibrate(pattern)` | Triggers native `Vibrator` with haptic feedback |
| `navigator.share({title, text, url})` | Opens Android System Share Sheet |
| `<meta name="theme-color" content="...">` | Real-time status bar and navigation bar recoloring |
| `<a download="..." href="blob:...">` | Downloads directly to `/sdcard/Download/WebCraft/` |
| `window.WebCraft` | Global object exposing `{version: '2.0', native: true}` |

---

## 📄 License

Open source and available under the standard MIT License.
