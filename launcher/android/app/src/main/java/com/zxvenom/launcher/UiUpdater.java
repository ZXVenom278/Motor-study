package com.zxvenom.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Keeps the live UI - one HTML file in a GitHub repo - in sync with the head of a branch.
 *
 * <p>A check resolves the branch head SHA (git smart-HTTP ref advertisement, falling back to the
 * REST API). When it differs from the cached one, {@code raw.githubusercontent.com/{owner}/{repo}/{sha}/{path}}
 * is downloaded to {@code filesDir/ui/index.html} through a temp file and an atomic rename.
 *
 * <p>Threading: all network and disk work runs on one background thread, so at most one check is
 * in flight. Polling is driven by a main-thread Handler while the home screen is resumed.
 * {@link #openUi()} may be called from any thread (WebView IO threads). Listener callbacks are
 * delivered on the main thread.
 */
final class UiUpdater {

    // ---- Default UI source. Change these to point the launcher at another repo, branch or file. ----
    static final String DEFAULT_OWNER = "ZXVenom278";
    static final String DEFAULT_REPO = "Motor-study";
    static final String DEFAULT_BRANCH = "ccr-9d5bfb07-3koy9f";
    static final String DEFAULT_PATH = "launcher/ui/index.html";

    static final long POLL_INTERVAL_MS = 15_000;
    private static final long RESUME_RECHECK_MS = 3_000;
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_REFS_BYTES = 1024 * 1024;
    private static final int MAX_UI_BYTES = 5 * 1024 * 1024;
    private static final int MAX_FIELD_LENGTH = 100;
    private static final int MAX_ERROR_LENGTH = 160;

    static final String FROM_REMOTE = "remote";
    static final String FROM_BUNDLED = "bundled";

    private static final String TAG = "ZXUpdater";
    private static final String PREFS = "launcher";
    private static final String K_OWNER = "owner";
    private static final String K_REPO = "repo";
    private static final String K_BRANCH = "branch";
    private static final String K_PATH = "path";
    private static final String K_SHA = "sha";
    private static final String K_LAST_CHECK = "lastCheck";
    private static final String K_LAST_UPDATE = "lastUpdate";
    private static final String K_LAST_ERROR = "lastError";
    private static final String K_LIVE = "live";

    private static final Pattern OWNER_REPO_RE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern BRANCH_PATH_RE = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern SHA_RE = Pattern.compile("[0-9a-f]{40}");

    /** Last-resort page, used only if the APK was built without assets/index.html. */
    private static final String FALLBACK_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:transparent;color:#fff;font:16px system-ui,sans-serif;text-align:center}"
            + "button{margin:6px;padding:10px 18px;border:0;border-radius:20px;background:rgba(255,255,255,.2);"
            + "color:#fff;font:inherit}</style></head><body><div><p>ZX Launcher: UI file is missing.</p>"
            + "<button onclick=\"Launcher.checkForUpdate()\">Download UI</button>"
            + "<button onclick=\"Launcher.openSettings()\">Android settings</button></div></body></html>";

    /** Update results, always delivered on the main thread. */
    interface Listener {
        /** A new UI file is in place; the page should be told and reloaded. */
        void onUiUpdated(String sha);

        /** Result of a forced check that did not update: status "current" (with sha) or "error" (with message). */
        void onCheckResult(String status, String sha, String message);
    }

    /** Immutable snapshot of where the UI comes from. */
    static final class Source {
        final String owner;
        final String repo;
        final String branch;
        final String path;

        Source(String owner, String repo, String branch, String path) {
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
            this.path = path;
        }
    }

    private static UiUpdater instance;

    /** Process-wide instance, so the cache, executor and polling survive activity re-creation. */
    static synchronized UiUpdater get(Context context) {
        if (instance == null) instance = new UiUpdater(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final File uiDir;
    private final File uiFile;
    private final File tmpFile;
    private final String userAgent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zx-ui-updater");
        t.setDaemon(true);
        return t;
    });

    /** Guards the cached file + stored sha + {@link #generation} as one unit. */
    private final Object lock = new Object();
    /** Bumped whenever the source or cache is reset, so an in-flight check can't commit stale results. */
    private int generation;

    private final AtomicBoolean checkQueued = new AtomicBoolean();
    private final AtomicBoolean queuedForced = new AtomicBoolean();

    private volatile Listener listener;
    private volatile String uiFrom = FROM_BUNDLED;
    private volatile boolean safeMode;

    /** Main thread only: whether the home screen is resumed (polling allowed). */
    private boolean active;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (!active || !isLive()) return;
            checkNow(false);
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private UiUpdater(Context appContext) {
        context = appContext;
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        uiDir = new File(appContext.getFilesDir(), "ui");
        uiFile = new File(uiDir, "index.html");
        tmpFile = new File(uiDir, "index.html.tmp");
        userAgent = "zx-launcher/" + versionName(appContext);
    }

    // ------------------------------------------------------------------------------------------
    // Listener & polling (main thread)
    // ------------------------------------------------------------------------------------------

    void setListener(Listener l) {
        listener = l;
    }

    /** Clears the listener only if it is still {@code l} (a newer activity may have replaced it). */
    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** Called from onResume: check now if the last check is older than 3s, then every 15s. */
    void startPolling() {
        active = true;
        schedulePolling();
    }

    /** Called from onPause. */
    void stopPolling() {
        active = false;
        main.removeCallbacks(pollTask);
    }

    private void schedulePolling() {
        main.removeCallbacks(pollTask);
        if (!active || !isLive()) return;
        long since = System.currentTimeMillis() - getLastCheck();
        if (since < 0 || since > RESUME_RECHECK_MS) {
            main.post(pollTask);
        } else {
            main.postDelayed(pollTask, POLL_INTERVAL_MS - since);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Settings (any thread)
    // ------------------------------------------------------------------------------------------

    Source getSource() {
        return new Source(
                prefs.getString(K_OWNER, DEFAULT_OWNER),
                prefs.getString(K_REPO, DEFAULT_REPO),
                prefs.getString(K_BRANCH, DEFAULT_BRANCH),
                prefs.getString(K_PATH, DEFAULT_PATH));
    }

    String getSha() {
        return prefs.getString(K_SHA, "");
    }

    long getLastCheck() {
        return prefs.getLong(K_LAST_CHECK, 0L);
    }

    long getLastUpdate() {
        return prefs.getLong(K_LAST_UPDATE, 0L);
    }

    String getLastError() {
        return prefs.getString(K_LAST_ERROR, "");
    }

    boolean isLive() {
        return prefs.getBoolean(K_LIVE, true);
    }

    /** "remote" or "bundled": what {@link #openUi()} served last. */
    String getUiFrom() {
        return uiFrom;
    }

    /**
     * Safe mode serves the bundled UI even if a cached remote file exists. The activity turns it on
     * when the WebView renderer keeps dying; the next successfully downloaded version turns it off.
     */
    void setSafeMode(boolean on) {
        safeMode = on;
    }

    static boolean isValidSource(String owner, String repo, String branch, String path) {
        return validField(owner) && OWNER_REPO_RE.matcher(owner).matches()
                && validField(repo) && OWNER_REPO_RE.matcher(repo).matches()
                && validField(branch) && BRANCH_PATH_RE.matcher(branch).matches() && !branch.contains("..")
                && validField(path) && BRANCH_PATH_RE.matcher(path).matches() && !path.contains("..")
                && path.endsWith(".html");
    }

    private static boolean validField(String s) {
        return s != null && !s.isEmpty() && s.length() <= MAX_FIELD_LENGTH;
    }

    /**
     * Points the launcher at a new UI source. The cached sha is cleared but the cached file is kept
     * (and keeps being served) until the new source has been downloaded.
     */
    boolean setSource(String owner, String repo, String branch, String path) {
        if (!isValidSource(owner, repo, branch, path)) return false;
        synchronized (lock) {
            prefs.edit()
                    .putString(K_OWNER, owner)
                    .putString(K_REPO, repo)
                    .putString(K_BRANCH, branch)
                    .putString(K_PATH, path)
                    .remove(K_SHA)
                    .apply();
            generation++;
        }
        checkNow(true);
        return true;
    }

    void resetSource() {
        setSource(DEFAULT_OWNER, DEFAULT_REPO, DEFAULT_BRANCH, DEFAULT_PATH);
    }

    void setLiveUpdates(boolean on) {
        prefs.edit().putBoolean(K_LIVE, on).apply();
        main.post(this::schedulePolling);
    }

    /** Deletes the cached UI and its sha, so the bundled asset is served until the next download. */
    void clearCache() {
        synchronized (lock) {
            deleteQuietly(tmpFile);
            deleteQuietly(uiFile);
            prefs.edit().remove(K_SHA).apply();
            generation++;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Serving (any thread, called by the WebView for https://launcher.local/)
    // ------------------------------------------------------------------------------------------

    /** The UI page: the cached remote file if present and sane, else the bundled asset. Never null. */
    InputStream openUi() {
        if (!safeMode) {
            byte[] cached = readCachedUi();
            if (cached != null) {
                uiFrom = FROM_REMOTE;
                return new ByteArrayInputStream(cached);
            }
        }
        uiFrom = FROM_BUNDLED;
        try {
            return context.getAssets().open("index.html");
        } catch (IOException e) {
            Log.e(TAG, "bundled assets/index.html missing", e);
            return new ByteArrayInputStream(FALLBACK_HTML.getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] readCachedUi() {
        if (!uiFile.isFile()) return null;
        // The file is only ever replaced by rename, so whichever inode we open is complete.
        try (InputStream in = new FileInputStream(uiFile)) {
            byte[] data = readLimited(in, MAX_UI_BYTES, true);
            if (data.length > 0 && containsHtmlTag(data)) return data;
            Log.w(TAG, "cached UI looks corrupt; serving bundled UI");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cached UI unreadable; serving bundled UI", e);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Checking (background thread)
    // ------------------------------------------------------------------------------------------

    /**
     * Queues a check unless one is already waiting to run. A forced check reports "current" and
     * "error" results to the listener; a background poll only reports actual updates.
     */
    void checkNow(boolean force) {
        if (force) queuedForced.set(true);
        if (!checkQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                checkQueued.set(false);
                boolean forced = queuedForced.getAndSet(false);
                try {
                    runCheck(forced);
                } catch (Throwable t) { // never let the updater thread die
                    Log.e(TAG, "update check crashed", t);
                }
            });
        } catch (RuntimeException e) { // RejectedExecutionException: should not happen
            checkQueued.set(false);
            Log.e(TAG, "could not queue update check", e);
        }
    }

    private void runCheck(boolean forced) {
        final Source src;
        final int gen;
        synchronized (lock) {
            src = getSource();
            gen = generation;
        }
        prefs.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();

        try {
            if (!isOnline()) throw new IOException("offline");
            String sha = resolveHeadSha(src);

            synchronized (lock) {
                if (gen != generation) return; // source/cache changed meanwhile; a newer check is queued
                if (sha.equals(getSha()) && uiFile.isFile() && uiFile.length() > 0) {
                    prefs.edit().putString(K_LAST_ERROR, "").apply();
                    if (forced) postResult("current", sha, null);
                    return;
                }
            }

            byte[] html = downloadUi(src, sha);

            synchronized (lock) {
                if (gen != generation) return;
                writeAtomically(html);
                prefs.edit()
                        .putString(K_SHA, sha)
                        .putLong(K_LAST_UPDATE, System.currentTimeMillis())
                        .putString(K_LAST_ERROR, "")
                        .apply();
                safeMode = false;
            }
            Log.i(TAG, "UI updated to " + sha);
            postUpdated(sha);
        } catch (IOException | RuntimeException e) {
            String message = describe(e);
            Log.w(TAG, "update check failed: " + message);
            prefs.edit().putString(K_LAST_ERROR, message).apply();
            if (forced) postResult("error", null, message);
        }
    }

    /** Branch head via the git ref advertisement; falls back to the REST API on any failure. */
    private String resolveHeadSha(Source src) throws IOException {
        try {
            return shaFromRefs(src);
        } catch (IOException | RuntimeException refsError) {
            try {
                return shaFromApi(src);
            } catch (IOException | RuntimeException apiError) {
                throw new IOException(truncate(describe(refsError) + "; api: " + describe(apiError)));
            }
        }
    }

    private String shaFromRefs(Source src) throws IOException {
        String url = "https://github.com/" + src.owner + "/" + src.repo
                + ".git/info/refs?service=git-upload-pack";
        byte[] body = httpGet(url, null, MAX_REFS_BYTES, false);
        // pkt-line framed text that may contain NULs; ISO-8859-1 maps bytes 1:1 to chars.
        String text = new String(body, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("([0-9a-f]{40}) refs/heads/" + Pattern.quote(src.branch)
                + "(?:\\x00|\\n|$)").matcher(text);
        if (m.find()) return m.group(1);
        throw new IOException("branch '" + src.branch + "' not found");
    }

    private String shaFromApi(Source src) throws IOException {
        String url = "https://api.github.com/repos/" + src.owner + "/" + src.repo + "/commits/"
                + URLEncoder.encode(src.branch, "UTF-8");
        byte[] body = httpGet(url, "application/vnd.github.sha", 4096, false);
        String sha = new String(body, StandardCharsets.UTF_8).trim();
        if (!SHA_RE.matcher(sha).matches()) throw new IOException("unexpected API response");
        return sha;
    }

    private byte[] downloadUi(Source src, String sha) throws IOException {
        String path = src.path;
        while (path.startsWith("/")) path = path.substring(1);
        String url = "https://raw.githubusercontent.com/" + src.owner + "/" + src.repo + "/" + sha + "/" + path;
        byte[] body = httpGet(url, null, MAX_UI_BYTES, true);
        if (body.length < 1) throw new IOException("UI file is empty");
        if (!containsHtmlTag(body)) throw new IOException("UI file has no <html> tag");
        return body;
    }

    /**
     * GET that requires HTTP 200. With {@code strict}, a body over {@code maxBytes} is an error;
     * otherwise it is truncated to {@code maxBytes}.
     */
    private byte[] httpGet(String url, String accept, int maxBytes, boolean strict) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", userAgent);
            if (accept != null) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " from " + c.getURL().getHost());
            }
            if (strict && c.getContentLengthLong() > maxBytes) throw new IOException("file too large");
            try (InputStream in = c.getInputStream()) {
                return readLimited(in, maxBytes, strict);
            }
        } finally {
            c.disconnect();
        }
    }

    private void writeAtomically(byte[] data) throws IOException {
        if (!uiDir.isDirectory() && !uiDir.mkdirs()) throw new IOException("can't create " + uiDir);
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            out.write(data);
            out.getFD().sync();
        } catch (IOException e) {
            deleteQuietly(tmpFile);
            throw e;
        }
        // rename(2) atomically replaces the old file; readers see either the old or the new one.
        if (!tmpFile.renameTo(uiFile)) {
            deleteQuietly(tmpFile);
            throw new IOException("can't replace cached UI");
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true; // unknown: just try
        }
    }

    // ------------------------------------------------------------------------------------------
    // Result delivery (main thread)
    // ------------------------------------------------------------------------------------------

    private void postUpdated(String sha) {
        main.post(() -> {
            Listener l = listener;
            if (l == null) return;
            try {
                l.onUiUpdated(sha);
            } catch (RuntimeException e) {
                Log.e(TAG, "listener failed", e);
            }
        });
    }

    private void postResult(String status, String sha, String message) {
        main.post(() -> {
            Listener l = listener;
            if (l == null) return;
            try {
                l.onCheckResult(status, sha, message);
            } catch (RuntimeException e) {
                Log.e(TAG, "listener failed", e);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static byte[] readLimited(InputStream in, int maxBytes, boolean strict) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > maxBytes) {
                if (strict) throw new IOException("file too large");
                out.write(buf, 0, maxBytes - total);
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /** True if the bytes contain "&lt;html", ignoring ASCII case. */
    static boolean containsHtmlTag(byte[] data) {
        final byte[] tag = {'<', 'h', 't', 'm', 'l'};
        outer:
        for (int i = 0; i + tag.length <= data.length; i++) {
            if (data[i] != '<') continue;
            for (int j = 1; j < tag.length; j++) {
                int c = data[i + j];
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                if (c != tag[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String describe(Throwable e) {
        if (e instanceof SocketTimeoutException) return "timed out";
        if (e instanceof UnknownHostException) return "no connection";
        if (e instanceof SSLException) return "TLS error";
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return truncate(m.trim());
    }

    private static String truncate(String s) {
        return s.length() > MAX_ERROR_LENGTH ? s.substring(0, MAX_ERROR_LENGTH) : s;
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) Log.w(TAG, "could not delete " + f);
    }

    private static String versionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "0";
        } catch (Exception e) {
            return "0";
        }
    }
}
