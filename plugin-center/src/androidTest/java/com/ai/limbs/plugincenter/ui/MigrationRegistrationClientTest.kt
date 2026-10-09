package com.ai.limbs.plugincenter.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class MigrationRegistrationClientTest {
    private fun descriptor() = JSONObject().put("protocol", "AIL_SELF_REGISTRATION_V1").put("identity_id", "identity-fixture")
        .put("migration_id", "migration-fixture").put("package_sha256", "a".repeat(64)).put("saved_export", true)
        .put("package_size_bytes", 100).put("success", true)
    private fun receipt() = JSONObject().put("protocol", "AIL_SELF_REGISTRATION_V1").put("identity_id", "identity-fixture")
        .put("migration_id", "migration-fixture").put("package_sha256", "a".repeat(64)).put("received", true).put("receipt_id", "receipt-fixture")
    private class FakeConnection(private val response: String, private val code: Int = 200) : HttpURLConnection(URL("https://receiver.invalid/register")) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = response.byteInputStream()
        override fun getResponseCode() = code
        override fun getContentType() = "application/json; charset=utf-8"
    }
    private fun rejects(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: IllegalStateException) { assertEquals(code, error.message) }
    }
    @Test fun sendsOnlyMetadataAndAcceptsMatchingAcknowledgment() {
        val connection = FakeConnection(receipt().toString())
        val result = MigrationRegistrationClient { connection }.upload("https://receiver.invalid/register", "fixture-token", descriptor())
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("migration-fixture", connection.getRequestProperty("Idempotency-Key"))
        assertEquals("Bearer fixture-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(sent.has("saved_export")); assertFalse(sent.has("access_token")); assertFalse(sent.has("package_path"))
        assertTrue(result.getBoolean("received")); assertTrue(connection.closed)
    }
    @Test fun receiptMismatchAndRefusalNeverReportSuccess() {
        for (field in listOf("identity_id", "migration_id", "package_sha256")) {
            val connection = FakeConnection(receipt().put(field, "foreign").toString())
            rejects("SELF_UPLOAD_RECEIPT_MISMATCH") { MigrationRegistrationClient { connection }.upload("https://receiver.invalid/register", "", descriptor()) }
            assertTrue(connection.closed)
        }
        rejects("SELF_UPLOAD_NOT_CONFIRMED") { MigrationRegistrationClient { FakeConnection(receipt().put("received", false).toString()) }.upload("https://receiver.invalid/register", "", descriptor()) }
    }
    @Test fun rejectsRedirectOversizedResponseAndUnverifiedLocalSave() {
        rejects("SELF_UPLOAD_HTTP_302") { MigrationRegistrationClient { FakeConnection(receipt().toString(), 302) }.upload("https://receiver.invalid/register", "", descriptor()) }
        rejects("SELF_UPLOAD_RESPONSE_TOO_LARGE") { MigrationRegistrationClient { FakeConnection(" ".repeat(65537)) }.upload("https://receiver.invalid/register", "", descriptor()) }
        rejects("SELF_EXPORT_SAVE_REQUIRED") { MigrationRegistrationClient { error("Network must not open") }.upload("https://receiver.invalid/register", "", descriptor().put("saved_export", false)) }
    }
    @Test fun refusesInsecureAndCredentialBearingEndpoints() {
        for (endpoint in listOf("http://receiver.invalid/register", "https://user:password@receiver.invalid/register", "https://receiver.invalid/register?token=secret", "file:///register"))
            rejects("SELF_UPLOAD_HTTPS_ENDPOINT_REQUIRED") { MigrationRegistrationClient { error("Network must not open") }.upload(endpoint, "", descriptor()) }
    }
}
