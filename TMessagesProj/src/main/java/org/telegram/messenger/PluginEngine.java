package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Python plugins. A plugin is one .py file in files/plugins; the engine (src/main/python/yologram_engine.py) calls its
 * hooks. Plugins run with the app's own permissions, so they are off until the user switches them on.
 */
public class PluginEngine implements NotificationCenter.NotificationCenterDelegate {

    public static class Info {
        public String file, name = "", version = "", description = "", error;
        public boolean enabled;
    }

    private static final String PREFS = "nullgram_plugins";
    private static final int MAX_SIZE = 512 * 1024;
    private static final long OUTGOING_TIMEOUT_MS = 1500;

    private static final PluginEngine INSTANCE = new PluginEngine();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "yologram-plugins");
        t.setDaemon(true);
        return t;
    });
    private static final HashMap<String, Info> infos = new HashMap<>();
    private static PyObject engine;
    private static boolean observing;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static File dir() {
        final File d = new File(ApplicationLoader.getFilesDirFixed(), "plugins");
        d.mkdirs();
        return d;
    }

    public static boolean isEnabled(String file) {
        return prefs().getBoolean("on_" + file, false);
    }

    /** True when at least one plugin is switched on (cheap: used on every message sent). */
    public static boolean hasEnabled() {
        for (String f : files()) {
            if (isEnabled(f)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> files() {
        final File[] list = dir().listFiles((d, n) -> n.endsWith(".py"));
        final ArrayList<String> out = new ArrayList<>();
        if (list != null) {
            Arrays.sort(list);
            for (File f : list) {
                out.add(f.getName());
            }
        }
        return out;
    }

    public static synchronized Info info(String file) {
        Info i = infos.get(file);
        if (i == null) {
            i = new Info();
            i.file = file;
            i.name = file.substring(0, file.length() - 3);
            infos.put(file, i);
        }
        i.enabled = isEnabled(file);
        return i;
    }

    private static synchronized void ensureStarted() {
        if (engine != null) {
            return;
        }
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
        }
        engine = Python.getInstance().getModule("yologram_engine");
    }

    /** Switches a plugin on or off. Loads it on the plugin thread; onDone(errorOrNull) runs on the UI thread. */
    public static void setEnabled(final String file, final boolean on, final Utilities.Callback<String> onDone) {
        prefs().edit().putBoolean("on_" + file, on).apply();
        worker.execute(() -> {
            String error = null;
            try {
                if (on) {
                    error = load(file);
                } else if (engine != null) {
                    engine.callAttr("unload", file.substring(0, file.length() - 3));
                }
            } catch (Throwable t) {
                error = firstLine(t);
            }
            if (error != null) {
                prefs().edit().putBoolean("on_" + file, false).apply();
            }
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (onDone != null) {
                    onDone.run(fe);
                }
            });
        });
        if (on) {
            observeMessages();
        }
    }

    private static String load(String file) {
        final Info i = info(file);
        try {
            ensureStarted();
            final List<PyObject> meta = engine.callAttr("load", new File(dir(), file).getAbsolutePath(), Api.INSTANCE).asList();
            i.name = meta.get(0).toString();
            i.version = meta.get(1).toString();
            i.description = meta.get(2).toString();
            i.error = null;
            return null;
        } catch (Throwable t) {
            i.error = firstLine(t);
            FileLog.e(t);
            return i.error;
        }
    }

    private static String firstLine(Throwable t) {
        final String m = t.getMessage();
        if (m == null) {
            return t.getClass().getSimpleName();
        }
        final String[] lines = m.trim().split("\n");
        return lines[lines.length - 1];
    }

    /** At app start: bring the enabled plugins up on the plugin thread. */
    public static void startEnabled() {
        final ArrayList<String> on = new ArrayList<>();
        for (String f : files()) {
            if (isEnabled(f)) {
                on.add(f);
            }
        }
        if (on.isEmpty()) {
            return;
        }
        worker.execute(() -> {
            for (String f : on) {
                load(f);
            }
        });
        observeMessages();
    }

    public static String importPlugin(Context context, Uri uri) {
        try {
            String name = null;
            try (android.database.Cursor c = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                }
            }
            if (name == null) {
                name = "plugin.py";
            }
            name = name.replaceAll("[^A-Za-z0-9_.\\-]", "_");
            if (name.endsWith(".plugin")) {
                name = name.substring(0, name.length() - 7) + ".py";
            }
            if (!name.endsWith(".py")) {
                return null;
            }
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_SIZE) {
                        return null;
                    }
                }
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir(), name))) {
                bo.writeTo(out);
            }
            synchronized (PluginEngine.class) {
                infos.remove(name);
            }
            return name;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Saves a plugin that came from the catalog; the name must be a plain "name.py". Returns false when it is refused. */
    public static boolean install(String file, byte[] data) {
        if (file == null || !file.matches("[A-Za-z0-9_\\-]{1,64}\\.py") || data == null || data.length == 0 || data.length > MAX_SIZE) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir(), file))) {
            out.write(data);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
        synchronized (PluginEngine.class) {
            infos.remove(file);
        }
        return true;
    }

    public static void remove(String file) {
        prefs().edit().remove("on_" + file).apply();
        new File(dir(), file).delete();
        synchronized (PluginEngine.class) {
            infos.remove(file);
        }
        worker.execute(() -> {
            if (engine != null) {
                engine.callAttr("unload", file.substring(0, file.length() - 3));
            }
        });
    }

    // ---------------------------------------------------------------- hooks
    private static synchronized void observeMessages() {
        if (observing) {
            return;
        }
        observing = true;
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(INSTANCE, NotificationCenter.didReceiveNewMessages);
            }
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || engine == null || args.length < 2 || !(args[1] instanceof ArrayList)) {
            return;
        }
        final ArrayList<?> list = (ArrayList<?>) args[1];
        final ArrayList<HashMap<String, Object>> msgs = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof MessageObject && ((MessageObject) o).messageOwner != null) {
                final MessageObject m = (MessageObject) o;
                final HashMap<String, Object> d = new HashMap<>();
                d.put("id", m.getId());
                d.put("dialog_id", m.getDialogId());
                d.put("from_id", m.getFromChatId());
                d.put("text", m.messageOwner.message == null ? "" : m.messageOwner.message);
                d.put("out", m.isOutOwner());
                msgs.add(d);
            }
        }
        if (msgs.isEmpty()) {
            return;
        }
        worker.execute(() -> {
            for (HashMap<String, Object> d : msgs) {
                try {
                    final PyObject dict = Python.getInstance().getBuiltins().callAttr("dict");
                    for (String k : d.keySet()) {
                        dict.callAttr("__setitem__", k, d.get(k));
                    }
                    engine.callAttr("on_message", Api.INSTANCE, dict);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        });
    }

    /** Lets plugins change or cancel an outgoing text. Returns the text to send, or null to drop the message. */
    public static String filterOutgoing(final long dialogId, final String text) {
        if (engine == null) {
            return text;
        }
        try {
            final Future<String> f = worker.submit((Callable<String>) () -> {
                final PyObject r = engine.callAttr("on_send", Api.INSTANCE, dialogId, text);
                return r == null ? null : r.toString();
            });
            final String out = f.get(OUTGOING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return out;
        } catch (Throwable t) {
            FileLog.e(t);
            return text; // a slow or broken plugin must never block sending
        }
    }

    // ---------------------------------------------------------------- what plugins can call
    public static class Api {
        static final Api INSTANCE = new Api();
        private String current = "plugin";

        public void toast(final String text) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show());
        }

        public void log(String text) {
            FileLog.d("[plugin] " + text);
        }

        public long my_id() {
            return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        }

        public void copy(final String text) {
            AndroidUtilities.runOnUIThread(() -> AndroidUtilities.addToClipboard(text));
        }

        public void send_message(final long dialogId, final String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(
                    SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null, false, null, null, null, true, 0, 0, null, false)));
        }

        public String get(String key, String def) {
            return prefs().getString("kv_" + key, def);
        }

        public void set(String key, String value) {
            prefs().edit().putString("kv_" + key, value).apply();
        }
    }
}
