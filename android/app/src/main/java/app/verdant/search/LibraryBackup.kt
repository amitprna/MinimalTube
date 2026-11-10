package app.verdant.search

import org.json.JSONObject

// The format ID keeps its original name so backups made before the rename still restore.
const val BACKUP_FORMAT = "greentube-library"
const val BACKUP_VERSION = 1
const val MAX_BACKUP_BYTES = 20 * 1024 * 1024

/** A portable copy of subscriptions, groups, saved lists and watch positions. API keys are never included. */
data class LibraryBackup(val library: LibraryData, val watch: Map<String,WatchEntry>, val exportedAt: Long)

fun encodeLibraryBackup(library: LibraryData, watch: Map<String,WatchEntry>, now: Long): String =
    JSONObject().put("format",BACKUP_FORMAT).put("version",BACKUP_VERSION).put("exportedAt",now)
        .put("library",LibraryStore.encode(library))
        .put("watch",JSONObject(watch.filterKeys(::validVideoId).mapValues {encodeWatchEntry(it.value)}))
        .toString()

fun decodeLibraryBackup(text: String): LibraryBackup {
    val root=try {JSONObject(text)} catch(_: org.json.JSONException) {throw SearchFailure("backup","This file is not a MinimalTube backup.")}
    if(root.optString("format")!=BACKUP_FORMAT)throw SearchFailure("backup","This file is not a MinimalTube backup.")
    if(root.optInt("version",0)>BACKUP_VERSION)throw SearchFailure("backup","This backup was made by a newer MinimalTube. Update the app, then try again.")
    val library=try {LibraryStore.decode(root.getJSONObject("library"))} catch(e: Exception) {throw SearchFailure("backup","The backup is damaged and could not be read.")}
    val watchJson=root.optJSONObject("watch") ?: JSONObject()
    val watch=watchJson.keys().asSequence().filter(::validVideoId)
        .mapNotNull {id->parseWatchEntry(watchJson.optString(id))?.let {id to it}}.toMap()
    return LibraryBackup(library.copy(channels=library.channels.filter {it.id.isNotBlank()}.distinctBy {it.id}),watch,root.optLong("exportedAt"))
}

fun backupSummary(backup: LibraryBackup): String {
    val d=backup.library
    return listOf("${d.channels.size} channels","${d.groups.size} groups","${d.later.size} in Watch Later","${d.bin.size} in Bin","${backup.watch.size} watch positions").joinToString(" · ")
}
