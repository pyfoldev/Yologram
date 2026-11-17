package org.telegram.messenger;

import org.json.JSONArray;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Translation without Telegram Premium. The texts go to Google's public translate endpoint, so this is a separate,
 * visible switch (Yologram settings → Chats). Premium accounts keep Telegram's own translation.
 */
public class NullgramTranslate {

    public static boolean useFree(int account) {
        return NullgramConfig.freeTranslate() && !UserConfig.getInstance(account).isPremium();
    }

    public static void translate(final TLRPC.TL_messages_translateText req, final RequestDelegate delegate) {
        Utilities.globalQueue.postRunnable(() -> {
            final TLRPC.TL_messages_translateResult result = new TLRPC.TL_messages_translateResult();
            try {
                final String lang = req.to_lang == null || req.to_lang.isEmpty() ? "en" : req.to_lang;
                for (TLRPC.TL_textWithEntities src : req.text) {
                    final TLRPC.TL_textWithEntities dst = new TLRPC.TL_textWithEntities();
                    dst.text = src.text == null || src.text.trim().isEmpty() ? (src.text == null ? "" : src.text) : request(src.text, lang);
                    result.result.add(dst);
                }
                delegate.run(result, null);
            } catch (Throwable t) {
                FileLog.e(t);
                final TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = "TRANSLATION_FAILED";
                delegate.run(null, err);
            }
        });
    }

    private static String request(String text, String lang) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL("https://translate.googleapis.com/translate_a/single").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        final byte[] body = ("client=gtx&sl=auto&tl=" + URLEncoder.encode(lang, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        }
        if (c.getResponseCode() != 200) {
            throw new Exception("HTTP " + c.getResponseCode());
        }
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        try (InputStream in = c.getInputStream()) {
            while ((n = in.read(buf)) >= 0) {
                bo.write(buf, 0, n);
            }
        }
        c.disconnect();
        final JSONArray parts = new JSONArray(bo.toString("UTF-8")).getJSONArray(0);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            final JSONArray p = parts.optJSONArray(i);
            if (p != null && !p.isNull(0)) {
                sb.append(p.getString(0));
            }
        }
        return sb.toString();
    }
}
