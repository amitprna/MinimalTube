package app.verdant.search

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun durationHandlesHoursLiveAndMalformedValues() {
        assertEquals("1:02:03", durationLabel("PT1H2M3S")); assertEquals("3:05", durationLabel("PT3M5S"))
        assertNull(durationLabel("PT0S")); assertNull(durationLabel("not-a-duration"))
    }
    private fun video(id: String) = Video(id, id, "Channel", "", "", null, null, true)
    @Test fun directSearchNeedsOnlyTheApiKeyAndKeepsGoogleSessionUntouched() = runTest(dispatcher) {
        val previous=SignInSession.user
        try {
            SignInSession.user=null
            var calls=0
            val source=object:SearchSource {
                override suspend fun search(query:String,pageToken:String?):SearchPage {
                    calls++;return SearchPage(listOf(video("direct")),null)
                }
            }
            val vm=SearchViewModel(source){true}
            vm.edit("test");vm.submit();advanceUntilIdle()
            assertEquals(1,calls);assertEquals("direct",vm.state.value.videos.single().id);assertNull(SignInSession.user)
            val unconfigured=SearchViewModel(source){false}
            unconfigured.edit("test");unconfigured.submit();advanceUntilIdle()
            assertEquals(1,calls);assertFalse(unconfigured.state.value.loading)
        } finally {SignInSession.user=previous}
    }
    @Test fun staleResponseCannotReplaceNewQueryEvenIfCancellationIsIgnored() = runTest(dispatcher) {
        val vm = SearchViewModel(object : SearchSource {
            override suspend fun search(query: String, pageToken: String?): SearchPage {
                withContext(NonCancellable) { delay(if (query == "first") 100 else 10) }
                return SearchPage(listOf(video(query)), null)
            }
        }){true}
        vm.edit("first"); vm.submit(); runCurrent()
        vm.edit("second"); vm.submit(); advanceUntilIdle()
        assertEquals("second", vm.state.value.videos.single().id)
    }
    @Test fun paginationKeepsResultsAndRetryAfterFailureDoesNotDuplicateThem() = runTest(dispatcher) {
        var calls = 0
        val vm = SearchViewModel(object : SearchSource {
            override suspend fun search(query: String, pageToken: String?): SearchPage {
                calls++
                if (calls == 2) throw SearchFailure("network", "Offline")
                return if (pageToken == null) SearchPage(listOf(video("one")), "next") else SearchPage(listOf(video("one"), video("two")), null)
            }
        }){true}
        vm.edit("test"); vm.submit(); advanceUntilIdle()
        vm.loadMore(); vm.loadMore(); advanceUntilIdle()
        assertEquals(2, calls); assertEquals(1, vm.state.value.videos.size); assertEquals("Offline", vm.state.value.error)
        vm.retry(); advanceUntilIdle(); assertEquals(listOf("one", "two"), vm.state.value.videos.map { it.id })
    }
    @Test fun clearInvalidatesAnInFlightRequest() = runTest(dispatcher) {
        val vm = SearchViewModel(object : SearchSource {
            override suspend fun search(query: String, pageToken: String?): SearchPage {
                withContext(NonCancellable) { delay(100) }; return SearchPage(listOf(video("old")), null)
            }
        }){true}
        vm.edit("old"); vm.submit(); runCurrent(); vm.clear(); advanceUntilIdle()
        assertTrue(vm.state.value.videos.isEmpty()); assertFalse(vm.state.value.searched)
    }

    @Test fun emptyEditClearsCompletedResultsAndPagination() = runTest(dispatcher) {
        val vm=SearchViewModel(object:SearchSource {
            override suspend fun search(query:String,pageToken:String?)=SearchPage(listOf(video("old")),"next")
        }){true}
        vm.edit("old");vm.submit();advanceUntilIdle()
        vm.edit("")
        assertEquals(SearchState(),vm.state.value)
        vm.loadMore();advanceUntilIdle();assertTrue(vm.state.value.videos.isEmpty())
    }
    @Test fun whitespaceEditInvalidatesLateResponseEvenWhenCancellationIgnored() = runTest(dispatcher) {
        val vm=SearchViewModel(object:SearchSource {
            override suspend fun search(query:String,pageToken:String?):SearchPage {
                withContext(NonCancellable){delay(100)};return SearchPage(listOf(video("old")),"next")
            }
        }){true}
        vm.edit("old");vm.submit();runCurrent();vm.edit("   ");advanceUntilIdle()
        assertEquals(SearchState(),vm.state.value)
    }
}
