package com.ai.limbs.plugincenter.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A bounded HTTPS JSON transport. No receiver database or package upload is implemented here. */
internal class MigrationRegistrationClient(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    companion object {
        fun validateEndpoint(value: String): URI {
            check(value.length in 1..2048 && value.none { it.isISOControl() }) { "SELF_UPLOAD_ENDPOINT_INVALID" }
            val uri = URI(value)
            check(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.rawQuery == null && (uri.port == -1 || uri.port in 1..65535)) { "SELF_UPLOAD_HTTPS_ENDPOINT_REQUIRED" }
            return uri
        }
    }
    fun upload(endpoint: String, accessToken: String, descriptor: JSONObject): JSONObject {
        val url = validateEndpoint(endpoint).toURL()
        check(accessToken.length <= 4096 && accessToken.none { it.isISOControl() }) { "SELF_UPLOAD_TOKEN_INVALID" }
        check(descriptor.getBoolean("saved_export")) { "SELF_EXPORT_SAVE_REQUIRED" }
        val payload = JSONObject(descriptor.toString()).apply { remove("saved_export"); remove("success") }
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = open(url)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", payload.getString("migration_id"))
            if (accessToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode in 200..299) { "SELF_UPLOAD_HTTP_${connection.responseCode}" }
            check(connection.contentType?.substringBefore(';')?.trim()?.equals("application/json", true) == true) { "SELF_UPLOAD_RESPONSE_TYPE_INVALID" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 65536) { "SELF_UPLOAD_RESPONSE_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val response = JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            check(response.getString("protocol") == "AIL_SELF_REGISTRATION_V1" && response.getBoolean("received")) { "SELF_UPLOAD_NOT_CONFIRMED" }
            for (field in listOf("identity_id", "migration_id", "package_sha256"))
                check(response.getString(field) == payload.getString(field)) { "SELF_UPLOAD_RECEIPT_MISMATCH" }
            check(response.getString("receipt_id").isNotBlank() && response.getString("receipt_id").length <= 256) { "SELF_UPLOAD_RECEIPT_INVALID" }
            // Store only the checked acknowledgment fields; never retain credentials or arbitrary server data.
            return JSONObject().put("protocol", response.getString("protocol")).put("received", true)
                .put("identity_id", response.getString("identity_id")).put("migration_id", response.getString("migration_id"))
                .put("package_sha256", response.getString("package_sha256")).put("receipt_id", response.getString("receipt_id"))
        } finally { connection.disconnect() }
    }
}
