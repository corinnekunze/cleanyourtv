package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** photo_source picture_window: the key's value, the caption under the buttons and when to ask again. */
public class PictureWindowTest {
    private static final String PHOTO_BY = "Photo by %1$s";
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void pictureWindowByNameAnyCase() {
        assertEquals(PictureWindow.SOURCE, PictureWindow.source("picture_window"));
        assertEquals(PictureWindow.SOURCE, PictureWindow.source(" Picture_Window "));
    }

    @Test
    public void anythingElseKeepsTheThemesPictures() {
        assertEquals("", PictureWindow.source(""));
        assertEquals("", PictureWindow.source(null));
        assertEquals("", PictureWindow.source("theme"));
        assertEquals("", PictureWindow.source("picture-window"));
    }

    @Test
    public void creditThenPlace() {
        assertEquals("Photo by Jane Doe\nKyoto, Japan", PictureWindow.caption(PHOTO_BY, "Jane Doe", "Kyoto, Japan"));
    }

    @Test
    public void noCreditShowsOnlyThePlace() {
        assertEquals("Kyoto, Japan", PictureWindow.caption(PHOTO_BY, null, "Kyoto, Japan"));
        assertEquals("Kyoto, Japan", PictureWindow.caption(PHOTO_BY, "   ", "Kyoto, Japan"));
    }

    @Test
    public void noPlaceShowsOnlyTheCredit() {
        assertEquals("Photo by Jane Doe", PictureWindow.caption(PHOTO_BY, "Jane Doe", null));
        assertEquals("Photo by Jane Doe", PictureWindow.caption(PHOTO_BY, " Jane Doe ", ""));
    }

    @Test
    public void neitherMeansNoCaption() {
        assertNull(PictureWindow.caption(PHOTO_BY, null, null));
        assertNull(PictureWindow.caption(PHOTO_BY, "", " "));
    }

    @Test
    public void captionLinesStayShort() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) longName.append("word ");
        String caption = PictureWindow.caption("%1$s", longName.toString(), null);
        assertTrue(caption.length() <= PictureWindow.MAX_LENGTH);
        assertEquals("One line", PictureWindow.caption("%1$s", "One\nline", null));
    }

    @Test
    public void pastChangeAsksAgainSoon() {
        assertEquals(PictureWindow.MIN_DELAY_MS, PictureWindow.refreshDelay(NOW - 60_000, NOW));
        assertEquals(PictureWindow.MIN_DELAY_MS, PictureWindow.refreshDelay(NOW, NOW));
        assertEquals(PictureWindow.MIN_DELAY_MS, PictureWindow.refreshDelay(0, NOW));
    }

    @Test
    public void futureChangeWaitsForItPlusSlack() {
        assertEquals(180_000 + PictureWindow.SLACK_MS, PictureWindow.refreshDelay(NOW + 180_000, NOW));
    }

    @Test
    public void imminentChangeStillWaitsTheMinimum() {
        assertEquals(PictureWindow.MIN_DELAY_MS, PictureWindow.refreshDelay(NOW + 100, NOW));
    }

    @Test
    public void farFutureIsCappedAtADay() {
        assertEquals(PictureWindow.MAX_DELAY_MS, PictureWindow.refreshDelay(Long.MAX_VALUE, NOW));
    }

    @Test
    public void onlyTheProvidersOwnPhotosAreOpened() {
        assertTrue(PictureWindow.trustedImageUri("content://com.mtdetc.picturewindow.photos/photos/abc-123"));
        assertFalse(PictureWindow.trustedImageUri(null));
        assertFalse(PictureWindow.trustedImageUri("content://com.mtdetc.picturewindow.photos/photos/"));
        assertFalse(PictureWindow.trustedImageUri("content://com.mtdetc.picturewindow.photos/photos/a/b"));
        assertFalse(PictureWindow.trustedImageUri("content://com.mtdetc.picturewindow.photos/photos/..x"));
        assertFalse(PictureWindow.trustedImageUri("content://com.example.other/photos/abc"));
        assertFalse(PictureWindow.trustedImageUri("file:///sdcard/abc.webp"));
    }
}
