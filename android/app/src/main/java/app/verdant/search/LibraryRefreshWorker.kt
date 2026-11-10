package app.verdant.search

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private fun refreshCadence(context: Context): RefreshCadence {
    val prefs=context.getSharedPreferences("verdant",0)
    return RefreshCadence(prefs.getBoolean("daily-refresh",false),prefs.getInt("refresh-hour",8),prefs.getInt("refresh-minute",0))
}
private fun dailyRunDay(context: Context): String? = context.getSharedPreferences("verdant-refresh",0)
    .getString("daily-run-day",context.getSharedPreferences("verdant",0).getString("refresh-scheduled-day",null))
data class FeedSyncStatus(val running: Boolean=false,val completed: Int=0,val total: Int=0,
                          val lastComplete: Long=0,val problems: Map<String,String> = emptyMap())

object LibraryRefresh {
    val mutex=Mutex()
    private val requestMutex=Mutex()
    val status=MutableStateFlow(FeedSyncStatus())
    const val WORK="verdant-feed-refresh"
    fun schedule(context: Context) {
        val work=WorkManager.getInstance(context)
        val prefs=context.getSharedPreferences("verdant",0)
        // Clock checks are local and hourly, which is as often as a refresh can start. The shared cadence
        // gate admits API work once an hour or after the selected daily time. KEEP preserves the timer on reopen.
        val request=PeriodicWorkRequestBuilder<DailyRefreshTriggerWorker>(60,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        val policy=if(prefs.getInt("refresh-scheduler-version",0)<4) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        work.enqueueUniquePeriodicWork("verdant-daily-refresh",policy,request)
        prefs.edit().putInt("refresh-scheduler-version",4).apply()
    }
    fun restore(context: Context) {
        schedule(context)
        if(!status.value.running) {
            val saved=context.getSharedPreferences("verdant-refresh",0)
            val errors=runCatching {JSONObject(saved.getString("problems","{}").orEmpty())}.getOrDefault(JSONObject())
            status.value=FeedSyncStatus(lastComplete=saved.getLong("last-complete",0),problems=errors.keys().asSequence().associateWith {errors.getString(it)})
        }
    }
    suspend fun request(context: Context, force: Boolean=false): Boolean = requestMutex.withLock { enqueue(context,force) }
    private suspend fun enqueue(context: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val prefs=context.getSharedPreferences("verdant",0)
        if(AppSettings.key("youtube").isBlank())return@withContext false
        if(!prefs.getBoolean("library-consent-v4",false))return@withContext false
        val sync=context.getSharedPreferences("verdant-refresh",0)
        val data=LibraryStore(context,prefs.getString("library-owner","device").orEmpty()).load()
        if(data.channels.isEmpty() || (!force && !refreshCadence(context).isDue(ZonedDateTime.now(),sync.getLong("last-request",0),dailyRunDay(context))))return@withContext false
        val manager=WorkManager.getInstance(context)
        val existing=manager.getWorkInfosForUniqueWork(WORK).get()
        // Preserve the original target/checkpoints during backoff. Reopening the app
        // must not replace pending retries and scan successful channels again.
        if(existing.any {it.state==WorkInfo.State.RUNNING || (!force && !it.state.isFinished)})return@withContext false
        val request=OneTimeWorkRequestBuilder<LibraryRefreshWorker>().setInputData(workDataOf("manual" to force))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
        manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,request).result.get()
        true
    }
    fun cancel(context: Context) {WorkManager.getInstance(context).cancelUniqueWork(WORK)}
}
class DailyRefreshTriggerWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        LibraryRefresh.request(applicationContext)
        return Result.success()
    }
}

/** One durable queue; retries skip channels already committed for this run's target. */
class LibraryRefreshWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs=applicationContext.getSharedPreferences("verdant",0)
        if(AppSettings.key("youtube").isBlank())return@withContext Result.success()
        if(!prefs.getBoolean("library-consent-v4",false))return@withContext Result.success()
        if(!LibraryRefresh.mutex.tryLock())return@withContext Result.retry()
        val store=LibraryStore(applicationContext,prefs.getString("library-owner","device").orEmpty())
        val saved=applicationContext.getSharedPreferences("verdant-refresh",0)
        var target=0L
        val problems=java.util.concurrent.ConcurrentHashMap<String,String>()
        fun publish(running: Boolean) {
            val data=store.load();val ids=data.channels.map {it.id}.toSet()
            problems.keys.removeAll {it !in ids}
            val complete=data.channels.count {(data.channelSyncedAt[it.id] ?: 0)>=target}
            LibraryRefresh.status.value=FeedSyncStatus(running,complete,data.channels.size,saved.getLong("last-complete",0),problems.toMap())
            saved.edit().putString("problems",JSONObject(problems.toMap()).toString()).apply()
        }
        try {
            if(store.load().channels.isEmpty())return@withContext Result.success()
            // Recheck on actual start: settings or local time may have changed while
            // work waited for a connection. Retried work keeps its original target.
            val continuing=saved.getString("run-id",null)==id.toString()
            val legacyTarget=if(runAttemptCount>0)inputData.getLong("target",0) else 0L
            if(continuing)target=saved.getLong("run-target",0)
            if(target==0L) {
                val now=ZonedDateTime.now();val cadence=refreshCadence(applicationContext)
                if(legacyTarget==0L && !inputData.getBoolean("manual",false) &&
                    !cadence.isDue(now,saved.getLong("last-request",0),dailyRunDay(applicationContext)))return@withContext Result.success()
                target=legacyTarget.takeIf {it>0} ?: now.toInstant().toEpochMilli()
                val edit=saved.edit().putString("run-id",id.toString()).putLong("run-target",target).putLong("last-request",target)
                cadence.dayFulfilledBy(java.time.Instant.ofEpochMilli(target).atZone(now.zone))?.let {edit.putString("daily-run-day",it)}
                check(edit.commit()) {"Could not save the refresh schedule."}
            }
            val old=runCatching {JSONObject(saved.getString("problems","{}").orEmpty())}.getOrDefault(JSONObject())
            old.keys().forEach {problems[it]=old.getString(it)}
            publish(true)
            val remote=YoutubeLibraryApi("",minimumRequestIntervalMs=650)
            val finished=withTimeoutOrNull(7*60_000L) {
                FeedSync(remote).run(target,store::load,{channel,videos ->
                    store.update {mergeRecentUploads(it,channel,videos,target)}
                    problems.remove(channel.id);publish(true)
                },{channel,error ->problems[channel.id]=syncProblem(error);publish(true)})
                true
            } ?: false
            val current=store.load()
            val pending=current.channels.filter {(current.channelSyncedAt[it.id] ?: 0)<target}
            if(finished && pending.isEmpty()) {
                saved.edit().putLong("last-complete",System.currentTimeMillis()).apply();Result.success()
            } else {
                pending.forEach {problems.putIfAbsent(it.id,"Refresh interrupted; automatic retry pending.")}
                Result.retry()
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){val data=store.load();data.channels.filter {(data.channelSyncedAt[it.id] ?: 0)<target}.forEach {problems[it.id]=syncProblem(e)};Result.retry()}
        finally {
            try {withContext(NonCancellable+Dispatchers.IO){LibraryStore.flush();if(target>0)publish(false)}}finally {LibraryRefresh.mutex.unlock()}
        }
    }
}

