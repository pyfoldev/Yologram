package org.telegram.messenger;

import android.app.Activity;
import android.util.Base64;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.MaterialYou;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class EditHistory {

    private static class Entry {
        final long time;
        final String text;

        Entry(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final HashMap<String, ArrayList<Entry>> map = new HashMap<>();
    private static boolean loaded;
    private static File file;

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        file = new File(ApplicationLoader.getFilesDirFixed(), "nullgram_edits.txt");
        try {
            if (file.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String[] p = line.split("\\|", 4);
                        if (p.length == 4) {
                            final String text = new String(Base64.decode(p[3], Base64.NO_WRAP), "UTF-8");
                            add(p[0] + ":" + p[1], new Entry(Long.parseLong(p[2]), text));
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        loaded = true;
    }

    private static void add(String key, Entry entry) {
        ArrayList<Entry> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        if (!list.isEmpty() && list.get(list.size() - 1).text.equals(entry.text)) {
            return;
        }
        list.add(entry);
    }

    private static String textOf(MessageObject m) {
        return m != null && m.messageOwner != null && m.messageOwner.message != null ? m.messageOwner.message : "";
    }

    public static synchronized void capture(MessageObject old, MessageObject fresh) {
        if (!NullgramConfig.editHistory() || old == null || fresh == null) {
            return;
        }
        final String before = textOf(old);
        final String after = textOf(fresh);
        if (before.isEmpty() || before.equals(after)) {
            return;
        }
        ensureLoaded();
        final long time = old.messageOwner.edit_date != 0 ? old.messageOwner.edit_date : old.messageOwner.date;
        final String key = old.getDialogId() + ":" + old.getId();
        add(key, new Entry(time, before));
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            final String line = old.getDialogId() + "|" + old.getId() + "|" + time + "|"
                + Base64.encodeToString(before.getBytes("UTF-8"), Base64.NO_WRAP) + "\n";
            out.write(line.getBytes("UTF-8"));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static synchronized boolean has(MessageObject m) {
        if (m == null) {
            return false;
        }
        ensureLoaded();
        final ArrayList<Entry> list = map.get(m.getDialogId() + ":" + m.getId());
        return list != null && !list.isEmpty();
    }

    public static synchronized void show(Activity activity, MessageObject m) {
        if (activity == null || m == null) {
            return;
        }
        ensureLoaded();
        final ArrayList<Entry> list = map.get(m.getDialogId() + ":" + m.getId());
        final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        final StringBuilder sb = new StringBuilder();
        sb.append(MaterialYou.s("Now:\n", "Сейчас:\n")).append(textOf(m)).append("\n");
        if (list != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                final Entry e = list.get(i);
                sb.append("\n").append(fmt.format(new Date(e.time * 1000L))).append(":\n").append(e.text).append("\n");
            }
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(MaterialYou.s("Edit history", "История правок"));
        builder.setMessage(sb.toString());
        builder.setPositiveButton("OK", null);
        builder.show();
    }
}
