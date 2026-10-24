package com.zxvenom.launcher;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code window.Launcher} object seen by the UI.
 *
 * <p>Every {@code @JavascriptInterface} method runs on the WebView's JavaBridge thread. Anything
 * that touches views or starts activities is posted to the main thread, and every method catches
 * everything: an exception must never propagate into JS or take down the home screen.
 */
final class LauncherBridge {

    private static final String TAG = "ZXBridge";
    private static final String ICON_URL_PREFIX = MainActivity.ORIGIN + "/icon/";
    private static final int MAX_TOAST_CHARS = 200;
    private static final int MAX_QUERY_CHARS = 2000;
    private static final int MAX_URL_CHARS = 8192;

    /** Schemes {@link #openUrl} may hand to other apps. Everything else (intent:, file:, ...) is refused. */
    private static final Set<String> SAFE_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "tel", "mailto", "sms", "smsto", "geo", "market"));

    private final MainActivity activity;
    private final Context app;
    private final UiUpdater updater;
    private final LauncherApps launcherApps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String versionName;
    private final long versionCode;

    private volatile String insetsJson = "{\"top\":0,\"bottom\":0,\"left\":0,\"right\":0,\"ime\":0}";

    LauncherBridge(MainActivity activity, UiUpdater updater) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.updater = updater;
        this.launcherApps = app.getSystemService(LauncherApps.class);

        String name = "0";
        long code = 0;
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (pi.versionName != null) name = pi.versionName;
            code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : legacyVersionCode(pi);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "own package info missing", e);
        }
        versionName = name;
        versionCode = code;
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo pi) {
        return pi.versionCode;
    }

    /** Called by the activity (main thread) whenever window insets change; values in CSS px. */
    void setInsets(String json) {
        insetsJson = json;
    }

    // ------------------------------------------------------------------------------------------
    // Apps
    // ------------------------------------------------------------------------------------------

    /** JSON array of launchable activities: {id,label,pkg,icon,system,installed,updated}, sorted by label. */
    @JavascriptInterface
    public String getApps() {
        try {
            if (launcherApps == null) return "[]";
            UserHandle user = Process.myUserHandle();
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(null, user);
            String self = app.getPackageName();
            PackageManager pm = app.getPackageManager();
            Map<String, long[]> times = new HashMap<>(); // pkg -> {firstInstall, lastUpdate}

            List<AppEntry> entries = new ArrayList<>(activities.size());
            for (LauncherActivityInfo info : activities) {
                ComponentName cn = info.getComponentName();
                String pkg = cn.getPackageName();
                if (self.equals(pkg)) continue;

                long[] t = times.get(pkg);
                if (t == null) {
                    t = new long[] {info.getFirstInstallTime(), 0L};
                    try {
                        PackageInfo pi = pm.getPackageInfo(pkg, 0);
                        t[0] = pi.firstInstallTime;
                        t[1] = pi.lastUpdateTime;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // Not visible / just removed: keep what LauncherActivityInfo knows.
                    }
                    times.put(pkg, t);
                }

                CharSequence label = info.getLabel();
                ApplicationInfo ai = info.getApplicationInfo();
                boolean system = ai != null
                        && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                entries.add(new AppEntry(cn.flattenToString(),
                        label != null && label.length() > 0 ? label.toString() : pkg,
                        pkg, system, t[0], t[1]));
            }

            final Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            Collections.sort(entries, (a, b) -> {
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.id.compareTo(b.id);
            });

            JSONArray out = new JSONArray();
            for (AppEntry e : entries) out.put(e.toJson());
            return out.toString();
        } catch (Throwable t) {
            Log.e(TAG, "getApps failed", t);
            return "[]";
        }
    }

    @JavascriptInterface
    public boolean launch(String id) {
        try {
            final ComponentName cn = parseComponent(id);
            if (cn == null || launcherApps == null) return false;
            runOnMain(() -> {
                try {
                    launcherApps.startMainActivity(cn, Process.myUserHandle(), null, null);
                } catch (RuntimeException e) { // ActivityNotFound, Security (disabled/suspended), ...
                    Log.w(TAG, "launch failed: " + cn, e);
                    showToast(app.getString(R.string.cant_open_app));
                }
            });
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
            return false;
        }
    }

    @JavascriptInterface
    public boolean appInfo(String id) {
        try {
            final ComponentName cn = parseComponent(id);
            if (cn == null || launcherApps == null) return false;
            runOnMain(() -> {
                try {
                    launcherApps.startAppDetailsActivity(cn, Process.myUserHandle(), null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "app info failed: " + cn, e);
                    showToast(app.getString(R.string.cant_open));
                }
            });
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "appInfo failed", t);
            return false;
        }
    }

    @JavascriptInterface
    public boolean uninstall(String pkg) {
        try {
            if (pkg == null) return false;
            String p = pkg.trim();
            if (p.isEmpty() || p.equals(app.getPackageName())) return false;
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", p, null));
            startOnMain(intent, null);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "uninstall failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // System screens
    // ------------------------------------------------------------------------------------------

    @JavascriptInterface
    public void openSettings() {
        try {
            startOnMain(new Intent(Settings.ACTION_SETTINGS), null);
        } catch (Throwable t) {
            Log.e(TAG, "openSettings failed", t);
        }
    }

    @JavascriptInterface
    public void openHomeSettings() {
        try {
            startOnMain(new Intent(Settings.ACTION_HOME_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable t) {
            Log.e(TAG, "openHomeSettings failed", t);
        }
    }

    @JavascriptInterface
    public void openWallpaper() {
        try {
            Intent chooser = Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                    app.getString(R.string.wallpaper_chooser));
            startOnMain(chooser, null);
        } catch (Throwable t) {
            Log.e(TAG, "openWallpaper failed", t);
        }
    }

    /** Expands the notification shade via the hidden StatusBarManager API. */
    @JavascriptInterface
    public boolean openNotifications() {
        try {
            Object statusBar = app.getSystemService("statusbar");
            if (statusBar == null) return false;
            Method expand = Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel");
            expand.invoke(statusBar);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "expandNotificationsPanel unavailable", t);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Links & search
    // ------------------------------------------------------------------------------------------

    @JavascriptInterface
    public boolean openUrl(String url) {
        try {
            return openExternal(url);
        } catch (Throwable t) {
            Log.e(TAG, "openUrl failed", t);
            return false;
        }
    }

    /**
     * ACTION_VIEW for allow-listed schemes only. Also used by the activity for links the page tries
     * to navigate to (the top-level page itself never leaves launcher.local).
     */
    boolean openExternal(String url) {
        Intent intent = viewIntent(url);
        if (intent == null) return false;
        startOnMain(intent, null);
        return true;
    }

    private static Intent viewIntent(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_CHARS) return null;
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || !SAFE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return null;
        uri = uri.normalizeScheme();
        if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && (uri.getHost() == null || uri.getHost().isEmpty())) {
            return null;
        }
        return new Intent(Intent.ACTION_VIEW, uri);
    }

    @JavascriptInterface
    public boolean webSearch(String q) {
        try {
            if (q == null) return false;
            String query = q.trim();
            if (query.isEmpty()) return false;
            if (query.length() > MAX_QUERY_CHARS) query = query.substring(0, MAX_QUERY_CHARS);

            Intent search = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query);
            Intent fallback = viewIntent("https://www.google.com/search?q=" + encode(query));
            startOnMain(search, fallback);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "webSearch failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Feedback
    // ------------------------------------------------------------------------------------------

    /** "long" = long-press haptic; anything else = a light tap. */
    @JavascriptInterface
    public void haptic(String kind) {
        try {
            final int constant = "long".equals(kind)
                    ? HapticFeedbackConstants.LONG_PRESS
                    : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                            ? HapticFeedbackConstants.KEYBOARD_TAP
                            : HapticFeedbackConstants.VIRTUAL_KEY);
            runOnMain(() -> {
                WebView wv = activity.currentWebView();
                if (wv != null) wv.performHapticFeedback(constant);
            });
        } catch (Throwable t) {
            Log.e(TAG, "haptic failed", t);
        }
    }

    @JavascriptInterface
    public void toast(String msg) {
        try {
            if (msg == null || msg.isEmpty()) return;
            showToast(msg.length() > MAX_TOAST_CHARS ? msg.substring(0, MAX_TOAST_CHARS) : msg);
        } catch (Throwable t) {
            Log.e(TAG, "toast failed", t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Info
    // ------------------------------------------------------------------------------------------

    /** {top,bottom,left,right,ime} in CSS px. */
    @JavascriptInterface
    public String getInsets() {
        return insetsJson;
    }

    @JavascriptInterface
    public String getInfo() {
        try {
            UiUpdater.Source src = updater.getSource();
            JSONObject source = new JSONObject()
                    .put("owner", src.owner)
                    .put("repo", src.repo)
                    .put("branch", src.branch)
                    .put("path", src.path);
            return new JSONObject()
                    .put("appVersion", versionName)
                    .put("appVersionCode", versionCode)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                    .put("source", source)
                    .put("sha", updater.getSha())
                    .put("uiFrom", updater.getUiFrom())
                    .put("lastCheck", updater.getLastCheck())
                    .put("lastUpdate", updater.getLastUpdate())
                    .put("lastError", updater.getLastError())
                    .put("live", updater.isLive())
                    .toString();
        } catch (Throwable t) {
            Log.e(TAG, "getInfo failed", t);
            return "{}";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Live UI updates
    // ------------------------------------------------------------------------------------------

    @JavascriptInterface
    public void checkForUpdate() {
        try {
            updater.checkNow(true);
        } catch (Throwable t) {
            Log.e(TAG, "checkForUpdate failed", t);
        }
    }

    @JavascriptInterface
    public boolean setSource(String owner, String repo, String branch, String path) {
        try {
            return updater.setSource(owner, repo, branch, path);
        } catch (Throwable t) {
            Log.e(TAG, "setSource failed", t);
            return false;
        }
    }

    @JavascriptInterface
    public void resetSource() {
        try {
            updater.resetSource();
        } catch (Throwable t) {
            Log.e(TAG, "resetSource failed", t);
        }
    }

    @JavascriptInterface
    public void setLiveUpdates(boolean on) {
        try {
            updater.setLiveUpdates(on);
        } catch (Throwable t) {
            Log.e(TAG, "setLiveUpdates failed", t);
        }
    }

    @JavascriptInterface
    public void reloadUi() {
        try {
            runOnMain(activity::reloadUi);
        } catch (Throwable t) {
            Log.e(TAG, "reloadUi failed", t);
        }
    }

    /** Drops the cached remote UI (back to the bundled one), then fetches the current version again. */
    @JavascriptInterface
    public void resetUi() {
        try {
            updater.clearCache();
            runOnMain(activity::reloadUi);
            updater.checkNow(true);
        } catch (Throwable t) {
            Log.e(TAG, "resetUi failed", t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static ComponentName parseComponent(String id) {
        return id == null || id.isEmpty() ? null : ComponentName.unflattenFromString(id);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always supported
        }
    }

    /** Posts to the main thread; the task can never throw out of the Looper. */
    private void runOnMain(Runnable task) {
        main.post(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, "main-thread task failed", t);
            }
        });
    }

    /** Starts {@code intent} (or {@code fallback} if nothing handles it) on the main thread. */
    private void startOnMain(Intent intent, Intent fallback) {
        runOnMain(() -> {
            if (tryStart(intent)) return;
            if (fallback != null && tryStart(fallback)) return;
            showToast(app.getString(R.string.cant_open));
        });
    }

    /** Main thread only. */
    private boolean tryStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Context ctx = activity.isDestroyed() ? app : activity;
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "no handler for " + intent);
            return false;
        } catch (RuntimeException e) { // SecurityException and friends
            Log.w(TAG, "can't start " + intent, e);
            return false;
        }
    }

    private void showToast(String msg) {
        runOnMain(() -> Toast.makeText(app, msg, Toast.LENGTH_SHORT).show());
    }

    /** One row of {@link #getApps()}. */
    private static final class AppEntry {
        final String id;
        final String label;
        final String pkg;
        final boolean system;
        final long installed;
        final long updated;

        AppEntry(String id, String label, String pkg, boolean system, long installed, long updated) {
            this.id = id;
            this.label = label;
            this.pkg = pkg;
            this.system = system;
            this.installed = installed;
            this.updated = updated;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("pkg", pkg)
                    .put("icon", ICON_URL_PREFIX + encode(id) + "?v=" + updated)
                    .put("system", system)
                    .put("installed", installed)
                    .put("updated", updated);
        }
    }
}
