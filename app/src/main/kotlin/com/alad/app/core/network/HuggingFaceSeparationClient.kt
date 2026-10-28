package com.alad.app.core.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Supports both the custom ALAD /separate endpoint and public Gradio Spaces such as
 * https://huggingface.co/spaces/abidlabs/music-separation. Gradio's public API uses
 * /gradio_api/upload, /gradio_api/call/inference and an SSE result endpoint.
 */
class HuggingFaceSeparationClient(
    private val baseUrl: String,
    private val bearerToken: String? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(280, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(320, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    data class Stems(val vocalsWav: ByteArray, val instrumentalWav: ByteArray, val model: String)

    suspend fun separate(audioFile: File): Stems = withContext(Dispatchers.IO) {
        require(audioFile.isFile && audioFile.length() > 44) { "Tệp âm thanh không tồn tại hoặc quá ngắn" }
        val endpoint = normalizeBaseUrl(baseUrl)
        require(endpoint.startsWith("https://")) { "URL Hugging Face phải bắt đầu bằng https://" }

        // First try the dedicated ALAD API. This is the preferred interface for a Space we own.
        val customResult = tryCustomEndpoint(endpoint, audioFile)
        if (customResult != null) return@withContext customResult

        // Fallback to the real Gradio API exposed by abidlabs/music-separation.
        separateViaGradio(endpoint, audioFile)
    }

    private fun normalizeBaseUrl(input: String): String {
        val value = input.trim().trimEnd('/')
        val repo = Regex("^https://huggingface\\.co/spaces/([^/]+)/([^/?#]+).*$", RegexOption.IGNORE_CASE).matchEntire(value)
        if (repo != null) return "https://${repo.groupValues[1]}-${repo.groupValues[2]}.hf.space"
        return value.removeSuffix("/health").removeSuffix("/separate").removeSuffix("/gradio_api")
    }

    private fun tryCustomEndpoint(base: String, audioFile: File): Stems? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name,
                audioFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = requestBuilder("$base/separate").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404 || response.code == 405 || response.code == 501) return null
            if (!response.isSuccessful) {
                // A normal Gradio Space has no /separate endpoint; its 4xx response is expected.
                if (response.code in 400..499 && !text.contains("vocals_wav_base64")) return null
                throw IOException("Lỗi API tách âm thanh HTTP ${response.code}: ${text.take(500)}")
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) return null
            val vocals = json.optString("vocals_wav_base64")
            val instrumental = json.optString("instrumental_wav_base64")
            if (vocals.isBlank() || instrumental.isBlank()) throw IOException("API /separate thiếu hai tệp âm thanh đầu ra")
            return Stems(Base64.decode(vocals, Base64.DEFAULT), Base64.decode(instrumental, Base64.DEFAULT), json.optString("model", "demucs"))
        }
    }

    private suspend fun separateViaGradio(base: String, audioFile: File): Stems {
        // Gradio expects uploaded file references, not the local Android path.
        val uploadBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val uploadRequest = requestBuilder("$base/gradio_api/upload").post(uploadBody).build()
        val uploadedPaths = client.newCall(uploadRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Không tải được âm thanh lên Gradio (HTTP ${response.code}): ${body.take(500)}")
            val arr = try { JSONArray(body) } catch (_: Exception) { JSONArray().put(body.trim().trim('"')) }
            if (arr.length() == 0) throw IOException("Gradio không trả về đường dẫn tệp đã tải lên")
            arr.getString(0)
        }

        val fileData = JSONObject().put("path", uploadedPaths)
            .put("orig_name", audioFile.name)
            .put("mime_type", "audio/wav")
            .put("meta", JSONObject().put("_type", "gradio.FileData"))
        val payload = JSONObject().put("data", JSONArray().put(fileData))
        val predictRequest = requestBuilder("$base/gradio_api/call/inference")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val eventId = client.newCall(predictRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gradio inference không nhận yêu cầu (HTTP ${response.code}): ${body.take(500)}")
            JSONObject(body).optString("event_id").takeIf { it.isNotBlank() }
                ?: throw IOException("Gradio không trả về event_id: ${body.take(300)}")
        }

        // Gradio streams event updates as Server-Sent Events. Wait for the final completion event.
        val resultRequest = requestBuilder("$base/gradio_api/call/inference/$eventId").get().build()
        val resultJson = client.newCall(resultRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Không lấy được kết quả Demucs (HTTP ${response.code}): ${body.take(500)}")
            parseFinalSse(body)
        }
        val data = resultJson.optJSONArray("data") ?: throw IOException("Gradio trả về kết quả không có trường data")
        if (data.length() < 2) throw IOException("Gradio không trả đủ hai đầu ra Vocals và Instrumental: ${data.toString().take(400)}")
        val vocalsUrl = extractOutputUrl(base, data.get(0))
        val instrumentalUrl = extractOutputUrl(base, data.get(1))
        val vocals = downloadOutput(vocalsUrl)
        val instrumental = downloadOutput(instrumentalUrl)
        if (vocals.size < 44 || instrumental.size < 44) throw IOException("Demucs trả về tệp âm thanh rỗng hoặc không hợp lệ")
        return Stems(vocals, instrumental, "abidlabs/music-separation · Gradio")
    }

    private fun parseFinalSse(body: String): JSONObject {
        var lastJson: JSONObject? = null
        var lastError: String? = null
        for (line in body.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val value = line.substringAfter("data:").trim()
            if (value.isBlank() || value == "null") continue
            try {
                val obj = JSONObject(value)
                if (obj.has("error") && !obj.isNull("error")) lastError = obj.optString("error")
                if (obj.has("data")) lastJson = obj
            } catch (_: Exception) { /* Ignore keepalive/non-JSON SSE frames. */ }
        }
        if (lastError != null) throw IOException("Demucs báo lỗi: $lastError")
        return lastJson ?: throw IOException("Gradio chưa trả kết quả hoàn tất. Hãy thử lại sau; phản hồi: ${body.takeLast(500)}")
    }

    private fun extractOutputUrl(base: String, value: Any): String {
        if (value is String) {
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            if (value.startsWith("/gradio_api/file=")) return "$base$value"
            if (value.startsWith("/tmp/") || value.startsWith("tmp/")) {
                val path = if (value.startsWith("/")) value else "/$value"
                return "$base/gradio_api/file=$path"
            }
            if (value.startsWith("/")) return "$base$value"
            throw IOException("Định dạng đầu ra Gradio không được hỗ trợ: $value")
        }
        if (value is JSONObject) {
            val url = value.optString("url")
            if (url.isNotBlank() && url != "null") return if (url.startsWith("http")) url else "$base$url"
            val path = value.optString("path")
            if (path.startsWith("/")) return "$base/gradio_api/file=$path"
        }
        throw IOException("Gradio trả về định dạng tệp âm thanh không nhận dạng được")
    }

    private fun downloadOutput(url: String): ByteArray {
        val request = requestBuilder(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Không tải được stem âm thanh (HTTP ${response.code})")
            return response.body?.bytes() ?: throw IOException("Tệp âm thanh đầu ra trống")
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", "ALAD-Mobile/1.0")
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
        return builder
    }
}
