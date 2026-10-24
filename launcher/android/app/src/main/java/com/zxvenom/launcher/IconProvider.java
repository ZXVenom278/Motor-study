package com.zxvenom.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Renders app icons to PNG bytes for {@code https://launcher.local/icon/<component>}.
 *
 * <p>Thread-safe: called from WebView IO threads. Results are kept in a byte-size bounded LRU
 * cache; rendering itself is serialized so concurrent requests never draw the same drawable state.
 */
final class IconProvider {

    private static final String TAG = "ZXIcons";
    static final int SIZE_PX = 192;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;

    private static IconProvider instance;

    /** Process-wide instance, so rendered icons survive activity re-creation. */
    static synchronized IconProvider get(Context context) {
        if (instance == null) instance = new IconProvider(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final LauncherApps launcherApps;
    private final UserHandle user = Process.myUserHandle();
    private final Object renderLock = new Object();
    private final LruCache<String, byte[]> cache = new LruCache<String, byte[]>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private IconProvider(Context appContext) {
        context = appContext;
        launcherApps = appContext.getSystemService(LauncherApps.class);
    }

    /**
     * PNG for a flattened component ("pkg/cls"), or null if the activity isn't installed / visible.
     */
    byte[] png(String componentFlat) {
        if (componentFlat == null || componentFlat.isEmpty()) return null;
        ComponentName cn = ComponentName.unflattenFromString(componentFlat);
        if (cn == null) return null;
        String key = cn.flattenToString(); // normalized ("pkg/.Main" -> "pkg/pkg.Main")

        byte[] cached = cache.get(key);
        if (cached != null) return cached;

        synchronized (renderLock) {
            cached = cache.get(key); // another thread may have rendered it while we waited
            if (cached != null) return cached;
            LauncherActivityInfo info = resolve(cn);
            if (info == null) return null;
            byte[] png = render(info);
            if (png != null) cache.put(key, png);
            return png;
        }
    }

    /** Drops cached icons of a package (installed, updated, removed, ...). */
    void invalidate(String pkg) {
        if (pkg == null) return;
        String prefix = pkg + "/";
        for (String key : cache.snapshot().keySet()) {
            if (key.startsWith(prefix)) cache.remove(key);
        }
    }

    void clear() {
        cache.evictAll();
    }

    private LauncherActivityInfo resolve(ComponentName cn) {
        if (launcherApps == null) return null;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn);
            LauncherActivityInfo info = launcherApps.resolveActivity(intent, user);
            if (info != null && cn.equals(info.getComponentName())) return info;
        } catch (RuntimeException e) {
            Log.w(TAG, "resolveActivity failed for " + cn, e);
        }
        try {
            List<LauncherActivityInfo> list = launcherApps.getActivityList(cn.getPackageName(), user);
            for (LauncherActivityInfo info : list) {
                if (cn.equals(info.getComponentName())) return info;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "getActivityList failed for " + cn.getPackageName(), e);
        }
        return null;
    }

    private byte[] render(LauncherActivityInfo info) {
        Drawable icon = null;
        try {
            icon = info.getIcon(0); // 0 = default density
        } catch (RuntimeException e) {
            Log.w(TAG, "getIcon failed for " + info.getComponentName(), e);
        }
        if (icon == null) icon = context.getPackageManager().getDefaultActivityIcon();

        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, SIZE_PX, SIZE_PX);
            icon.draw(canvas); // AdaptiveIconDrawable applies the system mask shape here
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
            return out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "rendering icon failed for " + info.getComponentName(), e);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
