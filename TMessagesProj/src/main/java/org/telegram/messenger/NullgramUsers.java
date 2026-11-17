package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;

/** Which Telegram users run Nullgram, asked from the Nullgram server so a badge can be shown next to their name. */
public class NullgramUsers {

    private static final long ASK_AGAIN_MS = 10 * 60 * 1000L;
    private static final HashSet<Long> yes = new HashSet<>();
    private static final HashMap<Long, Long> asked = new HashMap<>();

    public static boolean has(long userId) {
        synchronized (yes) {
            return yes.contains(userId);
        }
    }

    /** Looks the user up in the background; onChange runs on the UI thread if the answer changed anything. */
    public static void ensure(final long userId, final Runnable onChange) {
        if (NullgramSync.BASE.isEmpty() || userId <= 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        synchronized (asked) {
            final Long last = asked.get(userId);
            if (last != null && now - last < ASK_AGAIN_MS) {
                return;
            }
            asked.put(userId, now);
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/badges").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setDoOutput(true);
                final byte[] body = new JSONObject().put("ids", new JSONArray().put(userId)).toString().getBytes("UTF-8");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
                if (c.getResponseCode() == 200) {
                    final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    final byte[] buf = new byte[2048];
                    int n;
                    try (java.io.InputStream in = c.getInputStream()) {
                        while ((n = in.read(buf)) >= 0) {
                            bo.write(buf, 0, n);
                        }
                    }
                    final JSONArray ids = new JSONObject(bo.toString("UTF-8")).getJSONArray("ids");
                    boolean changed = false;
                    for (int i = 0; i < ids.length(); i++) {
                        synchronized (yes) {
                            changed |= yes.add(ids.getLong(i));
                        }
                    }
                    if (changed && onChange != null) {
                        AndroidUtilities.runOnUIThread(onChange);
                    }
                }
                c.disconnect();
            } catch (Throwable ignore) {
            }
        });
    }
}
