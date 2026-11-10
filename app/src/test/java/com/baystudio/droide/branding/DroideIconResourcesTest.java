package com.baystudio.droide.branding;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Build;
import com.baystudio.droide.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Pixel checks use Android's native renderer, not a generated preview. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DroideIconResourcesTest {
    private Drawable drawable(int id) {
        Drawable result = RuntimeEnvironment.getApplication().getDrawable(id);
        assertNotNull(result);
        return result;
    }

    private Bitmap render(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test public void manifestUsesDroideLauncher() {
        assertEquals(R.mipmap.ic_launcher, RuntimeEnvironment.getApplication().getApplicationInfo().icon);
    }

    @Test public void bothLauncherEntriesInflateAdaptiveLayers() {
        for (int id : new int[]{R.mipmap.ic_launcher, R.mipmap.ic_launcher_round}) {
            Drawable icon = drawable(id);
            assertTrue(icon instanceof AdaptiveIconDrawable);
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
            assertNotNull(adaptive.getBackground());
            assertTrue(adaptive.getForeground() instanceof VectorDrawable);
            if (Build.VERSION.SDK_INT >= 33) assertNotNull(adaptive.getMonochrome());
        }
    }

    @Test public void monochromeAndColorUseIdenticalTransparentShape() {
        Bitmap color = render(drawable(R.drawable.ic_droide_foreground), 432);
        Bitmap mono = render(drawable(R.drawable.ic_droide_monochrome), 432);
        int visible = 0;
        for (int y = 0; y < 432; y++) for (int x = 0; x < 432; x++) {
            int pixel = mono.getPixel(x, y);
            int alpha = Color.alpha(pixel);
            assertEquals(Color.alpha(color.getPixel(x, y)), alpha);
            if (alpha > 0) {
                visible++;
                assertEquals(255, Color.red(pixel));
                assertEquals(255, Color.green(pixel));
                assertEquals(255, Color.blue(pixel));

                assertTrue(Math.hypot((x + .5) / 4 - 54, (y + .5) / 4 - 54) < 33.3);
            }
        }
        assertTrue(visible > 15000);
        assertEquals(0, mono.getPixel(0, 0));
    }

    @Test public void smallIconIsWhiteSilhouetteWithoutSquareBackground() {
        Drawable drawable = drawable(R.drawable.ic_stat_droide);
        assertTrue(drawable instanceof VectorDrawable);
        Bitmap icon = render(drawable, 96);
        int visible = 0, transparentInside = 0;
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
            int pixel = icon.getPixel(x, y);
            if (Color.alpha(pixel) > 0) {
                visible++;
                assertEquals(255, Color.red(pixel));
                assertEquals(255, Color.green(pixel));
                assertEquals(255, Color.blue(pixel));
                assertTrue(x >= 7 && x <= 88 && y >= 7 && y <= 88);
            } else if (x > 32 && x < 64 && y > 32 && y < 64) transparentInside++;
        }
        assertTrue(visible > 2000);
        assertTrue(visible < 6000);
        assertTrue(transparentInside > 100);
        assertEquals(0, icon.getPixel(0, 0));
    }

    @Test public void smallIconAcceptsLightAndDarkSystemTint() {
        for (int tint : new int[]{Color.rgb(35, 55, 89), Color.rgb(210, 225, 255)}) {
            Drawable icon = drawable(R.drawable.ic_stat_droide).mutate();
            icon.setTint(tint);
            Bitmap rendered = render(icon, 96);
            boolean opaqueFound = false;
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                int pixel = rendered.getPixel(x, y);
                if (Color.alpha(pixel) == 255) {
                    opaqueFound = true;
                    assertEquals(tint, pixel);
                }
            }
            assertTrue(opaqueFound);
            assertEquals(0, rendered.getPixel(0, 0));
        }
    }
}
