package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Catalog of ready-made plugins kept on the Yologram server: a JSON list, one .py file per entry and a checksum for each. */
public class PluginStore {

    public static class Entry {
        public String file, name, version, description, author, sha256;
    }

    public interface ListCallback {
        void onResult(List<Entry> entries, String error);
    }

    public interface InstallCallback {
        void onResult(String error);
    }

    private static final int MAX_SIZE = 512 * 1024;

    public static void load(final ListCallback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            List<Entry> entries = null;
            String error = null;
            try {
                if (NullgramSync.BASE.isEmpty()) {
                    throw new IllegalStateException("no server");
                }
                final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/plugins/index.json").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + c.getResponseCode());
                }
                final JSONArray arr = new JSONObject(new String(read(c.getInputStream()), "UTF-8")).getJSONArray("plugins");
                entries = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject o = arr.getJSONObject(i);
                    final Entry e = new Entry();
                    e.file = o.getString("file");
                    e.name = o.optString("name", e.file);
                    e.version = o.optString("version", "");
                    e.description = o.optString("description", "");
                    e.author = o.optString("author", "");
                    e.sha256 = o.optString("sha256", "").toLowerCase();
                    entries.add(e);
                }
            } catch (Throwable t) {
                entries = null;
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final List<Entry> result = entries;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> cb.onResult(result, err));
        });
    }

    /** Downloads one plugin, checks its SHA-256 against the catalog and saves it next to the user's own plugins (switched off). */
    public static void install(final Entry e, final InstallCallback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            try {
                final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/plugins/" + e.file).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + c.getResponseCode());
                }
                final byte[] data = read(c.getInputStream());
                final StringBuilder hex = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                    hex.append(String.format("%02x", b));
                }
                if (e.sha256.isEmpty() || !e.sha256.equals(hex.toString())) {
                    throw new IllegalStateException("checksum");
                }
                if (!PluginEngine.install(e.file, data)) {
                    throw new IllegalStateException("file");
                }
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> cb.onResult(err));
        });
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_SIZE) {
                    throw new java.io.IOException("too big");
                }
            }
            return bo.toByteArray();
        }
    }
}
