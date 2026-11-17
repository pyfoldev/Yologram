package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Thoughts;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.MaterialYou;

public class ThoughtView extends View {

    private static final int MAX_PILL_DP = 200;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String text;
    private CharSequence shown = "";
    private float pillW;
    private boolean remote;
    private int maxPillPx = dp(MAX_PILL_DP);
    private String remoteText;

    public void setRemote(String t) {
        remote = true;
        remoteText = t;
        setClickable(false);
        update();
    }

    public ThoughtView(Context context) {
        super(context);
        textPaint.setTextSize(dp(15));
        textPaint.setTypeface(AndroidUtilities.bold());
        setOnClickListener(v -> edit());
        update();
    }

    public void update() {
        text = remote ? remoteText : Thoughts.get();
        final String label = text != null ? text : MaterialYou.s("＋ Thought", "＋ Мысль");
        shown = TextUtils.ellipsize(label, textPaint, maxPillPx - dp(28), TextUtils.TruncateAt.END);
        pillW = textPaint.measureText(shown, 0, shown.length()) + dp(28);
        requestLayout();
        invalidate();
    }

    public void follow(float right, float top, int parentWidth) {
        final float x = right - dp(22);
        final int avail = Math.max(dp(70), (int) (parentWidth - x - dp(30) - dp(12)));
        final int wanted = Math.min(avail, dp(MAX_PILL_DP));
        // only re-measure the text when the room changed noticeably, not on every scroll frame
        if (Math.abs(wanted - maxPillPx) >= dp(10) || (wanted == dp(MAX_PILL_DP) && maxPillPx != wanted)) {
            maxPillPx = wanted;
            update();
        }
        setTranslationX(x);
        setTranslationY(top + dp(16));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension((int) Math.ceil(pillW) + dp(30), dp(40) + dp(26));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float h = dp(40);
        rect.set(dp(30), 0, dp(30) + pillW, h);
        fill.setColor(text != null ? MaterialYou.role(MaterialYou.R_SURFACE_HIGHEST) : MaterialYou.role(MaterialYou.R_PRIMARY_CONTAINER));
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill);
        canvas.drawCircle(dp(24), h + dp(4), dp(5), fill);
        canvas.drawCircle(dp(12), h + dp(16), dp(3), fill);
        textPaint.setColor(text != null ? MaterialYou.role(MaterialYou.R_ON_SURFACE) : MaterialYou.role(MaterialYou.R_ON_PRIMARY_CONTAINER));
        canvas.drawText(shown, 0, shown.length(), rect.left + dp(14), rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
    }

    private void edit() {
        if (remote) {
            return;
        }
        new ThoughtSheet(getContext(), text, this::update).show();
    }
}
