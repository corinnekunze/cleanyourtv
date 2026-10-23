package com.gathin.tvhome;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/**
 * photo_source "picture_window": the photo on screen in the Picture Window app (com.mtdetc.picturewindow), read
 * from its provider so Home and its screensaver show the same picture with the same credit. The provider answers
 * {@code content://com.mtdetc.picturewindow.photos/current} with no row (nothing downloaded yet) or one row, and
 * opens that row's {@code image_uri} read-only. The static helpers have no Android types and are tested on the JVM.
 */
final class PictureWindow {
    static final String SOURCE = "picture_window";
    static final String PACKAGE = "com.mtdetc.picturewindow";
    static final String AUTHORITY = "com.mtdetc.picturewindow.photos";
    /** After next_change_at, so the provider has moved on when it is asked. */
    static final long SLACK_MS = 250;
    /** A next_change_at already past (clock change, odd row): ask again soon, but not in a loop. */
    static final long MIN_DELAY_MS = 5_000;
    static final long MAX_DELAY_MS = 24 * 60 * 60_000L;
    /** After no photo or a failure, the next try while Home stays visible; every return to Home tries too. */
    static final long RETRY_MS = 15 * 60_000L;
    static final int MAX_LENGTH = 80;

    /** One row of /current; credit and place are null when the app has none to show. */
    static final class Photo {
        final String id;
        final String imageUri;
        final long nextChangeAt;
        final String credit;
        final String place;

        Photo(String id, String imageUri, long nextChangeAt, String credit, String place) {
            this.id = id;
            this.imageUri = imageUri;
            this.nextChangeAt = nextChangeAt;
            this.credit = credit;
            this.place = place;
        }
    }

    private PictureWindow() { }

    /** A known photo_source, or "" (the theme's own wallpapers, the default) for anything else. */
    static String source(String value) {
        return value != null && SOURCE.equalsIgnoreCase(value.trim()) ? SOURCE : "";
    }

    /**
     * "Photo by credit" and the place under it, as the screensaver shows them; either line is left out when the app
     * has none, and null means no caption. Never built from the raw author: the app withholds names it could not
     * clean, and then there is no credit line.
     */
    static String caption(String photoBy, String credit, String place) {
        String name = clean(credit), where = clean(place);
        String byLine = name == null ? null : String.format(photoBy, name);
        if (byLine == null) return where;
        return where == null ? byLine : byLine + "\n" + where;
    }

    /** Milliseconds until the provider should be asked again: just after next_change_at, within sane bounds. */
    static long refreshDelay(long nextChangeAt, long now) {
        if (nextChangeAt <= now) return MIN_DELAY_MS;
        long delay = nextChangeAt - now;
        if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS;
        return Math.max(MIN_DELAY_MS, delay + SLACK_MS);
    }

    /** Only the provider's own photo URIs are opened, whatever the row says. */
    static boolean trustedImageUri(String uri) {
        String prefix = "content://" + AUTHORITY + "/photos/";
        return uri != null && uri.startsWith(prefix) && uri.length() > prefix.length()
                && uri.indexOf('/', prefix.length()) < 0 && !uri.contains("..");
    }

    private static String clean(String text) {
        if (text == null) return null;
        String trimmed = text.replace('\n', ' ').trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH).trim() : trimmed;
    }

    /** True when the authority belongs to the Picture Window app, so no other app can stand in for it. */
    static boolean installed(PackageManager packages) {
        ProviderInfo provider = packages.resolveContentProvider(AUTHORITY, 0);
        return provider != null && PACKAGE.equals(provider.packageName);
    }

    /**
     * The current photo, or null when the app is missing, has none yet, or anything goes wrong. An unstable client:
     * if the app dies or is updated mid-call this gets an exception instead of Home being taken down with it.
     */
    static Photo current(ContentResolver resolver, PackageManager packages) {
        ContentProviderClient client = null;
        Cursor cursor = null;
        try {
            if (!installed(packages)) return null;
            client = resolver.acquireUnstableContentProviderClient(AUTHORITY);
            if (client == null) return null;
            cursor = client.query(Uri.parse("content://" + AUTHORITY + "/current"), null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return null;
            String id = text(cursor, "id");
            String image = text(cursor, "image_uri");
            int next = cursor.getColumnIndex("next_change_at");
            if (id == null || id.isEmpty() || !trustedImageUri(image) || next < 0 || cursor.isNull(next)) return null;
            return new Photo(id, image, cursor.getLong(next), text(cursor, "credit"), text(cursor, "place"));
        } catch (Exception failure) {   // SecurityException, IllegalArgumentException, RemoteException, ...
            return null;
        } finally {
            if (cursor != null) cursor.close();
            if (client != null) client.close();
        }
    }

    /** The photo file, read-only, through an unstable client; the caller closes it. Null on any failure. */
    static ParcelFileDescriptor open(ContentResolver resolver, Photo photo) {
        if (!trustedImageUri(photo.imageUri)) return null;
        try (ContentProviderClient client = resolver.acquireUnstableContentProviderClient(AUTHORITY)) {
            return client == null ? null : client.openFile(Uri.parse(photo.imageUri), "r");
        } catch (Exception failure) {   // FileNotFoundException, SecurityException, RemoteException, ...
            return null;
        }
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }
}
