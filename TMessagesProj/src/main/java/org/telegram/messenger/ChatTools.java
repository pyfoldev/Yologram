package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.MaterialYou;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class ChatTools {

    private static String fmt(long unix) {
        return unix <= 0 ? "—" : new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date(unix * 1000L));
    }

    public static void showDetails(Activity activity, MessageObject m) {
        if (activity == null || m == null || m.messageOwner == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("ID: ").append(m.getId()).append('\n');
        sb.append(MaterialYou.s("Chat: ", "Чат: ")).append(m.getDialogId()).append('\n');
        sb.append(MaterialYou.s("Sender: ", "Отправитель: ")).append(m.getFromChatId()).append('\n');
        sb.append(MaterialYou.s("Sent: ", "Отправлено: ")).append(fmt(m.messageOwner.date)).append('\n');
        if (m.messageOwner.edit_date != 0) {
            sb.append(MaterialYou.s("Edited: ", "Изменено: ")).append(fmt(m.messageOwner.edit_date)).append('\n');
        }
        if (m.messageOwner.views > 0) {
            sb.append(MaterialYou.s("Views: ", "Просмотры: ")).append(m.messageOwner.views).append('\n');
        }
        if (m.messageOwner.forwards > 0) {
            sb.append(MaterialYou.s("Forwards: ", "Пересылки: ")).append(m.messageOwner.forwards).append('\n');
        }
        if (m.getGroupId() != 0) {
            sb.append(MaterialYou.s("Album: ", "Альбом: ")).append(m.getGroupId()).append('\n');
        }
        sb.append(MaterialYou.s("Type: ", "Тип: ")).append(m.type).append('\n');
        sb.append(MaterialYou.s("Outgoing: ", "Исходящее: ")).append(m.isOutOwner() ? "да" : "нет");
        final AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(MaterialYou.s("Message details", "Подробности сообщения"));
        b.setMessage(sb.toString());
        b.setPositiveButton("OK", null);
        b.setNegativeButton(MaterialYou.s("Copy ID", "Копировать ID"), (d, w) -> AndroidUtilities.addToClipboard(String.valueOf(m.getId())));
        b.show();
    }

    public static void showSnippets(Activity activity, Consumer<String> insert) {
        if (activity == null) {
            return;
        }
        final List<String> list = NullgramConfig.snippets();
        final ArrayList<CharSequence> items = new ArrayList<>();
        for (String s : list) {
            items.add(s.length() > 60 ? s.substring(0, 60) + "…" : s);
        }
        items.add(MaterialYou.s("＋ New snippet", "＋ Новый шаблон"));
        if (!list.isEmpty()) {
            items.add(MaterialYou.s("🗑 Delete a snippet…", "🗑 Удалить шаблон…"));
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(MaterialYou.s("Snippets", "Шаблоны"));
        b.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            if (which < list.size()) {
                insert.accept(list.get(which));
            } else if (which == list.size()) {
                addSnippet(activity);
            } else {
                deleteSnippet(activity);
            }
        });
        b.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
        b.show();
    }

    private static void addSnippet(Activity activity) {
        final EditText edit = new EditText(activity);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setHint(MaterialYou.s("Text of the snippet", "Текст шаблона"));
        edit.setMinLines(2);
        final AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(MaterialYou.s("New snippet", "Новый шаблон"));
        b.setView(edit);
        b.setPositiveButton(MaterialYou.s("Save", "Сохранить"), (d, w) -> {
            final String text = edit.getText().toString().trim();
            if (!text.isEmpty()) {
                final List<String> list = NullgramConfig.snippets();
                list.add(text);
                NullgramConfig.setSnippets(list);
            }
        });
        b.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
        b.show();
    }

    private static void deleteSnippet(Activity activity) {
        final List<String> list = NullgramConfig.snippets();
        final CharSequence[] items = new CharSequence[list.size()];
        for (int i = 0; i < items.length; i++) {
            final String s = list.get(i);
            items[i] = s.length() > 60 ? s.substring(0, 60) + "…" : s;
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(MaterialYou.s("Delete which?", "Какой удалить?"));
        b.setItems(items, (d, which) -> {
            list.remove(which);
            NullgramConfig.setSnippets(list);
        });
        b.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
        b.show();
    }

    public static void exportChat(Activity activity, List<MessageObject> messages, String title) {
        if (activity == null || messages == null || messages.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(title == null ? "" : title).append('\n').append("—\n");
        final int count = Math.min(messages.size(), 500);
        for (int i = count - 1; i >= 0; i--) {
            final MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null) {
                continue;
            }
            final CharSequence text = m.messageText != null && m.messageText.length() > 0 ? m.messageText : m.messageOwner.message;
            sb.append('[').append(fmt(m.messageOwner.date)).append("] ")
                .append(m.isOutOwner() ? MaterialYou.s("me", "я") : String.valueOf(m.getFromChatId()))
                .append(": ").append(text == null ? "" : text).append('\n');
        }
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        activity.startActivity(Intent.createChooser(send, MaterialYou.s("Export chat", "Экспорт чата")));
    }
}
