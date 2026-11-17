package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseArray;

import androidx.core.graphics.PathParser;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * User-installable icon packs (.icons archives: metadata.json plus SVG files named after the app's drawables).
 * The active pack replaces drawables at load time by wrapping Resources, so nothing is rebuilt.
 */
public class IconPack {

    private static final String PREFS = "nullgram_iconpack";
    private static final int MAX_FILES = 2000, MAX_FILE = 300 * 1024, MAX_TOTAL = 24 * 1024 * 1024;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_\\-]{1,80}\\.svg");

    private static volatile String activeId = null;
    private static boolean loaded;
    private static final HashMap<String, Parsed> parsed = new HashMap<>();
    private static HashMap<String, String> files = new HashMap<>();
    private static File packDir;
    private static PackResources wrapper;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static File root() {
        return new File(ApplicationLoader.getFilesDirFixed(), "icon_packs");
    }

    public static synchronized boolean isActive() {
        ensureLoaded();
        return activeId != null;
    }

    public static synchronized String activeName() {
        ensureLoaded();
        return activeId == null ? null : prefs().getString("name", activeId);
    }

    private static void ensureLoaded() {
        if (loaded || ApplicationLoader.applicationContext == null) {
            return;
        }
        loaded = true;
        parsed.clear();
        files = new HashMap<>();
        final String id = prefs().getString("active", null);
        if (id == null) {
            activeId = null;
            return;
        }
        final File dir = new File(root(), id);
        try {
            final JSONObject meta = new JSONObject(new String(readAll(new FileInputStream(new File(dir, "metadata.json"))), "UTF-8"));
            final JSONObject icons = meta.getJSONObject("icons");
            final java.util.Iterator<String> it = icons.keys();
            while (it.hasNext()) {
                final String name = it.next();
                final String file = icons.getString(name);
                if (SAFE.matcher(file).matches()) {
                    files.put(name, file);
                }
            }
            packDir = dir;
            activeId = id;
        } catch (Throwable t) {
            FileLog.e(t);
            activeId = null;
        }
    }

    /** Imports a pack from a picked file; returns the pack's name or null. Runs on the calling thread. */
    public static String importPack(Context context, Uri uri) {
        File tmp = null;
        try {
            final File base = root();
            base.mkdirs();
            tmp = new File(base, "import_" + System.nanoTime());
            tmp.mkdirs();
            int count = 0;
            long total = 0;
            String meta = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    final String name = e.getName();
                    if (e.isDirectory() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                        continue;
                    }
                    final boolean isMeta = name.equals("metadata.json");
                    if (!isMeta && !SAFE.matcher(name).matches()) {
                        continue;
                    }
                    if (++count > MAX_FILES) {
                        throw new Exception("too many files");
                    }
                    final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    final byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > MAX_FILE) {
                            throw new Exception("file too large");
                        }
                    }
                    total += bo.size();
                    if (total > MAX_TOTAL) {
                        throw new Exception("pack too large");
                    }
                    try (FileOutputStream out = new FileOutputStream(new File(tmp, name))) {
                        bo.writeTo(out);
                    }
                    if (isMeta) {
                        meta = bo.toString("UTF-8");
                    }
                }
            }
            if (meta == null) {
                throw new Exception("metadata.json missing");
            }
            final JSONObject o = new JSONObject(meta);
            if (o.getJSONObject("icons").length() == 0) {
                throw new Exception("no icons");
            }
            final String id = o.optString("packId", "pack").replaceAll("[^A-Za-z0-9_\\-]", "_");
            final String name = o.optString("packName", id);
            final File dest = new File(base, id);
            deleteRecursive(dest);
            if (!tmp.renameTo(dest)) {
                throw new Exception("cannot store pack");
            }
            tmp = null;
            prefs().edit().putString("active", id).putString("name", name).apply();
            synchronized (IconPack.class) {
                loaded = false;
                wrapper = null;
            }
            return name;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (tmp != null) {
                deleteRecursive(tmp);
            }
        }
    }

    public static void reset() {
        prefs().edit().remove("active").remove("name").apply();
        synchronized (IconPack.class) {
            loaded = false;
            wrapper = null;
            activeId = null;
        }
        deleteRecursive(root());
    }

    private static void deleteRecursive(File f) {
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        f.delete();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    // ---------------------------------------------------------------- Resources wrapper
    public static Resources wrap(Resources base) {
        if (base == null || ApplicationLoader.applicationContext == null || base instanceof PackResources || !isActive()) {
            return base;
        }
        synchronized (IconPack.class) {
            final Configuration cfg = base.getConfiguration();
            if (wrapper == null || wrapper.source != base || !wrapper.getConfiguration().equals(cfg)) {
                wrapper = new PackResources(base);
            }
            return wrapper;
        }
    }

    private static class Parsed {
        final ArrayList<Path> paths = new ArrayList<>();
        final ArrayList<Integer> alphas = new ArrayList<>();
        float vw = 24, vh = 24;
    }

    private static synchronized Parsed parse(String name) {
        Parsed p = parsed.get(name);
        if (p != null) {
            return p;
        }
        final String file = files.get(name);
        if (file == null || packDir == null) {
            return null;
        }
        try {
            final String svg = new String(readAll(new FileInputStream(new File(packDir, file))), "UTF-8");
            p = new Parsed();
            final Matcher vb = Pattern.compile("viewBox=\"([^\"]+)\"").matcher(svg);
            if (vb.find()) {
                final String[] v = vb.group(1).trim().split("[ ,]+");
                if (v.length == 4) {
                    p.vw = Float.parseFloat(v[2]);
                    p.vh = Float.parseFloat(v[3]);
                }
            }
            final Matcher m = Pattern.compile("<path\\b([^>]*?)/?>", Pattern.DOTALL).matcher(svg);
            while (m.find()) {
                final String a = m.group(1);
                final Matcher d = Pattern.compile("\\sd=\"([^\"]*)\"").matcher(a);
                if (!d.find() || a.contains("fill=\"none\"")) {
                    continue;
                }
                final Path path = PathParser.createPathFromPathData(d.group(1));
                if (path == null) {
                    continue;
                }
                if (a.contains("fill-rule=\"evenodd\"")) {
                    path.setFillType(Path.FillType.EVEN_ODD);
                }
                int alpha = 255;
                final Matcher op = Pattern.compile("opacity=\"([\\d.]+)\"").matcher(a);
                if (op.find()) {
                    alpha = Math.round(Float.parseFloat(op.group(1)) * 255);
                }
                p.paths.add(path);
                p.alphas.add(alpha);
            }
            if (p.paths.isEmpty() || p.vw <= 0 || p.vh <= 0) {
                p = null;
            }
        } catch (Throwable t) {
            FileLog.e(t);
            p = null;
        }
        if (p != null) {
            parsed.put(name, p);
        } else {
            files.remove(name);
        }
        return p;
    }

    private static class PackResources extends Resources {
        final Resources source;
        private final SparseArray<String> names = new SparseArray<>();

        PackResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), new Configuration(base.getConfiguration()));
            source = base;
        }

        private String nameOf(int id) {
            String n = names.get(id);
            if (n == null) {
                try {
                    n = getResourceEntryName(id);
                } catch (Throwable t) {
                    n = "";
                }
                names.put(id, n);
            }
            return n;
        }

        private Drawable pack(int id, Drawable original) {
            if (original == null) {
                return null;
            }
            final String name = nameOf(id);
            if (name.isEmpty() || !files.containsKey(name)) {
                return original;
            }
            final Parsed p = parse(name);
            if (p == null) {
                return original;
            }
            final int w = original.getIntrinsicWidth() > 0 ? original.getIntrinsicWidth() : Math.round(24 * getDisplayMetrics().density);
            final int h = original.getIntrinsicHeight() > 0 ? original.getIntrinsicHeight() : Math.round(24 * getDisplayMetrics().density);
            return new SvgDrawable(p, w, h);
        }

        @Override
        public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
            return pack(id, super.getDrawable(id, theme));
        }

        @Override
        public Drawable getDrawableForDensity(int id, int density, Theme theme) {
            return pack(id, super.getDrawableForDensity(id, density, theme));
        }
    }

    private static class SvgDrawable extends Drawable {
        private final Parsed p;
        private final int w, h;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        private ColorStateList tint;
        private PorterDuff.Mode tintMode = PorterDuff.Mode.SRC_IN;
        private ColorFilter filter;

        SvgDrawable(Parsed p, int w, int h) {
            this.p = p;
            this.w = w;
            this.h = h;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            if (tint != null) {
                paint.setColorFilter(new PorterDuffColorFilter(tint.getColorForState(getState(), tint.getDefaultColor()), tintMode));
            } else {
                paint.setColorFilter(filter);
            }
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / p.vw, b.height() / p.vh);
            for (int i = 0; i < p.paths.size(); i++) {
                paint.setAlpha(p.alphas.get(i) * alpha / 255);
                canvas.drawPath(p.paths.get(i), paint);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            filter = cf;
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList t) {
            tint = t;
            invalidateSelf();
        }

        @Override
        public void setTintMode(PorterDuff.Mode m) {
            tintMode = m == null ? PorterDuff.Mode.SRC_IN : m;
            invalidateSelf();
        }

        @Override
        public boolean isStateful() {
            return tint != null && tint.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return tint != null && tint.isStateful();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }
    }
}
