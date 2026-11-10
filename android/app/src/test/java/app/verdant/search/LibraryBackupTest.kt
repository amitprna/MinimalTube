package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private val video=Video("aaaaaaaaaaa","Title","One","2026-10-17T10:00:00Z","https://i.ytimg.com/vi/a/hqdefault.jpg","PT10M","42",true,"UC1")
    private val library=LibraryData(
        channels=listOf(Channel("UC1","One","thumb","UU1","About",5)),feed=listOf(video),
        later=listOf(SavedVideo(video.copy(id="bbbbbbbbbbb"),10)),bin=listOf(SavedVideo(video.copy(id="ccccccccccc"),20)),
        rules=mapOf("UC1" to ChannelRule(true,180,setOf("Tech"))),groups=setOf("Tech"),groupIds=mapOf("Tech" to "TECH"),groupOrder=listOf("Tech"),syncedAt=1)

    @Test fun backupRestoresTheLibraryAndWatchPositions() {
        val watch=mapOf("aaaaaaaaaaa" to WatchEntry(120,600,99),"not-a-video-id" to WatchEntry(1,2,3))
        val restored=decodeLibraryBackup(encodeLibraryBackup(library,watch,1234))
        assertEquals(library.channels,restored.library.channels)
        assertEquals(library.feed,restored.library.feed)
        assertEquals(library.later,restored.library.later)
        assertEquals(library.bin,restored.library.bin)
        assertEquals(library.rules,restored.library.rules)
        assertEquals(library.groups,restored.library.groups)
        assertEquals(library.groupOrder,restored.library.groupOrder)
        assertEquals("TECH",restored.library.groupIds["Tech"])
        assertEquals(mapOf("aaaaaaaaaaa" to WatchEntry(120,600,99)),restored.watch)
        assertEquals(1234,restored.exportedAt)
        assertEquals("1 channels · 1 groups · 1 in Watch Later · 1 in Bin · 1 watch positions",backupSummary(restored))
    }
    @Test fun unrelatedOrNewerFilesAreRejectedWithAClearMessage() {
        for(text in listOf("not json","{}","""{"format":"other"}""")) {
            val error=assertThrows(SearchFailure::class.java){decodeLibraryBackup(text)}
            assertEquals("This file is not a MinimalTube backup.",error.message)
        }
        val newer=assertThrows(SearchFailure::class.java){decodeLibraryBackup("""{"format":"greentube-library","version":99,"library":{}}""")}
        assertTrue(newer.message.contains("newer MinimalTube"))
        val damaged=assertThrows(SearchFailure::class.java){decodeLibraryBackup("""{"format":"greentube-library","version":1,"library":{"channels":[{"title":"no id"}]}}""")}
        assertTrue(damaged.message.contains("damaged"))
    }
    @Test fun backupNeverContainsApiKeys() {
        val text=encodeLibraryBackup(library,emptyMap(),0)
        assertFalse(text.contains("AIza"))
        assertFalse(text.contains("\"keys\""))
    }
}
