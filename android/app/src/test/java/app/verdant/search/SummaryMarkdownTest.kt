package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class SummaryMarkdownTest {
    @Test fun generatedLinksCannotLaunchScriptsLocalFilesOrAppIntents() {
        for(link in listOf("javascript:alert(1)","intent://open#Intent;end","file:///sdcard/private.txt","data:text/html,hello","content://private/data","http://example.com","//example.com","https://account@example.com","https://example.com\n"))
            assertFalse(link,safeSummaryLink(link))
        assertTrue(safeSummaryLink("https://www.youtube.com/watch?v=aaaaaaaaaaa&t=120s"))
        assertTrue(safeSummaryLink("https://example.com/notes#section"))
    }
}
