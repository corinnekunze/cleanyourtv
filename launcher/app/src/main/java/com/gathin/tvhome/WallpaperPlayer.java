package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays and rotates a theme's local wallpaper images while Home is visible, or, with photo_source
 * picture_window, the Picture Window app's current photo with its credit, falling back to the theme's pictures.
 */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** photo_source picture_window: the app's photo replaces the theme's pictures while it can be read. */
    private final boolean photoSource;
    private final ContentResolver resolver;
    private final PackageManager packages;
    /** "Photo by %1$s". */
    private final String photoBy;
    private final int backgroundColor;
    /** The Picture Window photo on screen, or null while the theme's pictures (or the plain colour) show. */
    private String photoId;
    private String photoCaption;
    /** When to ask the provider again (wall clock); 0 asks on the next update. */
    private long photoCheckAt;
    /** Just after the photo's next_change_at, so Home changes with the screensaver. */
    private final Runnable photoTick = () -> {
        photoCheckAt = 0;
        update();
    };

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        photoSource = PictureWindow.SOURCE.equals(config.photoSource);
        resolver = context.getContentResolver();
        packages = context.getPackageManager();
        photoBy = context.getString(R.string.photo_by);
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        // The credit always shows with the photo: its licence asks for attribution.
        caption = names.isEmpty() && !photoSource ? null : makeCaption(context, root, photoSource ? 3 : 1);
        if (photoSource) {
            // The photo is read and decoded off the main thread from start(): asking another app's provider here
            // could wait on that app's process start. Until it arrives, Home shows the plain colour.
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        photoCheckAt = 0;   // every return to Home asks the provider again
        update();
    }

    void stop() {
        active = false;
        root.removeCallbacks(photoTick);
    }

    void close() {
        active = false;
        closed = true;
        root.removeCallbacks(photoTick);
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading) return;
        if (photoSource && System.currentTimeMillis() >= photoCheckAt) {
            loadPhoto();
            return;
        }
        if (photoId != null) return;   // the photo stays until its next_change_at
        updateTheme();
    }

    private void updateTheme() {
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    if (photoSource && files.isEmpty() && displayed.isEmpty()) showPlainColour();
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** Asks Picture Window for its photo and decodes it off the main thread; the same id is not decoded again. */
    private void loadPhoto() {
        loading = true;
        String shownId = photoId;
        decoder.execute(() -> {
            PictureWindow.Photo photo = PictureWindow.current(resolver, packages);
            Bitmap bitmap = photo == null || photo.id.equals(shownId) ? null : decodePhoto(photo);
            root.post(() -> photoLoaded(photo, bitmap));
        });
    }

    private void photoLoaded(PictureWindow.Photo photo, Bitmap bitmap) {
        loading = false;
        if (closed || !active) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        long now = System.currentTimeMillis();
        boolean same = photo != null && photo.id.equals(photoId);
        if (!same && bitmap == null) {
            // No app, no photo yet, or it would not open or decode: the theme's pictures until the next try.
            long retry = photo == null ? PictureWindow.RETRY_MS
                    : Math.min(PictureWindow.RETRY_MS, PictureWindow.refreshDelay(photo.nextChangeAt, now));
            photoCheckAt = now + retry;
            leavePhoto();
            update();
            return;
        }
        long delay = PictureWindow.refreshDelay(photo.nextChangeAt, now);
        photoCheckAt = now + delay;
        root.removeCallbacks(photoTick);
        root.postDelayed(photoTick, delay);
        if (same) return;
        show(bitmap);
        root.setBackground(null);
        photoId = photo.id;
        photoCaption = PictureWindow.caption(photoBy, photo.credit, photo.place);
        displayed = "";   // a later fallback decodes the theme's picture again
        showCaption(true);
    }

    /** Back from the photo to the theme's pictures, which update() decodes, or to the plain colour without any. */
    private void leavePhoto() {
        if (photoId == null) return;
        photoId = null;
        photoCaption = null;
        displayed = "";
        showCaption(false);
        if (files.isEmpty()) showPlainColour();
    }

    private void showPlainColour() {
        root.setBackgroundColor(backgroundColor);
        show(null);
    }

    private Bitmap decodePhoto(PictureWindow.Photo photo) {
        try (ParcelFileDescriptor file = PictureWindow.open(resolver, photo)) {
            if (file == null) return null;
            FileDescriptor descriptor = file.getFileDescriptor();
            // decodeFileDescriptor leaves the position alone, so the bounds pass and the decode read the same file.
            return decode(options -> BitmapFactory.decodeFileDescriptor(descriptor, null, options));
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root, int lines) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        if (lines == 1) {
            text.setSingleLine(true);
        } else {
            text.setMaxLines(lines);   // a long credit, then the place
        }
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = photoId != null ? photoCaption : names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture (or none) on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    /** Reads a picture with the given options: a theme file, or the Picture Window photo's descriptor. */
    private interface Source {
        Bitmap read(BitmapFactory.Options options);
    }

    private Bitmap decode(File file) {
        return decode(options -> BitmapFactory.decodeFile(file.getPath(), options));
    }

    private Bitmap decode(Source source) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            source.read(options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = source.read(options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
