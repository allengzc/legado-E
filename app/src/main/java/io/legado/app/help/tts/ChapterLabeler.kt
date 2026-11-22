package io.legado.app.help.tts

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 用 LLM 给一章标「谁在说话、什么情绪」。
 *
 * 分工是刻意的：**切句由代码做，标注才交给模型**。
 * - 切句是确定性的，交给模型只会更慢更贵，还可能切坏；
 * - 标注需要理解上下文，这是模型唯一不可替代的地方。
 *
 * 成本控制（都是实测踩出来的）：
 * - 只发「编号 + 文本 + 角色表」，绝不要把章节正文整章发进去；
 * - 机械任务必须关思维链：开着时 512 token 预算会被推理吃光，正文为空；
 * - 分批，每批约 [batchSize] 条，`max_tokens` 按条数估算，否则工具参数会被截断。
 */
class ChapterLabeler(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val batchSize: Int = 120,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val NARRATOR = "旁白"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 逐章标注的系统提示词。逐字沿用，它是在真实中文小说上挑过的：
         * 措辞一改，说话人归属准确率就掉。
         */
        val LABEL_SYSTEM = """
你是中文小说的配音导演。你会收到若干条已按代码切分好的语音单元（带编号），
以及这本书已有的角色表。你的唯一任务：为每个编号标注说话人、情绪和类型。

只输出 JSON，不要输出任何解释文字。规则：
- i 必须与输入编号一一对应，不能新增、不能遗漏、不能改顺序。
- speaker：优先使用角色表里已有的名字；若是表中没有的新角色，直接给一个简短的名字（2–4 字），旁白统一用「$NARRATOR」。
- emotion：2–4 个字，如「平静」「紧张」「讥讽」「哽咽」「低声」；没有明显情绪就写「平静」。
- kind：narration（旁白）/ dialogue（对白）/ inner（心声）。
        """.trimIndent()

        val JSON_ONLY = "只输出一个 JSON 对象，不要输出解释文字，不要使用 Markdown 代码块。"

        /** 每句字数上限：超过 80 字中文韵律会垮、标注也会糊。 */
        const val MAX_UNIT = 80

        fun labelUser(units: List<String>, startIndex: Int, roster: List<TtsRole>): String {
            val sb = StringBuilder()
            sb.append("角色表：\n- ").append(NARRATOR).append("（旁白）\n")
            roster.filter { it.id != TtsRole.NARRATOR }.forEach { r ->
                sb.append("- ").append(r.name)
                if (r.role.isNotBlank()) sb.append("（").append(r.role).append("）")
                sb.append('\n')
            }
            sb.append("\n语音单元（共 ").append(units.size)
                .append(" 条，编号从 ").append(startIndex).append(" 开始）：\n")
            units.forEachIndexed { i, text ->
                sb.append(startIndex + i).append('｜').append(text).append('\n')
            }
            sb.append("\n请输出 ")
                .append("""{"labels":[{"i":""").append(startIndex)
                .append(""","speaker":"旁白","emotion":"平静","kind":"narration"}]}""")
                .append(" 这样的 JSON，覆盖每一条。")
            return sb.toString()
        }

        fun budget(units: Int): Int = minOf(16384, maxOf(4096, units * 60))
    }

    data class Label(val i: Int, val speaker: String, val emotion: String, val kind: String)

    sealed class Result {
        data class Ok(val labels: List<Label>) : Result()
        data class Err(val message: String) : Result()
    }

    private val json = okhttp3.MediaType.Companion.let { "application/json".toMediaType() }

    /**
     * @param units 已切好的句子，顺序即编号
     */
    fun label(units: List<String>, roster: List<TtsRole>): Result {
        if (units.isEmpty()) return Result.Ok(emptyList())
        val all = mutableListOf<Label>()
        units.chunked(batchSize).forEachIndexed { bi, batch ->
            val startIndex = bi * batchSize
            when (val r = labelBatch(batch, startIndex, roster)) {
                is Result.Ok -> all += r.labels
                is Result.Err -> return r
            }
        }
        return Result.Ok(all)
    }

    private fun labelBatch(batch: List<String>, startIndex: Int, roster: List<TtsRole>): Result {
        val messages = listOf(
            mapOf("role" to "system", "content" to (LABEL_SYSTEM + "\n\n" + JSON_ONLY)),
            mapOf("role" to "user", "content" to labelUser(batch, startIndex, roster))
        )
        val body = GSON.toJson(
            linkedMapOf(
                "model" to model,
                "messages" to messages,
                // 关思维链：机械任务开着它只会烧预算
                "thinking" to mapOf("type" to "disabled"),
                "temperature" to 0.1,
                "max_tokens" to budget(batch.size)
            )
        )
        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(json))
            .build()
        val (code, bytes) = try {
            client.newCall(request).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
        } catch (e: Exception) {
            return Result.Err("标注请求失败：${e.localizedMessage}")
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (code != 200) return Result.Err("标注 HTTP $code：${text.take(200)}")
        val content = extractContent(text) ?: return Result.Err("标注响应无内容：${text.take(200)}")
        val labels = LabelParser.parse(content)
        return if (labels.isEmpty()) Result.Err("标注解析为空") else Result.Ok(labels)
    }

    private fun endpoint(): String {
        var raw = baseUrl.trim().trimEnd('/')
        if (!raw.endsWith("/v1")) raw += "/v1"
        return "$raw/chat/completions"
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractContent(raw: String): String? {
        val root = GSON.fromJsonObject<Map<String, Any?>>(raw).getOrNull() ?: return null
        val choices = root["choices"] as? List<Map<String, Any?>> ?: return null
        val message = choices.firstOrNull()?.get("message") as? Map<String, Any?> ?: return null
        return message["content"] as? String
    }
}

/**
 * 容错解析器。
 *
 * 实测模型会输出三种形态：规范 JSON、裸数组、markdown 围栏；键名还会在
 * `i`/`index`、`speaker`/`speakerName` 之间摇摆。全都接受，否则每次标注都要
 * 白花一轮修复。
 */
object LabelParser {

    private val FENCE = Regex("""```(?:json)?\s*([\s\S]*?)```""")

    fun parse(raw: String): List<ChapterLabeler.Label> {
        val text = FENCE.find(raw)?.groupValues?.get(1) ?: raw
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val arr = GSON.fromJsonObject<List<Map<String, Any?>>>(text.substring(start, end + 1))
            .getOrNull() ?: return emptyList()
        return arr.mapNotNull { o ->
            val i = (o["i"] ?: o["index"])?.toString()?.toIntOrNull() ?: return@mapNotNull null
            ChapterLabeler.Label(
                i = i,
                speaker = (o["speaker"] ?: o["speakerName"])?.toString()?.trim().orEmpty()
                    .ifBlank { "旁白" },
                emotion = (o["emotion"])?.toString()?.trim().orEmpty().ifBlank { "平静" },
                kind = (o["kind"] ?: o["type"])?.toString()?.trim().orEmpty().ifBlank { "narration" }
            )
        }
    }
}
