package com.paperzone.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xxhdpi")
public class RenderTest {
    static void shot(GameView v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(1080, 2280, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(System.getProperty("shotdir", "/tmp") + "/" + name + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }
    @Test public void render() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        GameView v; java.lang.reflect.Field f = MainActivity.class.getDeclaredField("view"); f.setAccessible(true); v = (GameView) f.get(a);
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(2280, android.view.View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 1080, 2280);
        shot(v, "a_menu");
        v.scr = GameView.Scr.CHARS; shot(v, "b_chars");
        v.scr = GameView.Scr.LEVELS; shot(v, "c_levels");
        v.startGame(false, 2); shot(v, "d_start");
        v.eng.player.ai = true; v.eng.start();
        for (int i = 0; i < 30 * 50; i++) { v.eng.update(1 / 30f); v.eng.events.clear(); }
        v.camX = v.eng.player.x; v.camY = v.eng.player.y; v.refreshBitmap(true); v.started = true;
        shot(v, "e_game");
        for (int i = 0; i < 40; i++) { v.eng.update(1 / 30f); v.eng.events.clear(); }
        v.camX = v.eng.player.x; v.camY = v.eng.player.y; v.refreshBitmap(true);
        shot(v, "f_game2");
        v.pause(); shot(v, "g_pause");
    }
}
