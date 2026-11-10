package app.verdant.search

import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

// Keep remote messages, metadata, URLs and credentials out of the UI and logs.
internal data class SummaryApiError(
    val httpCode:Int,
    val status:String,
    val reason:String,
    val retryDelayMillis:Long?,
    val dailyQuota:Boolean
) {
    val retryable:Boolean get()=httpCode in setOf(408,500,502,503,504) ||
        (httpCode==429 && !dailyQuota && (retryDelayMillis!=null || status in setOf("RATE_LIMIT_EXCEEDED","TOO_MANY_REQUESTS")))

    fun toSearchFailure(model:String,attempts:Int):SearchFailure {
        val message=when {
            reason=="SERVICE_DISABLED"->"Enable the Generative Language API in the Gemini key's Google Cloud project."
            reason=="BILLING_DISABLED" || status=="FAILED_PRECONDITION"->"Check Gemini billing, region availability and project prerequisites in Google AI Studio."
            reason in setOf("API_KEY_INVALID","API_KEY_EXPIRED")->"The Gemini key is invalid or expired. Replace it in Settings → API keys."
            reason in setOf("API_KEY_SERVICE_BLOCKED","API_KEY_ANDROID_APP_BLOCKED","API_KEY_IP_ADDRESS_BLOCKED","API_KEY_HTTP_REFERRER_BLOCKED")->"The Gemini key's restrictions rejected this app. Check its Generative Language API and application restrictions."
            httpCode==400->"Gemini could not process the video or model configuration. Try another public video and check the model supports video input and high thinking."
            httpCode==401 || httpCode==403->"The Gemini key was not accepted. Check its API restrictions and project permissions."
            httpCode==402->"Gemini requires billing or additional credits. Check this key's project in Google AI Studio before trying again."
            httpCode==404->"The Gemini model or resource was not found. Check the model in Settings → API keys and that the video is public."
            httpCode==429 && dailyQuota->"Gemini daily quota is exhausted. Wait for the quota reset or review limits in Google AI Studio."
            httpCode==429->"Gemini quota or rate limit reached. Check Google AI Studio and try later."
            httpCode==408 || httpCode==504->"Gemini timed out processing this video. Try a shorter public video or try again later."
            httpCode==500->"Gemini could not process this request because of a server error. Try another public video or try again later."
            httpCode==502 || httpCode==503->"Gemini is busy or unavailable. Try again in a few minutes."
            httpCode==501->"This Gemini endpoint does not support the request. Check the selected model in Settings → API keys."
            else->"Gemini rejected the request. Share the error code below to help diagnose it."
        }
        val codes=listOf("HTTP $httpCode",status,reason).filter {it.isNotBlank()}.joinToString(" · ")
        return SearchFailure("summary_http_$httpCode","$message\n$codes · $attempts ${if(attempts==1)"attempt" else "attempts"}\nModel: $model")
    }
}

internal fun summaryApiError(httpCode:Int,body:String,retryAfter:String?,now:Instant=Instant.now()):SummaryApiError {
    val error=runCatching {JSONObject(body).optJSONObject("error")}.getOrNull()
    val rawStatus=error?.optString("status").orEmpty().ifBlank {error?.optString("code").orEmpty()}.uppercase(Locale.ROOT)
    val status=rawStatus.takeIf {it in setOf("INVALID_ARGUMENT","INVALID_REQUEST","FAILED_PRECONDITION","UNAUTHENTICATED","AUTHENTICATION","PERMISSION_DENIED","NOT_FOUND","MODEL_NOT_FOUND","PAYMENT_REQUIRED","RESOURCE_EXHAUSTED","RATE_LIMIT_EXCEEDED","QUOTA_EXCEEDED","TOO_MANY_REQUESTS","INTERNAL","API_ERROR","UNAVAILABLE","SERVICE_UNAVAILABLE","DEADLINE_EXCEEDED","UNIMPLEMENTED")} ?: ""
    var reason=""
    var dailyQuota=status=="QUOTA_EXCEEDED"
    var retryDelay=retryAfter?.toDoubleOrNull()?.let(::secondsToMillis)
        ?: retryAfter?.let {runCatching {java.time.Duration.between(now,ZonedDateTime.parse(it,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis().coerceAtLeast(0)}.getOrNull()}
    val details=error?.optJSONArray("details")
    for(index in 0 until (details?.length() ?: 0)) {
        val detail=details?.optJSONObject(index) ?: continue
        when(detail.optString("@type")) {
            "type.googleapis.com/google.rpc.ErrorInfo"-> {
                val candidate=detail.optString("reason")
                if(candidate in setOf("SERVICE_DISABLED","BILLING_DISABLED","API_KEY_INVALID","API_KEY_EXPIRED","API_KEY_SERVICE_BLOCKED","API_KEY_ANDROID_APP_BLOCKED","API_KEY_IP_ADDRESS_BLOCKED","API_KEY_HTTP_REFERRER_BLOCKED"))reason=candidate
            }
            "type.googleapis.com/google.rpc.RetryInfo"-> {
                val candidate=detail.optString("retryDelay").takeIf {it.endsWith("s")}?.dropLast(1)?.toDoubleOrNull()?.let(::secondsToMillis)
                if(candidate!=null)retryDelay=maxOf(retryDelay ?: 0,candidate)
            }
            "type.googleapis.com/google.rpc.QuotaFailure"-> {
                val violations=detail.optJSONArray("violations")
                for(item in 0 until (violations?.length() ?: 0)) {
                    val violation=violations?.optJSONObject(item) ?: continue
                    val metric=(violation.optString("quotaId")+" "+violation.optString("quotaMetric")).lowercase(Locale.ROOT).replace("_","")
                    if("perday" in metric || "daily" in metric)dailyQuota=true
                }
            }
        }
    }
    return SummaryApiError(httpCode,status,reason,retryDelay,dailyQuota)
}

private fun secondsToMillis(seconds:Double):Long? =
    if(seconds.isFinite() && seconds>=0)ceil(seconds.coerceAtMost(86_400.0)*1000).toLong() else null
