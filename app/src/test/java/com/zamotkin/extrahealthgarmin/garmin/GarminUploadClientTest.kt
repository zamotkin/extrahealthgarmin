package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.Decoder
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.WeightScaleMesg
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Wire-contract tests for [GarminUploadClient] against MockWebServer: multipart
 * shape, native headers, FIT roundtrip through the official SDK Decoder, and
 * the retry discipline (request-count assertions: 429=1, 401=2+1 refresh,
 * 5xx=3). Plain JUnit + JVM seams only — Robolectric cannot run on this
 * JDK 26 toolchain (see app/build.gradle).
 */
class GarminUploadClientTest {

    private lateinit var server: MockWebServer
    private lateinit var authClient: GarminAuthClient
    private lateinit var client: GarminUploadClient
    private val backoffAttempts = mutableListOf<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        authClient = GarminAuthClient(
            ssoBaseUrl = base,
            diauthBaseUrl = base,
            apiBaseUrl = base,
            apiBackoffDelayMs = { 0 }
        )
        backoffAttempts.clear()
        client = GarminUploadClient(
            authClient = authClient,
            apiBaseUrl = base,
            backoffDelayMs = { attempt ->
                backoffAttempts += attempt
                0 // no sleeping in tests
            }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ //
    //  Spec scenarios (names match the task's TDD list 1:1)              //
    // ------------------------------------------------------------------ //

    @Test
    fun `multipart_request_shape_and_native_headers`() = runBlocking {
        server.enqueue(jsonResponse(successBody(1)))

        val result = client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())

        assertEquals(1, (result as UploadResult.Success).count)
        val request = server.takeRequest()
        assertEquals("/upload-service/upload", request.path)
        assertEquals("POST", request.method)
        val contentType = request.getHeader("Content-Type")
        assertTrue("expected multipart body, got $contentType",
            contentType!!.startsWith("multipart/form-data; boundary="))
        assertEquals("Bearer ${JWT}", request.getHeader("Authorization"))
        assertEquals("GCM-Android-5.23", request.getHeader("User-Agent"))
        assertEquals("com.garmin.android.apps.connectmobile/5.23; ; " +
            "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
            request.getHeader("X-Garmin-User-Agent"))
        assertEquals("10861", request.getHeader("X-App-Ver"))
        assertEquals("Android", request.getHeader("X-Garmin-Client-Platform"))
        assertEquals("en", request.getHeader("X-Lang"))
        assertEquals("GC5", request.getHeader("X-GCExperience"))
        val body = request.body.readUtf8()
        assertTrue("part name must be 'file': $body", body.contains("name=\"file\""))
        assertTrue("filename must be body_composition.fit: $body",
            body.contains("filename=\"body_composition.fit\""))
    }

    @Test
    fun `fit_roundtrip_weight_fat_bmi_decoded`() = runBlocking {
        server.enqueue(jsonResponse(successBody(1)))

        client.uploadWeighIns(
            listOf(record(EPOCH_1, weightKg = 75.5f, percentFat = 22.5f, bmi = 24.5f)),
            tokens()
        )

        val decoded = decodeCapturedFit(server.takeRequest())
        assertEquals("file_id mesg must be present", 1, decoded.fileIds.size)
        assertEquals(File.WEIGHT, decoded.fileIds[0].type)
        assertEquals(1, decoded.weights.size)
        val mesg = decoded.weights[0]
        assertEquals(75.5f, mesg.weight)
        assertEquals(22.5f, mesg.percentFat)
        assertEquals(24.5f, mesg.bmi)
        assertEquals("timestamp must roundtrip as UTC epoch seconds",
            EPOCH_1, mesg.timestamp.getDate().time / 1000)
    }

    @Test
    fun `fit_multiple_records_batched`() = runBlocking {
        server.enqueue(jsonResponse(successBody(3)))

        client.uploadWeighIns(
            listOf(record(EPOCH_1), record(EPOCH_2), record(EPOCH_3)),
            tokens()
        )

        val decoded = decodeCapturedFit(server.takeRequest())
        assertEquals(3, decoded.weights.size)
        assertEquals(
            listOf(EPOCH_1, EPOCH_2, EPOCH_3),
            decoded.weights.map { it.timestamp.getDate().time / 1000 }
        )
    }

