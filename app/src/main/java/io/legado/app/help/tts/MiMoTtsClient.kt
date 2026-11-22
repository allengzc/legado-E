package io.legado.app.help.tts

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MiMo 语音合成客户端
 *
 * 小米 MiMo 的 TTS **不是** OpenAI 的 `/v1/audio/speech`：音频藏在 chat
 * completion 的响应里。
 *
 * ```
 * POST {base}/chat/completions
 * {
 *   "model": "mimo-v2.5-tts",
 *   "messages": [ {"role":"user","content":"<风格指令>"},
 *                 {"role":"assistant","content":"<正文>"} ],
 *   "audio": {"voice":"白桦","format":"mp3"}
 * }
 * → choices[0].message.audio.data (base64)
 * ```
 *
 * 原来的 HttpTTS 只支持「URL 模板直接返回音频流」，靠 content-type 判断成败，
 * 遇到 `application/json` 直接当错误抛出——所以这类接口根本接不进去。
 * 这里独立实现，不走 AnalyzeUrl，避免把 Rhino 规则引擎塞进朗读热路径。
 */
class MiMoTtsClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = PLAIN_MODEL,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        const val PLAIN_MODEL = "mimo-v2.5-tts"
        const val CLONE_MODEL = "mimo-v2.5-tts-voiceclone"
        const val DESIGN_MODEL = "mimo-v2.5-tts-voicedesign"
        const val DEFAULT_VOICE = "白桦"

        /** 实测：mp3 约为 wav 的 1/8 体积，往返延迟相同，默认 mp3。 */
        const val FORMAT = "mp3"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun isDesignModel(model: String) = model.contains("voicedesign")
        fun isCloneModel(model: String) = model.contains("voiceclone")
    }

    data class Settings(
        val baseUrl: String,
        val apiKey: String,
        val model: String = PLAIN_MODEL
    )

    /**
     * 纯函数构造请求体，方便单元测试锁死线上格式。
     *
     * @param style 自然语言风格指令；voicedesign 模型下它同时决定音色。
     */
    fun buildBody(text: String, voice: String?, style: String?): String {
        val isDesign = isDesignModel(model)
        val messages = buildList {
            if (isDesign) {
                add(mapOf("role" to "user", "content" to (style ?: "温和平静的旁白")))
            } else if (!style.isNullOrBlank()) {
                add(mapOf("role" to "user", "content" to style))
            }
            add(mapOf("role" to "assistant", "content" to text))
        }
        val audio = mutableMapOf<String, Any>("format" to FORMAT)
        if (isDesign) {
            audio["optimize_text_preview"] = false
        } else {
            audio["voice"] = voice ?: DEFAULT_VOICE
        }
        return GSON.toJson(mapOf("model" to model, "messages" to messages, "audio" to audio))
    }

    private fun endpoint(): String {
        var raw = baseUrl.trim().trimEnd('/')
        if (!raw.endsWith("/v1")) raw += "/v1"
        return "$raw/chat/completions"
    }

    sealed class Result {
        data class Ok(val audio: ByteArray) : Result()
        data class Err(val message: String) : Result()
    }

    /**
     * 同步合成一句。调用方在 IO 线程里用。
     *
     * 失败要返回可读原因，而不是静默替换成静音——TTS 出错时用户听到的应该是
     * 「为什么没声音」，不是一段没头没尾的空白。
     */
    fun synthesize(text: String, voice: String?, style: String?): Result {
        if (text.isBlank()) return Result.Err("空文本")
        val body = buildBody(text, voice, style)
        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val (code, bytes) = try {
            client.newCall(request).execute().use { resp ->
                resp.code to (resp.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: Exception) {
            return Result.Err("网络请求失败：${e.localizedMessage}")
        }
        if (code != 200) {
            return Result.Err("HTTP $code：${brief(bytes)}")
        }
        val json = bytes.toString(Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val root = GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()
            ?: return Result.Err("无法解析响应：${brief(bytes)}")
        (root["error"] as? Map<*, *>)?.let {
            return Result.Err("${it["code"] ?: ""}: ${it["message"] ?: ""}")
        }
        @Suppress("UNCHECKED_CAST")
        val choices = root["choices"] as? List<Map<String, Any?>>
            ?: return Result.Err("响应没有 choices：${brief(bytes)}")
        @Suppress("UNCHECKED_CAST")
        val message = choices.firstOrNull()?.get("message") as? Map<String, Any?>
            ?: return Result.Err("响应没有 message")
        @Suppress("UNCHECKED_CAST")
        val audio = message["audio"] as? Map<String, Any?>
        val encoded = audio?.get("data") as? String
        if (encoded.isNullOrBlank()) {
            val content = message["content"] as? String ?: ""
            return Result.Err("响应里没有音频：${content.take(120)}")
        }
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?: return Result.Err("audio.data 不是合法 base64")
        if (decoded.size < 512) return Result.Err("音频数据异常小（${decoded.size}B）")
        return Result.Ok(decoded)
    }

    private fun brief(bytes: ByteArray): String =
        bytes.copyOfRange(0, minOf(bytes.size, 240)).toString(Charsets.UTF_8).replace("\n", " ")
}
