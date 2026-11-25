package it.reno.lab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.Random;

public final class NightWallpaper extends WallpaperService {
    @Override public Engine onCreateEngine() { return new NightEngine(); }

    private final class NightEngine extends Engine {
        private final Handler clock = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Runnable frame = this::render;
        private final float[][] stars = new float[90][4];
        private Bitmap background;
        private boolean visible;
        private int lastWidth;
        private int lastHeight;

        NightEngine() {
            Random rng = new Random(2315);
            for (float[] star : stars) {
                star[0] = rng.nextFloat();
                star[1] = rng.nextFloat() * .56f;
                star[2] = rng.nextFloat();
                star[3] = rng.nextFloat() * 8;
            }
        }

        @Override public void onVisibilityChanged(boolean shown) {
            visible = shown;
            clock.removeCallbacks(frame);
            if (visible) render();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            render();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            clock.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            clock.removeCallbacks(frame);
            if (background != null) background.recycle();
            super.onDestroy();
        }

        private void render() {
            clock.removeCallbacks(frame);
            SurfaceHolder holder = getSurfaceHolder();
            if (!holder.getSurface().isValid()) return;
            Canvas screen = null;
            try {
                screen = holder.lockHardwareCanvas();
                if (screen == null) return;
                int width = screen.getWidth();
                int height = screen.getHeight();
                if (background == null || width != lastWidth || height != lastHeight) build(width, height);
                screen.scale((float) width / background.getWidth(), (float) height / background.getHeight());
                screen.drawBitmap(background, 0, 0, paint);
                int w = background.getWidth();
                int h = background.getHeight();
                float time = SystemClock.elapsedRealtime() % 180000L / 1000f;
                for (float[] star : stars) {
                    float brightness = .50f + .40f * (float) Math.sin(time * .8f + star[3]);
                    paint.setColor(Color.argb((int) ((50 + star[2] * 155) * brightness), 217, 227, 255));
                    screen.drawCircle(star[0] * w, star[1] * h, .50f + star[2] * 1.15f, paint);
                }
                paint.setColor(Color.rgb(167, 162, 226));
                paint.setAlpha(24);
                for (int wave = 0; wave < 3; wave++) {
                    float x = (float) Math.sin(time * .13f + wave) * w * .13f;
                    float y = h * (.70f + wave * .039f);
                    screen.drawOval(x - w * .08f, y, x + w * 1.12f, y + h * .004f, paint);
                }
                paint.setAlpha(255);
            } catch (IllegalArgumentException | IllegalStateException transientSurface) {
                // Surfaces can change as the launcher opens or rotates.
            } finally {
                if (screen != null) holder.unlockCanvasAndPost(screen);
                if (visible) clock.postDelayed(frame, 86);
            }
        }

        private void build(int width, int height) {
            lastWidth = width;
            lastHeight = height;
            if (background != null) background.recycle();
            float scale = Math.min(1f, 1200f / Math.max(width, height));
            int w = Math.max(1, Math.round(width * scale));
            int h = Math.max(1, Math.round(height * scale));
            background = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(background);
            paint.setColor(Color.WHITE);
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xff080c1f, 0xff25214e, 0xff6c426d, 0xff10182d},
                    new float[]{0f, .48f, .75f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            float moonX = w * .66f;
            float moonY = h * .30f;
            float moon = Math.min(w, h) * .123f;
            paint.setShader(new RadialGradient(moonX, moonY, moon * 2.95f,
                    new int[]{0x7ea7b9ff, 0x25a7b9ff, 0x00a7b9ff},
                    new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0xffb9c9ff);
            canvas.drawCircle(moonX, moonY, moon, paint);
            Path clip = new Path();
            clip.addCircle(moonX, moonY, moon, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setColor(0xff777cae);
            for (int i = 0; i < 6; i++) {
                float top = moonY + moon * (.13f + .14f * i);
                canvas.drawRect(moonX - moon, top, moonX + moon, top + moon * .036f, paint);
            }
            canvas.restore();

            ridge(canvas, w, h, 0xff383768,
                    new float[]{.71f,.65f,.67f,.60f,.63f,.55f,.62f,.58f,.68f,.62f,.70f,.64f,.71f});
            ridge(canvas, w, h, 0xff252b53,
                    new float[]{.79f,.76f,.74f,.78f,.70f,.72f,.74f,.72f,.68f,.75f,.69f,.73f,.76f});
            ridge(canvas, w, h, 0xff172341,
                    new float[]{.84f,.80f,.84f,.78f,.80f,.84f,.79f,.83f,.78f,.83f,.79f,.83f,.85f});
            ridge(canvas, w, h, 0xff0b132a,
                    new float[]{.93f,.87f,.90f,.84f,.88f,.92f,.89f,.93f,.88f,.91f,.87f,.92f,.92f});
            paint.setColor(0xff0b132a);
            Random rng = new Random(224);
            for (int i = 0; i < 26; i++) {
                float x = rng.nextFloat() * w;
                float low = h * (.91f + rng.nextFloat() * .035f);
                float size = h * (.012f + rng.nextFloat() * .034f);
                for (int limb = 0; limb < 3; limb++) {
                    float top = low - size * (1f - limb * .22f);
                    Path pine = new Path();
                    pine.moveTo(x, top);
                    pine.lineTo(x + size * (.16f + limb * .045f), top + size * .52f);
                    pine.lineTo(x - size * (.16f + limb * .045f), top + size * .52f);
                    pine.close();
                    canvas.drawPath(pine, paint);
                }
            }
            paint.setColor(Color.WHITE);
        }

        private void ridge(Canvas canvas, int w, int h, int color, float[] points) {
            paint.setColor(color);
            Path path = new Path();
            path.moveTo(0, points[0] * h);
            for (int i = 1; i < points.length; i++) path.lineTo((float) w * i / (points.length - 1), points[i] * h);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            canvas.drawPath(path, paint);
        }
    }
}
