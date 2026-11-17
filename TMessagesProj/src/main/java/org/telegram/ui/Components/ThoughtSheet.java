package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NullgramSync;
import org.telegram.messenger.Thoughts;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.MaterialYou;

/** Bottom sheet for writing a "thought": text field with counter, quick moods, save / remove. */
public class ThoughtSheet extends BottomSheet {

    private static final String[][] MOODS = {
        {"😊", "Хорошее настроение", "Good mood"},
        {"🔥", "В огне", "On fire"},
        {"💭", "Задумался", "Thinking"},
        {"🎧", "Слушаю музыку", "Listening to music"},
        {"📚", "Учусь", "Studying"},
        {"😴", "Хочу спать", "Sleepy"},
        {"✈️", "В пути", "On the road"},
    };

    private static GradientDrawable pill(int color, float radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(MaterialYou.radius(radiusDp));
        return d;
    }

    public ThoughtSheet(Context context, String current, Runnable onChanged) {
        super(context, true);
        final int onSurface = MaterialYou.role(MaterialYou.R_ON_SURFACE);
        final int variant = MaterialYou.role(MaterialYou.R_ON_SURFACE_VARIANT);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(22));

        final TextView title = new TextView(context);
        title.setText(MaterialYou.s("Your thought", "Твоя мысль"));
        title.setTextSize(22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(onSurface);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView sub = new TextView(context);
        final String left = current != null ? "  ·  " + MaterialYou.s("hours left: ", "осталось часов: ") + Thoughts.hoursLeft() : "";
        sub.setText(MaterialYou.s("Shown next to your avatar for 24 hours. Other Yologram users can see it.", "Показывается рядом с аватаркой 24 часа. Её видят другие пользователи Yologram.") + left);
        sub.setTextSize(14);
        sub.setTextColor(variant);
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 14));

        final EditText edit = new EditText(context);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setSingleLine(true);
        edit.setHint(MaterialYou.s("What's on your mind?", "О чём думаешь?"));
        edit.setHintTextColor(variant);
        edit.setTextColor(onSurface);
        edit.setTextSize(17);
        edit.setBackground(pill(MaterialYou.role(MaterialYou.R_SURFACE_HIGH), 18));
        edit.setPadding(dp(16), dp(14), dp(56), dp(14));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Thoughts.MAX_LENGTH)});
        if (current != null) {
            edit.setText(current);
            edit.setSelection(current.length());
        }
        final TextView counter = new TextView(context);
        counter.setTextSize(12);
        counter.setTextColor(variant);
        final android.widget.FrameLayout field = new android.widget.FrameLayout(context);
        field.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        field.addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 16, 0));
        root.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final Runnable updateCounter = () -> counter.setText(edit.getText().length() + "/" + Thoughts.MAX_LENGTH);
        updateCounter.run();
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateCounter.run();
            }
        });

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final boolean ruLang = !MaterialYou.s("a", "b").equals("a");
        for (String[] m : MOODS) {
            final String label = m[0] + " " + (ruLang ? m[1] : m[2]);
            final TextView chip = new TextView(context);
            chip.setText(label);
            chip.setTextSize(14);
            chip.setSingleLine(true);
            chip.setTextColor(MaterialYou.role(MaterialYou.R_ON_SECONDARY_CONTAINER));
            chip.setBackground(pill(MaterialYou.role(MaterialYou.R_SECONDARY_CONTAINER), 14));
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setOnClickListener(v -> {
                final String t = label.length() > Thoughts.MAX_LENGTH ? label.substring(0, Thoughts.MAX_LENGTH) : label;
                edit.setText(t);
                edit.setSelection(t.length());
            });
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        scroll.addView(chips);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        if (current != null) {
            final TextView remove = button(context, MaterialYou.s("Remove", "Убрать"), MaterialYou.role(MaterialYou.R_ERROR_CONTAINER), MaterialYou.role(MaterialYou.R_ERROR));
            remove.setOnClickListener(v -> {
                Thoughts.clear();
                NullgramSync.pushThought(null);
                dismiss();
                if (onChanged != null) onChanged.run();
            });
            buttons.addView(remove, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 10, 0));
        }
        final TextView save = button(context, MaterialYou.s("Save", "Сохранить"), MaterialYou.role(MaterialYou.R_PRIMARY), MaterialYou.role(MaterialYou.R_ON_PRIMARY));
        save.setOnClickListener(v -> {
            Thoughts.set(edit.getText().toString());
            NullgramSync.pushThought(Thoughts.get());
            dismiss();
            if (onChanged != null) onChanged.run();
        });
        buttons.addView(save, LayoutHelper.createLinear(0, 48, 1f));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        setCustomView(root);
        AndroidUtilities.runOnUIThread(() -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        }, 250);
    }

    private static TextView button(Context context, String text, int bg, int fg) {
        final TextView b = new TextView(context);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(16);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(fg);
        b.setBackground(pill(bg, 24));
        return b;
    }
}
