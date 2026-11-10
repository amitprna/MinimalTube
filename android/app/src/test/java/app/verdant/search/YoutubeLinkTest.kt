package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class YoutubeLinkTest {
    private val id="HlPe33BYvE4"
    @Test fun commonYoutubeLinkFormsOpenTheVideo() {
        for(link in listOf("https://www.youtube.com/watch?v=$id","https://youtube.com/watch?feature=share&v=$id","http://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&list=RD1","https://youtu.be/$id","https://youtu.be/$id?si=abc123",
            "https://www.youtube.com/shorts/$id","https://youtube.com/live/$id?feature=share","https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id","youtu.be/$id","www.youtube.com/watch?v=$id"))
            assertEquals(link,YoutubeLink(id),parseYoutubeLink(link))
    }
    @Test fun startTimesInLinksAreKept() {
        assertEquals(90,parseYoutubeLink("https://youtu.be/$id?t=90")?.start)
        assertEquals(90,parseYoutubeLink("https://youtu.be/$id?t=90s")?.start)
        assertEquals(3723,parseYoutubeLink("https://www.youtube.com/watch?v=$id&t=1h2m3s")?.start)
        assertEquals(75,parseYoutubeLink("https://www.youtube.com/watch?v=$id#t=1:15")?.start)
        assertEquals(30,parseYoutubeLink("https://www.youtube.com/embed/$id?start=30")?.start)
        assertNull(parseYoutubeLink("https://youtu.be/$id?t=soon")?.start)
    }
    @Test fun sharedTextFindsTheLinkAmongOtherWords() {
        assertEquals(YoutubeLink(id),parseYoutubeLink("Check this out!\nhttps://youtu.be/$id."))
        assertEquals(YoutubeLink(id,42),parseYoutubeLink("Watch \"A talk\" on YouTube: (https://www.youtube.com/watch?v=$id&t=42)"))
    }
    @Test fun nonVideoAndLookalikeLinksAreRejected() {
        for(link in listOf(null,"","hello","https://www.youtube.com/","https://www.youtube.com/@channel","https://www.youtube.com/playlist?list=PL1",
            "https://www.youtube.com/watch?v=short","https://evil.example/watch?v=$id","https://youtube.com.evil.example/watch?v=$id",
            "https://user@youtube.com/watch?v=$id","ftp://youtube.com/watch?v=$id","https://youtube.com:8443/watch?v=$id",
            "https://user@youtube.com/watch?v=$id "))
            assertNull(link,parseYoutubeLink(link))
    }
}
