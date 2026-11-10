package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class PlayerTest {
    @Test fun privateAndEmbeddingRestrictedVideosOfferYouTubeInsteadOfEndlessRetry() {
        for(code in listOf(100,101,150)) {assertFalse(playerFailure(code).retryable);assertTrue(playerFailure(code).openYouTube)}
        assertTrue(playerFailure(100).message.contains("private"))
        assertTrue(playerFailure(5).retryable)
        assertEquals("https://www.youtube.com/watch?v=HlPe33BYvE4",youtubeWatchUrl("HlPe33BYvE4"))
        assertThrows(IllegalArgumentException::class.java){youtubeWatchUrl("https://other.test")}
    }
    @Test fun untrustedVideoAndOriginCannotInjectPlayerHtml() {
        assertFalse(validVideoId("';alert(1)"))
        assertThrows(IllegalArgumentException::class.java) { playerHtml("';alert(1)", "app.verdant.search") }
        assertThrows(IllegalArgumentException::class.java) { playerHtml("HlPe33BYvE4", "x'</script>") }
        val html = playerHtml("HlPe33BYvE4", "app.verdant.search")
        assertTrue(html.contains("https://app.verdant.search"))
        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
    }
    @Test fun playerRejectsInvalidPreferencesAndRestoresTheSelectedValueInNewHtml() {
        for(rate in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,100.0))
            assertThrows(IllegalArgumentException::class.java){playerHtml("HlPe33BYvE4","app.verdant.search",rate)}
        val html=playerHtml("HlPe33BYvE4","app.verdant.search",2.0)
        assertTrue(html.contains("return player},2.0,"))
        assertTrue(html.contains(PLAYER_SPEED_SCRIPT))
        assertEquals("2×",playbackRateLabel(2.0));assertEquals("1.25×",playbackRateLabel(1.25))
    }
    @Test fun savingFromThePlayerUsesTheBestDetailsAvailable() {
        val id="HlPe33BYvE4"
        val fromCard=watchLaterVideo(id,null,"Title","Channel","UC1","https://i.ytimg.com/vi/x/hq.jpg","2026-11-10T10:00:00Z","PT5M","42")
        assertEquals(Video(id,"Title","Channel","2026-11-10T10:00:00Z","https://i.ytimg.com/vi/x/hq.jpg","PT5M","42",null,"UC1"),fromCard)
        val fetched=Video(id,"Fetched","Fetched channel","2026-11-01T00:00:00Z","https://i.ytimg.com/vi/y/hq.jpg","PT9M","7",true,"UC2")
        assertEquals(fetched,watchLaterVideo(id,fetched,"","","","","","",""))
        val bare=watchLaterVideo(id,null,"","","","","","","")
        assertEquals("Saved video",bare.title);assertEquals("https://i.ytimg.com/vi/$id/hqdefault.jpg",bare.thumbnailUrl);assertNull(bare.duration)
        assertThrows(IllegalArgumentException::class.java){watchLaterVideo("bad",null,"","","","","","","")}
    }
}
