package com.raafi.cyberhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders the real Home view into a bitmap for the web remote. This still works when ADB is not
 * connected, which gives the layout editor a live preview without extra permissions.
 */
final class PreviewBridge {

    private static WeakReference<MainActivity> current;

    private PreviewBridge() {
    }

    static void attach(MainActivity activity) {
        current = new WeakReference<>(activity);
    }

    static void detach(MainActivity activity) {
        MainActivity existing = current == null ? null : current.get();
        if (existing == activity) {
            current = null;
        }
    }

    static byte[] captureJpeg(final int maxWidth, final int quality) {
        final MainActivity activity = current == null ? null : current.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] holder = new Bitmap[1];
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    View root = activity.findViewById(R.id.root);
                    if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
                        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(),
                                root.getHeight(), Bitmap.Config.ARGB_8888);
                        root.draw(new Canvas(bitmap));
                        holder[0] = bitmap;
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(4, TimeUnit.SECONDS) || holder[0] == null) {
                return null;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }

        Bitmap source = holder[0];
        Bitmap scaled = source;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width > maxWidth) {
                int targetHeight = Math.max(1, Math.round(height * (maxWidth / (float) width)));
                scaled = Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output);
            return output.toByteArray();
        } catch (Throwable exception) {
            return null;
        } finally {
            if (scaled != source) {
                scaled.recycle();
            }
            source.recycle();
        }
    }
}
