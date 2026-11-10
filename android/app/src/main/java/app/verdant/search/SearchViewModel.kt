package app.verdant.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "", val submittedQuery: String = "", val videos: List<Video> = emptyList(),
    val loading: Boolean = false, val appending: Boolean = false, val searched: Boolean = false,
    val nextPage: String? = null, val error: String? = null, val errorCode: String? = null
)

class SearchViewModel(private val source: SearchSource = SearchRepository(), private val directSearchReady:()->Boolean = {AppSettings.key("youtube").isNotBlank()}) : ViewModel() {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    fun edit(query: String) {
        if (query.isBlank()) { clear(); return }
        mutable.value = mutable.value.copy(query = query, error = null, errorCode = null)
    }
    fun clear() { job?.cancel(); generation++; mutable.value = SearchState() }
    fun submit() {
        val query = state.value.query.trim()
        if (!directSearchReady()) return
        if (query.isEmpty() || query.codePointCount(0, query.length) > 300) {
            mutable.value = state.value.copy(error = "Enter a search of 1–300 characters."); return
        }
        if (state.value.loading && state.value.submittedQuery == query) return
        job?.cancel(); val token = ++generation
        mutable.value = SearchState(query = state.value.query, submittedQuery = query, loading = true)
        request(query, null, token)
    }
    fun loadMore() {
        val current = state.value
        val page = current.nextPage ?: return
        if (current.loading || current.appending) return
        mutable.value = current.copy(appending = true, error = null, errorCode = null)
        request(current.submittedQuery, page, generation)
    }
    fun retry() { if (state.value.videos.isNotEmpty() && state.value.nextPage != null) loadMore() else submit() }
    private fun request(query: String, page: String?, token: Int) {
        job = viewModelScope.launch {
            try {
                val result = source.search(query, page)
                if (token != generation) return@launch
                val videos = if (page == null) result.items else (state.value.videos + result.items).distinctBy { it.id }
                mutable.value = state.value.copy(videos = videos, loading = false, appending = false, searched = true,
                    nextPage = result.nextPageToken?.takeUnless { it == page }, error = null, errorCode = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token != generation) return@launch
                mutable.value = state.value.copy(loading = false, appending = false, searched = true,
                    error = (e as? SearchFailure)?.message ?: "Search is unavailable. Please try again.", errorCode = (e as? SearchFailure)?.code)
            }
        }
    }
}