    @Test
    fun `missing_fields_omitted_from_fit`() = runBlocking {
        server.enqueue(jsonResponse(successBody(1)))

        client.uploadWeighIns(
            listOf(record(EPOCH_1, percentFat = null, bmi = null)),
            tokens()
        )

        val decoded = decodeCapturedFit(server.takeRequest())
        assertEquals(1, decoded.weights.size)
        val mesg = decoded.weights[0]
        assertEquals(75.5f, mesg.weight)
        assertTrue("percent_fat must be unset for null input, got ${mesg.percentFat}",
            mesg.percentFat == null || mesg.percentFat.isNaN())
        assertTrue("bmi must be unset for null input, got ${mesg.bmi}",
            mesg.bmi == null || mesg.bmi.isNaN())
    }

    @Test
    fun `response_all_success_returns_success_count`() = runBlocking {
        server.enqueue(jsonResponse(successBody(3)))

        val result = client.uploadWeighIns(
            listOf(record(EPOCH_1), record(EPOCH_2), record(EPOCH_3)),
            tokens()
        )

        assertEquals(3, (result as UploadResult.Success).count)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `response_with_failures_returns_partial_with_messages`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"detailedImportResult":{"successes":[],"failures":[
                    {"messages":["Duplicate detection - existing measurement"]},
                    {"messages":["Value out of range"]}
                ]}}"""
            )
        )

        val result = client.uploadWeighIns(
            listOf(record(EPOCH_1), record(EPOCH_2)),
            tokens()
        )

        assertTrue("expected Partial, got $result", result is UploadResult.Partial)
        val partial = result as UploadResult.Partial
        assertTrue("failure messages must be surfaced: ${partial.failureMessages}",
            partial.failureMessages.contains("Duplicate detection - existing measurement"))
        assertTrue(partial.failureMessages.contains("Value out of range"))
        assertTrue(
            "uncorrelatable failures must yield EMPTY succeededKeys: ${partial.succeededKeys}",
            partial.succeededKeys.isEmpty()
        )
    }

    @Test
    fun `response_with_indexed_failures_returns_partial_with_succeeded_keys`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"detailedImportResult":{"successes":[],"failures":[
                    {"index":1,"messages":["Duplicate detection - existing measurement"]}
                ]}}"""
            )
        )

        val result = client.uploadWeighIns(
            listOf(record(EPOCH_1), record(EPOCH_2), record(EPOCH_3)),
            tokens()
        )

        assertTrue("expected Partial, got $result", result is UploadResult.Partial)
        val partial = result as UploadResult.Partial
        assertEquals(
            "succeededKeys are composite journal keys (epoch:weight)",
            listOf("${EPOCH_1}:75.5", "${EPOCH_3}:75.5"),
            partial.succeededKeys
        )
        assertEquals(listOf("Duplicate detection - existing measurement"), partial.failureMessages)
    }

    /** Task .6 audit: HTTP 204 No Content is the plain success answer. */
    @Test
    fun `upload_204_no_content_returns_success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())

        assertEquals(1, (result as UploadResult.Success).count)
        assertEquals("204 must produce exactly ONE request", 1, server.requestCount)
    }

    /**
     * Task .6 audit: range bounds are inclusive maxima — 500.0 kg is accepted,
     * 500.01 kg is rejected BEFORE any network call, and the FIT uint16 scale
     * (weight * 100) must fit the wire field even at the maximum.
     */
    @Test
    fun `range_boundaries_inclusive_maxima_accepted_just_over_rejected`() = runBlocking {
        // Sanity: the accepted maximum must survive the FIT scale-100 encoding.
        assertTrue(
            "MAX_WEIGHT_KG * 100 must fit the FIT uint16 field",
            GarminWeighInRecord.MAX_WEIGHT_KG * 100 <= 65535f
        )

        server.enqueue(MockResponse().setResponseCode(204))
        val accepted = client.uploadWeighIns(
            listOf(record(EPOCH_1, weightKg = 500.0f, percentFat = 100.0f, bmi = 100.0f)),
            tokens()
        )
        assertEquals("inclusive maxima (500.0 kg / 100% / BMI 100) must be accepted",
            1, (accepted as UploadResult.Success).count)
        assertEquals(1, server.requestCount)

        try {
            client.uploadWeighIns(listOf(record(EPOCH_2, weightKg = 500.01f)), tokens())
            fail("expected GarminValidationError just above the weight maximum")
        } catch (e: GarminValidationError) {
            assertTrue(e.message!!.contains("range"))
        }
        assertEquals("out-of-range records must not touch the network", 1, server.requestCount)
    }

    /**
     * Task .6 audit: a 200 answer whose failures carry NO correlatable index
     * must yield EMPTY succeededKeys even when successes are listed — only
     * provable imports may be journaled.
     */
    @Test
    fun `upload_200_successes_plus_unindexed_failures_yields_empty_succeeded_keys`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"detailedImportResult":{"successes":[{"index":0},{"index":1}],"failures":[
                    {"messages":["Duplicate detection - existing measurement"]}
                ]}}"""
            )
        )

        val result = client.uploadWeighIns(
            listOf(record(EPOCH_1), record(EPOCH_2)),
            tokens()
        )

        assertTrue("expected Partial, got $result", result is UploadResult.Partial)
        val partial = result as UploadResult.Partial
        assertTrue(
            "unindexed failures must yield EMPTY succeededKeys even with listed successes: ${partial.succeededKeys}",
            partial.succeededKeys.isEmpty()
        )
        assertEquals(
            listOf("Duplicate detection - existing measurement"),
            partial.failureMessages
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `out_of_range_weight_rejected_before_network`() = runBlocking {
        val invalid = listOf(
            record(EPOCH_1, weightKg = 0f),
            record(EPOCH_1, weightKg = 700f),
            record(EPOCH_1, percentFat = 150f),
            record(EPOCH_1, bmi = 0f)
        )
        for (bad in invalid) {
            try {
                client.uploadWeighIns(listOf(record(EPOCH_2), bad), tokens())
                fail("expected GarminValidationError for $bad")
            } catch (e: GarminValidationError) {
                // expected — message names the offending field
                assertTrue(e.message!!.isNotBlank())
            }
        }
        assertEquals("range violations must not touch the network", 0, server.requestCount)
    }

    @Test
    fun `empty_records_returns_success_zero_without_network`() = runBlocking {
        val result = client.uploadWeighIns(emptyList(), tokens())

        assertEquals(0, (result as UploadResult.Success).count)
        assertEquals("empty input must produce ZERO requests", 0, server.requestCount)
    }

    @Test
    fun `upload_429_surfaces_after_single_request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))

        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminTooManyRequestsError")
        } catch (e: GarminTooManyRequestsError) {
            assertTrue(e.message!!.contains("429"))
        }
        assertEquals("429 must produce exactly ONE request", 1, server.requestCount)
    }

    @Test
    fun `upload_401_refreshes_once_retries_once`() = runBlocking {
        server.enqueue(jsonResponse("", code = 401))
        server.enqueue(jsonResponse(tokenJson(FRESH_JWT, refreshToken = "rt-new")))
        server.enqueue(jsonResponse("", code = 401))

        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminAuthError after second 401")
        } catch (e: GarminAuthError) {
            assertTrue(e.message!!.contains("401"))
        }

        // Exactly 2 upload attempts + 1 refresh — no retry storm.
        assertEquals(3, server.requestCount)
        val firstUpload = server.takeRequest()
        val refresh = server.takeRequest()
        val secondUpload = server.takeRequest()
        assertEquals("/upload-service/upload", firstUpload.path)
        assertEquals("/di-oauth2-service/oauth/token", refresh.path)
        assertEquals("/upload-service/upload", secondUpload.path)
        assertEquals("Bearer $JWT", firstUpload.getHeader("Authorization"))
        assertEquals("Bearer $FRESH_JWT", secondUpload.getHeader("Authorization"))
    }

    @Test
    fun `upload_401_refresh_then_success_uses_new_token`() = runBlocking {
        server.enqueue(jsonResponse("", code = 401))
        server.enqueue(jsonResponse(tokenJson(FRESH_JWT, refreshToken = "rt-new")))
        server.enqueue(jsonResponse(successBody(1)))

        val result = client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())

        assertEquals(1, (result as UploadResult.Success).count)
        assertEquals(3, server.requestCount)
        server.takeRequest() // first upload (401, old token)
        server.takeRequest() // refresh
        val retry = server.takeRequest()
        assertEquals("/upload-service/upload", retry.path)
        assertEquals("Bearer $FRESH_JWT", retry.getHeader("Authorization"))
    }

    @Test
    fun `upload_5xx_retries_3_attempts_then_connection_error`() = runBlocking {
        repeat(3) { server.enqueue(jsonResponse("boom", code = 500)) }

        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue(e.message!!.contains("500"))
        }

        assertEquals("5xx must produce exactly THREE attempts", 3, server.requestCount)
        assertEquals("backoff must run between the 3 attempts (attempts 1 and 2)",
            listOf(1, 2), backoffAttempts)
    }

    @Test
    fun `html_challenge_response_maps_to_connection_error`() = runBlocking {
        // 200 with an HTML Cloudflare challenge body — the sneakiest case.
        server.enqueue(htmlResponse(200))
        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue("message must indicate a challenge: ${e.message}",
                e.message!!.contains("challenge"))
        }
        // Same for a 403 challenge.
        server.enqueue(htmlResponse(403))
        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue(e.message!!.contains("challenge"))
        }
        assertEquals("challenges are never retried", 2, server.requestCount)
    }

    @Test
    fun `malformed_json_success_response_maps_to_connection_error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{{{not json at all")
        )

        try {
            client.uploadWeighIns(listOf(record(EPOCH_1)), tokens())
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue("message must say the JSON is malformed: ${e.message}",
                e.message!!.contains("malformed"))
        }
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------ //
    //  Blood-pressure uploads (same shared path, blood_pressure.fit)      //
    // ------------------------------------------------------------------ //

    @Test
    fun `bp_upload_posts_blood_pressure_fit_with_decodable_messages`() = runBlocking {
        server.enqueue(jsonResponse(successBody(2)))

        val result = client.uploadBloodPressure(
            listOf(bpReading(EPOCH_1, userSlot = 1), bpReading(EPOCH_1, userSlot = 2)),
            tokens()
        )

        assertEquals(2, (result as UploadResult.Success).count)
        val request = server.takeRequest()
        assertEquals("/upload-service/upload", request.path)
        // Decode first: it clones the one-shot body, but readUtf8() would drain it.
        val decoded = decodeCapturedBpFit(request)
        val body = request.body.readUtf8()
        assertTrue("part name must be 'file': $body", body.contains("name=\"file\""))
        assertTrue("filename must be blood_pressure.fit: $body",
            body.contains("filename=\"blood_pressure.fit\""))
        assertEquals(1, decoded.fileIds.size)
        assertEquals(File.BLOOD_PRESSURE, decoded.fileIds[0].type)
        assertEquals(
            "both user slots travel as separate messages (profile index 0 and 1)",
            listOf(0, 1),
            decoded.bloodPressures.map { it.userProfileIndex }
        )
        assertEquals(
            "same measurement time, two user slots: both timestamps survive",
            listOf(EPOCH_1, EPOCH_1),
            decoded.bloodPressures.map { it.timestamp.getDate().time / 1000 }
        )
    }

    @Test
    fun `bp_empty_records_returns_success_zero_without_network`() = runBlocking {
        val result = client.uploadBloodPressure(emptyList(), tokens())

        assertEquals(0, (result as UploadResult.Success).count)
        assertEquals("empty input must produce ZERO requests", 0, server.requestCount)
    }

    @Test
    fun `bp_indexed_failures_return_partial_with_slot_aware_keys`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"detailedImportResult":{"successes":[],"failures":[
                    {"index":0,"messages":["Duplicate detection - existing measurement"]}
                ]}}"""
            )
        )

        val result = client.uploadBloodPressure(
            listOf(bpReading(EPOCH_1, userSlot = 1), bpReading(EPOCH_1, userSlot = 2)),
            tokens()
        )

        assertTrue("expected Partial, got $result", result is UploadResult.Partial)
        val partial = result as UploadResult.Partial
        assertEquals(
            "succeededKeys are composite ts:slot keys — USER2 must survive USER1's failure",
            listOf("${EPOCH_1}:2"),
            partial.succeededKeys
        )
        assertEquals(listOf("Duplicate detection - existing measurement"), partial.failureMessages)
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun tokens(jwt: String = JWT): GarminTokens =
        GarminTokens(jwt, "rt-old", CLIENT_ID_2025Q2)

    private fun record(
        epochSecondsUtc: Long,
        weightKg: Float = 75.5f,
        percentFat: Float? = 22.5f,
        bmi: Float? = 24.5f
    ): GarminWeighInRecord =
        GarminWeighInRecord(epochSecondsUtc, weightKg, percentFat, bmi)

    private fun bpReading(
        epochSecondsUtc: Long,
        userSlot: Int = 1
    ): BloodPressureReading = BloodPressureReading(
        timestampEpochSec = epochSecondsUtc,
        systolic = 128,
        diastolic = 82,
        pulse = 62,
        mov = false,
        ihb = false,
        userSlot = userSlot
    )

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun htmlResponse(code: Int): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "text/html")
            .setBody(
                "<!DOCTYPE html><html><head><title>Just a moment...</title>" +
                    "</head><body>cf-challenge-window cloudflare</body></html>"
            )

    /** detailedImportResult with [count] successes and no failures. */
    private fun successBody(count: Int): String =
        """{"detailedImportResult":{"fileName":"body_composition.fit",""" +
            """"successes":[${repeatJson("\"index\":", count)}],"failures":[]}}"""

    private fun repeatJson(element: String, count: Int): String =
        (0 until count).joinToString(",") { """{$element$it}""" }

    private fun tokenJson(jwt: String, refreshToken: String = "di-rt-1"): String =
        """{"access_token":"$jwt","refresh_token":"$refreshToken","token_type":"Bearer","expires_in":3599}"""

    /**
     * Extracts the raw FIT part bytes from the captured multipart request
     * (Buffer clone — the RecordedRequest body is one-shot) and decodes them
     * with the official SDK [Decoder] into file_id + weight_scale messages.
     */
    private fun decodeCapturedFit(request: RecordedRequest): DecodedFit {
        val bytes = extractFitPartBytes(request)
        val decoder = Decoder(bytes)
        val fileIds = mutableListOf<FileIdMesg>()
        val weights = mutableListOf<WeightScaleMesg>()
        decoder.addListener { mesg: Mesg ->
            when (mesg.num) {
                MesgNum.FILE_ID -> fileIds += FileIdMesg(mesg)
                MesgNum.WEIGHT_SCALE -> weights += WeightScaleMesg(mesg)
            }
        }
        decoder.read()
        return DecodedFit(fileIds, weights)
    }

    private fun extractFitPartBytes(request: RecordedRequest): ByteArray {
        val boundary = request.getHeader("Content-Type")!!
            .substringAfter("boundary=")
            .trim()
        val body = request.body.clone().readByteArray()
        val delimiter = "--$boundary".toByteArray()
        val headerEndMarker = "\r\n\r\n".toByteArray()

        val firstDelimiter = body.indexOf(delimiter)
        assertTrue("multipart boundary not found", firstDelimiter >= 0)
        val partStart = firstDelimiter + delimiter.size

        val headerEnd = body.indexOf(headerEndMarker, partStart)
        assertTrue("part headers not terminated", headerEnd >= 0)
        val contentStart = headerEnd + headerEndMarker.size

        val closingDelimiter = "\r\n--$boundary".toByteArray()
        var contentEnd = body.indexOf(closingDelimiter, contentStart)
        if (contentEnd < 0) {
            // Final boundary: content runs until the trailing "--boundary--".
            contentEnd = body.indexOf(delimiter, contentStart)
        }
        assertTrue("FIT part end not found", contentEnd > contentStart)
        return body.copyOfRange(contentStart, contentEnd)
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Decodes the captured multipart FIT part into blood-pressure messages. */
    private fun decodeCapturedBpFit(request: RecordedRequest): DecodedBpFit {
        val bytes = extractFitPartBytes(request)
        val decoder = Decoder(bytes)
        val fileIds = mutableListOf<FileIdMesg>()
        val bloodPressures = mutableListOf<BloodPressureMesg>()
        decoder.addListener { mesg: Mesg ->
            when (mesg.num) {
                MesgNum.FILE_ID -> fileIds += FileIdMesg(mesg)
                MesgNum.BLOOD_PRESSURE -> bloodPressures += BloodPressureMesg(mesg)
            }
        }
        decoder.read()
        return DecodedBpFit(fileIds, bloodPressures)
    }

    private class DecodedFit(
        val fileIds: List<FileIdMesg>,
        val weights: List<WeightScaleMesg>
    )

    private class DecodedBpFit(
        val fileIds: List<FileIdMesg>,
        val bloodPressures: List<BloodPressureMesg>
    )

    companion object {
        private const val CLIENT_ID_2025Q2 = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"

        /** 2025-10-09T09:46:40Z. */
        private const val EPOCH_1 = 1_760_000_000L
        private const val EPOCH_2 = EPOCH_1 + 3_600
        private const val EPOCH_3 = EPOCH_1 + 7_200

        private val JWT = fakeJwt(System.currentTimeMillis() / 1000 + 86_400)
        private val FRESH_JWT = fakeJwt(System.currentTimeMillis() / 1000 + 86_400)

        private fun fakeJwt(
            expEpochSeconds: Long,
            clientId: String = CLIENT_ID_2025Q2
        ): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
            val payload = encoder.encodeToString(
                """{"exp":$expEpochSeconds,"client_id":"$clientId"}""".toByteArray()
            )
            return "$header.$payload.sig"
        }
    }
}
