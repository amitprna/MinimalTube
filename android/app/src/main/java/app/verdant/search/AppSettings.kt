package app.verdant.search

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MinimalTubeApplication: Application() {
    override fun onCreate(){
        super.onCreate();AppSettings.initialize(this);SummaryManager.initialize(this)
        Thread({WatchHistory.initialize(this)},"watch-history").start()
    }
}
data class UserKey(val id: String,val name: String,val value: String,val description: String="")
object AppSettings {
    private var app: Context?=null
    fun appContext(): Context? = app
    private var cachedCiphertext: String?=null
    private var cachedKeys: List<UserKey> = emptyList()
    val revision=MutableStateFlow(0L)
    fun initialize(context: Context){app=context.applicationContext}
    private fun prefs()=checkNotNull(app).getSharedPreferences("greentube-settings",0)
    private fun secretKey(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("greentube-credentials",null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("greentube-credentials",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun keys(): List<UserKey> {
        if(app==null)return emptyList()
        val stored=prefs().getString("keys",null) ?: return emptyList()
        if(stored==cachedCiphertext)return cachedKeys
        val parts=stored.split(':');require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))}
        val data=JSONArray(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
        return (0 until data.length()).map {val item=data.getJSONObject(it);UserKey(item.getString("id"),item.getString("name"),item.getString("value"),item.optString("description"))}.also {cachedCiphertext=stored;cachedKeys=it}
    }
    @Synchronized fun saveKey(key: UserKey) {
        val all=keys().filterNot {it.id==key.id}+listOf(key).filter {it.value.isNotBlank()}
        val data=JSONArray(all.map {JSONObject().put("id",it.id).put("name",it.name).put("value",it.value.trim()).put("description",it.description)})
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,secretKey())}
        val encrypted=cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(prefs().edit().putString("keys",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
        revision.value++
    }
    // An invalidated device keystore disables network features instead of crashing the UI.
    fun key(id: String)=runCatching {keys().firstOrNull {it.id==id}?.value.orEmpty()}.getOrDefault("")
    @Synchronized fun resetKeys(){check(prefs().edit().remove("keys").commit());cachedCiphertext=null;cachedKeys=emptyList();revision.value++}
    fun text(id: String, fallback: String="")=if(app==null)fallback else prefs().getString(id,fallback).orEmpty()
    fun setText(id: String,value: String){prefs().edit().putString(id,value).apply();revision.value++}
    fun identifyAndroid(builder: okhttp3.Request.Builder): okhttp3.Request.Builder {
        val context=app ?: return builder
        val fingerprint=signingFingerprint(context).replace(":","")
        if(fingerprint.matches(Regex("[A-F0-9]{40}")))builder.header("X-Android-Package",context.packageName).header("X-Android-Cert",fingerprint)
        return builder
    }
    fun model()=text("gemini-model","gemini-flash-latest").removePrefix("models/")
    /** Backup summary model used when the main one is overloaded; blank turns the backup off. */
    fun fallbackModel()=text("gemini-fallback-model",DEFAULT_FALLBACK_MODEL).trim().removePrefix("models/")
    fun playbackRate()=text("playback-speed","2.0").toDoubleOrNull()?.takeIf {it in defaultPlaybackRates} ?: 2.0
}

