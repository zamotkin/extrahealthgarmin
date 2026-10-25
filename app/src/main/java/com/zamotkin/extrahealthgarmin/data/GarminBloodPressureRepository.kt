package com.zamotkin.extrahealthgarmin.data

import android.content.Context
import android.util.Log
import com.zamotkin.extrahealthgarmin.garmin.ApiGetResult
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthClient
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminBloodPressureParsing
import com.zamotkin.extrahealthgarmin.garmin.GarminBloodPressureReading
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.GarminUploadClient
import com.zamotkin.extrahealthgarmin.garmin.KeystoreAesCrypto
import com.zamotkin.extrahealthgarmin.garmin.SharedPreferencesTokenPersistence
import com.zamotkin.extrahealthgarmin.health.BloodPressureGateway
import com.zamotkin.extrahealthgarmin.health.BloodPressureUploadOutcome
import com.zamotkin.extrahealthgarmin.health.HcBloodPressure
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneOffset

/** Network seam for [GarminBloodPressureRepository] (fakes in unit tests). */
internal interface BloodPressureRemote {
    suspend fun apiGet(tokens: GarminTokens, path: String): ApiGetResult
    suspend fun uploadBloodPressure(
        readings: List<GarminBloodPressureReading>,
        tokens: GarminTokens
    ): GarminTokens
}

/**
 * Garmin side of blood pressure sync: for each waiting reading, skip it if
 * Garmin already holds it, otherwise take the watch's heart rate at that
 * minute as the pulse and upload all ready readings as one FIT file. A
 * reading with no heart-rate sample yet stays waiting. Never throws.
 */
class GarminBloodPressureRepository internal constructor(
    private val remote: BloodPressureRemote,
    private val tokenStore: GarminTokenStore
) : BloodPressureGateway {

    constructor(context: Context) : this(
        remote = GarminAuthClient().let { auth ->
            val upload = GarminUploadClient(auth)
            object : BloodPressureRemote {
                override suspend fun apiGet(tokens: GarminTokens, path: String) =
                    auth.apiGet(tokens, path)

                override suspend fun uploadBloodPressure(
                    readings: List<GarminBloodPressureReading>,
                    tokens: GarminTokens
                ) = upload.uploadHcBloodPressure(readings, tokens)
            }
        },
        tokenStore = GarminTokenStore(SharedPreferencesTokenPersistence(context), KeystoreAesCrypto())
    )

    override suspend fun upload(readings: List<HcBloodPressure>): BloodPressureUploadOutcome {
        var tokens = tokenStore.load() ?: return BloodPressureUploadOutcome.LoginRequired
        return try {
            val localDates = readings.map { localDate(it) }
            val existing = tokens.get("$RANGE_PATH/${localDates.min()}/${localDates.max()}?includeAll=true")
                .let { (body, t) -> tokens = t; GarminBloodPressureParsing.existingKeys(body) }

            val alreadyThere = readings.filter {
                GarminBloodPressureReading.key(it.localEpochSeconds, it.systolic, it.diastolic) in existing
            }
            val heartRateByDate = mutableMapOf<String, String>()
            val ready = (readings - alreadyThere.toSet()).mapNotNull { reading ->
                val date = localDate(reading)
                val body = heartRateByDate.getOrPut(date) {
                    tokens.get("$HEART_RATE_PATH?date=$date").let { (b, t) -> tokens = t; b }
                }
                val pulse = GarminBloodPressureParsing
                    .nearestHeartRate(body, reading.epochMs, PULSE_WINDOW_MS)
                    ?.takeIf { it in GarminBloodPressureReading.PULSE_RANGE }
                    ?: return@mapNotNull null
                reading to GarminBloodPressureReading(
                    reading.epochMs / 1000, reading.zoneOffsetSeconds,
                    reading.systolic, reading.diastolic, pulse
                )
            }
            if (ready.isNotEmpty()) {
                tokens = remote.uploadBloodPressure(ready.map { it.second }, tokens)
            }
            tokenStore.save(tokens)
            Log.d(TAG, "Blood pressure: ${ready.size} uploaded, ${alreadyThere.size} already in Garmin, " +
                "${readings.size - ready.size - alreadyThere.size} waiting for heart rate")
            BloodPressureUploadOutcome.Done(
                handledIds = (alreadyThere.map { it.id } + ready.map { it.first.id }).toSet(),
                uploadedCount = ready.size
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GarminAuthError) {
            // The weigh-in pass owns token clearing and the sign-in alert.
            Log.w(TAG, "Blood pressure: auth rejected", e)
            BloodPressureUploadOutcome.LoginRequired
        } catch (e: Exception) {
            Log.w(TAG, "Blood pressure upload failed; readings stay waiting", e)
            BloodPressureUploadOutcome.Retryable
        }
    }

    private suspend fun GarminTokens.get(path: String): Pair<String, GarminTokens> =
        remote.apiGet(this, path).let { it.body to it.tokens }

    private fun localDate(reading: HcBloodPressure): String =
        Instant.ofEpochSecond(reading.localEpochSeconds).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    private companion object {
        const val TAG = "GarminBloodPressure"
        const val RANGE_PATH = "/bloodpressure-service/bloodpressure/range"
        const val HEART_RATE_PATH = "/wellness-service/wellness/dailyHeartRate"

        /** Garmin all-day heart rate is sampled every 2 minutes. */
        const val PULSE_WINDOW_MS = 5 * 60_000L
    }
}
