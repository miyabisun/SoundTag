package dev.miyabisun.soundtag

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class YouTubeRecoveryTest {
    @Test fun restoresOnlyPlayingYouTube() {
        assertTrue(shouldRestoreYouTube("com.google.android.youtube", PlaybackState.STATE_PLAYING))
        for (state in listOf(null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_ERROR)) {
            assertFalse(shouldRestoreYouTube("com.google.android.youtube", state))
        }
        assertFalse(shouldRestoreYouTube("com.google.android.apps.youtube.music", PlaybackState.STATE_PLAYING))
        assertFalse(shouldRestoreYouTube("other.player", PlaybackState.STATE_PLAYING))
    }
}
