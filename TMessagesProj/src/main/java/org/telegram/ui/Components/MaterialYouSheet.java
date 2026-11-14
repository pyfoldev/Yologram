package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.WallpapersListActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.messenger.WsProxy;
import org.telegram.messenger.GhostMode;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;

public class MaterialYouSheet extends BottomSheet {

    private final Swatch[] swatches = new Swatch[MaterialYou.PRESETS.length];

    public MaterialYouSheet(Context context) {
        super(context, false);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        root.addView(title(context, "Material You"));

        root.addView(label(context, MaterialYou.s("Color", "Цвет")));
        final int perRow = 6;
        for (int start = 0; start < MaterialYou.PRESETS.length; start += perRow) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = start; i < Math.min(start + perRow, MaterialYou.PRESETS.length); i++) {
                final int color = MaterialYou.PRESETS[i];
                final Swatch s = new Swatch(context, color);
                swatches[i] = s;
                s.setOnClickListener(v -> {
                    MaterialYou.setSeed(color);
                    refreshSelection();
                });
                row.addView(s, LayoutHelper.createLinear(0, 48, 1f, Gravity.CENTER, 0, 4, 0, 4));
            }
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        refreshSelection();

        root.addView(label(context, MaterialYou.s("Roundness", "Скругление")));
        final SeekBarView seek = new SeekBarView(context);
        seek.setReportChanges(true);
        seek.setProgress((MaterialYou.getCornerScale() - 0.3f) / 1.3f);
        seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (stop) {
                    MaterialYou.setCornerScale(0.3f + 1.3f * progress);
                }
            }
        });
        root.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, 0, 0, 0));
        root.addView(hint(context, MaterialYou.s("Applies to new dialogs and buttons", "Применяется к новым окнам и кнопкам")));

        final TextCheckCell amoled = new TextCheckCell(context);
        amoled.setTextAndCheck(MaterialYou.s("AMOLED black", "AMOLED-чёрный"), MaterialYou.isAmoled(), false);
        amoled.setOnClickListener(v -> {
            final boolean value = !MaterialYou.isAmoled();
            amoled.setChecked(value);
            MaterialYou.setAmoled(value);
        });
        root.addView(amoled, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        root.addView(label(context, MaterialYou.s("Privacy", "Приватность")));
        addSwitch(context, root, MaterialYou.s("Ghost mode", "Режим призрака"), GhostMode.isOn(), GhostMode::setOn);
        root.addView(hint(context, MaterialYou.s("Hides online status, read receipts, typing and story views", "Скрывает «в сети», прочтение, набор текста и просмотры историй")));
        addSwitch(context, root, MaterialYou.s("Keep deleted messages", "Сохранять удалённые сообщения"), GhostMode.isKeepDeleted(), GhostMode::setKeepDeleted);
        root.addView(hint(context, MaterialYou.s("Messages the other side deletes stay in the chat, marked \"deleted\"", "Удалённые собеседником сообщения остаются в чате с пометкой «удалено»")));

        root.addView(label(context, MaterialYou.s("Connection", "Соединение")));
        addSwitch(context, root, MaterialYou.s("WS proxy", "WS-прокси"), WsProxy.isEnabled(), WsProxy::setEnabled);
        root.addView(hint(context, MaterialYou.s("Carries Telegram over a WebSocket to web.telegram.org; helps when plain connection is blocked", "Пускает Telegram через WebSocket к web.telegram.org; помогает, когда обычное соединение блокируют")));

        final org.telegram.tgnet.TLRPC.User me = org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getCurrentUser();
        if (org.telegram.messenger.CreatorBadge.isCreator(me)) {
            root.addView(label(context, MaterialYou.s("Extra usernames (visual only)", "Доп. юзернеймы (только визуально)")));
            final android.widget.EditText extras = new android.widget.EditText(context);
            extras.setSingleLine(true);
            extras.setText(org.telegram.messenger.CreatorBadge.getExtrasText());
            extras.setHint("nullgram, pyfold_dev");
            extras.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            extras.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
            extras.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    org.telegram.messenger.CreatorBadge.setExtrasText(s.toString());
                }
            });
            root.addView(extras, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            root.addView(hint(context, MaterialYou.s("Shown on your profile in this app only; Telegram and other people don't see them", "Показываются на твоём профиле только в этом приложении; Telegram и другие люди их не видят")));
        }

        root.addView(label(context, MaterialYou.s("Chat wallpaper", "Обои чата")));
        final TextView wallpaper = new TextView(context);
        wallpaper.setText(MaterialYou.s("Choose wallpaper…", "Выбрать обои…"));
        wallpaper.setTextSize(16);
        wallpaper.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        wallpaper.setPadding(0, dp(10), 0, dp(10));
        wallpaper.setOnClickListener(v -> {
            dismiss();
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment != null) {
                fragment.presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
            }
        });
        root.addView(wallpaper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell enabled = new TextCheckCell(context);
        enabled.setTextAndCheck(MaterialYou.s("Material You design", "Дизайн Material You"), MaterialYou.isEnabled(), false);
        enabled.setOnClickListener(v -> {
            final boolean value = !MaterialYou.isEnabled();
            enabled.setChecked(value);
            MaterialYou.setEnabled(value);
        });
        root.addView(enabled, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        setCustomView(root);
    }

    private static void addSwitch(Context context, LinearLayout root, String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        final TextCheckCell cell = new TextCheckCell(context);
        cell.setTextAndCheck(text, initial, false);
        cell.setOnClickListener(v -> {
            final boolean value = !cell.isChecked();
            cell.setChecked(value);
            onChange.accept(value);
        });
        root.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
    }

    private void refreshSelection() {
        for (Swatch s : swatches) {
            if (s != null) {
                s.setSelectedColor(MaterialYou.getSeed());
            }
        }
    }

    private static TextView title(Context c, String text) {
        final TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(22);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private static TextView label(Context c, String text) {
        final TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private static TextView hint(Context c, String text) {
        final TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private static class Swatch extends View {
        private final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private boolean selected;

        Swatch(Context context, int color) {
            super(context);
            this.color = color;
            fill.setColor(color);
            check.setColor(0xFFFFFFFF);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeWidth(dp(2.5f));
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
        }

        void setSelectedColor(int seed) {
            final boolean now = (seed | 0xFF000000) == (color | 0xFF000000);
            if (now != selected) {
                selected = now;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float r = Math.min(getWidth(), getHeight()) / 2f - dp(2);
            canvas.drawCircle(cx, cy, r, fill);
            if (selected) {
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.38f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.08f, cy + r * 0.32f);
                checkPath.lineTo(cx + r * 0.42f, cy - r * 0.26f);
                canvas.drawPath(checkPath, check);
            }
        }
    }
}
