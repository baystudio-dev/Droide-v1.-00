package com.baystudio.droide.branding;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import com.baystudio.droide.R;
import com.baystudio.droide.core.DroideNotificationIconsKt;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Verify the payload sent to Android, including parcel/resource loading across the process boundary.
 * SystemUI placement and OEM app-icon caches still require device acceptance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DroideNotificationIconsTest {
    private Context context() { return RuntimeEnvironment.getApplication(); }

    private Notification.Builder builder() {
        return DroideNotificationIconsKt.withDroideIcons(
            new Notification.Builder(context(), "branding-test"), context());
    }

    private void assertResource(Icon icon, int expectedId) {
        assertNotNull(icon);
        assertEquals(Icon.TYPE_RESOURCE, icon.getType());
        assertEquals(context().getPackageName(), icon.getResPackage());
        assertEquals(expectedId, icon.getResId());
        assertNotNull(icon.loadDrawable(context()));
    }

    @Test public void notificationSuppliesBothSlotsWithoutSystemDefaultIcons() {
        Notification notification = builder().build();
        assertResource(notification.getSmallIcon(), R.drawable.ic_stat_droide);
        assertResource(notification.getLargeIcon(), R.mipmap.ic_launcher);
    }

    private Bitmap render(Drawable drawable) {
        assertNotNull(drawable);
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 96, 96);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void assertSamePixels(Icon icon, int expectedId) {
        Bitmap actual = render(icon.loadDrawable(context()));
        Bitmap expected = render(context().getDrawable(expectedId));
        try { assertTrue(actual.sameAs(expected)); }
        finally { actual.recycle(); expected.recycle(); }
    }

    @Test public void parcelledNotificationLoadsTheUnchangedDroideArtwork() {
        Parcel parcel = Parcel.obtain();
        try {
            builder().build().writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Notification restored = Notification.CREATOR.createFromParcel(parcel);
            assertResource(restored.getSmallIcon(), R.drawable.ic_stat_droide);
            assertResource(restored.getLargeIcon(), R.mipmap.ic_launcher);
            assertSamePixels(restored.getSmallIcon(), R.drawable.ic_stat_droide);
            assertSamePixels(restored.getLargeIcon(), R.mipmap.ic_launcher);
        } finally { parcel.recycle(); }
    }

    @Test public void refreshingBrandingPreservesNotificationControlsAndContent() {
        PendingIntent open = PendingIntent.getActivity(context(), 1,
            new Intent("droide.test.OPEN").setPackage(context().getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = builder().setContentTitle("Droide developer session")
            .setContentText("Package installing").setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, 42, false)
            .addAction(new Notification.Action.Builder(null, "Exit", open).build());
        Notification notification = DroideNotificationIconsKt.withDroideIcons(builder, context()).build();
        assertEquals("branding-test", notification.getChannelId());
        assertEquals("Droide developer session", notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Package installing", notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS));
        assertEquals(open, notification.contentIntent);
        assertEquals(1, notification.actions.length);
        assertEquals("Exit", notification.actions[0].title);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertResource(notification.getSmallIcon(), R.drawable.ic_stat_droide);
        assertResource(notification.getLargeIcon(), R.mipmap.ic_launcher);
    }
}
