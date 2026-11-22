package io.legado.app.help.tts

import io.legado.app.constant.AppPattern

/**
 * 段落 → 可朗读的「语音单元」
 *
 * 为什么要自己切：legado 的朗读列表是**按段落**给的，一个段落可能同时包含
 * 旁白、对白和心声。整段丢给 TTS，就只能用一把嗓子把三个人的话念完——
 * 这是「机器味」最主要的来源。
 *
 * 这里的规则全部是确定性的（不花 token）：
 * - 引号对决定类型：「」"" ＝ 对白，『』（）＝ 心声；
 * - 只在句末标点切分，**不在逗号处切**——切在逗号会得到「雨从傍晚下起，」
 *   这种半句，读起来尾音上扬，而且 TTS 请求数翻倍；
 * - 纯标点的碎片不能单独成段，并进上一段（真实书里出现过 72/348 段全是
 *   标点，全部付费渲染成静音）。
 */
object Segmenter {

    data class QuotePair(val open: Char, val close: Char, val kind: String)

    /** kind: dialogue / inner */
    val defaultQuotes = listOf(
        QuotePair('「', '」', "dialogue"),
        QuotePair('『', '』', "inner"),
        QuotePair('“', '”', "dialogue"),
        QuotePair('‘', '’', "inner"),
        QuotePair('＂', '＂', "dialogue"),
        QuotePair('"', '"', "dialogue"),
        QuotePair('（', '）', "inner"),
        QuotePair('(', ')', "inner")
    )

    /** 句末标点。逗号不在内。 */
    private val TERMINALS = charArrayOf('。', '！', '？', '…', '；', '!', '?', ';', '．')

    /** 收尾标点，跟在句末之后仍属于同一句。 */
    private const val TRAILING = "”’＂\"』」）)》】〕〉］]…—"

    private val INVISIBLES = "\u200B\u200C\u200D\uFEFF\u2060"

    /** 不发音的排版符号。句读标点不在此列。 */
    private const val TYPOGRAPHY = "“”‘’「」『』\"'＂《》【】（）()—…·~～"
    private val READABLE = Regex("""[\p{L}\p{N}]""")

    /**
     * @param chapterText 整章或整段文本
     * @param maxUnit 单句上限
     */
    fun segment(chapterText: String, maxUnit: Int = ChapterLabeler.MAX_UNIT): List<Unit> {
        val out = mutableListOf<Unit>()
        var cursor = 0
        for (line in chapterText.split('\n')) {
            if (line.isNotBlank()) {
                segmentLine(line, cursor, maxUnit, out)
            }
            cursor += line.length + 1
        }
        return out
    }

    data class Unit(
        /** 在原始文本中的起始偏移，用于把朗读位置映射回正文 */
        val start: Int,
        val end: Int,
        val text: String,
        /** narration / dialogue / inner */
        val kind: String
    )

    private fun segmentLine(line: String, base: Int, maxUnit: Int, out: MutableList<Unit>) {
        var i = 0
        while (i < line.length) {
            val hit = nextOpener(line, i)
            if (hit == null) {
                addRuns(line, i, line.length, base, "narration", maxUnit, out)
                return
            }
            if (hit.first > i) addRuns(line, i, hit.first, base, "narration", maxUnit, out)
            val close = line.indexOf(hit.second.close, hit.first + 1)
            if (close < 0) {
                addRuns(line, hit.first + 1, line.length, base, hit.second.kind, maxUnit, out)
                return
            }
            addRuns(line, hit.first + 1, close, base, hit.second.kind, maxUnit, out)
            i = close + 1
        }
    }

    private fun nextOpener(text: String, from: Int): Pair<Int, QuotePair>? {
        var best: Pair<Int, QuotePair>? = null
        for (q in defaultQuotes) {
            val idx = text.indexOf(q.open, from)
            if (idx >= 0 && (best == null || idx < best.first)) best = idx to q
        }
        return best
    }

    private fun addRuns(
        line: String,
        from: Int,
        to: Int,
        base: Int,
        kind: String,
        maxUnit: Int,
        out: MutableList<Unit>
    ) {
        var i = from
        while (i < to) {
            while (i < to && line[i].isWhitespace()) i++
            if (i >= to) break
            val start = i
            var end = i
            var terminal = false
            while (end < to) {
                val c = line[end]
                if (c in TERMINALS) {
                    end++
                    terminal = true
                    while (end < to && (line[end].isWhitespace() || line[end] in TRAILING)) end++
                    break
                }
                end++
                if (end - start >= maxUnit) break
            }
            if (!terminal && end - start >= maxUnit) end = start + maxUnit
            val raw = line.substring(start, end).trim()
            val spoken = speechText(raw)
            if (spoken.isNotEmpty()) {
                out += Unit(base + start, base + end, spoken, kind)
            } else {
                // 纯标点：并进上一段（只扩范围），没有上一段就丢弃
                val last = out.lastOrNull()
                if (last != null && last.kind == kind) {
                    out[out.lastIndex] = last.copy(end = base + end)
                }
            }
            i = maxOf(end, start + 1)
        }
    }

    /**
     * 送给 TTS 的文本：去掉只该看的排版符号，保留驱动停顿的标点。
     *
     * 末尾的逗号要去掉——「指尖敲着，」这类悬空半句，MiMo 会读出一个上挑的
     * 收音，听起来像念错了。
     */
    fun speechText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c in INVISIBLES -> Unit
                c == '　' -> sb.append(' ')
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                // 只给眼睛看的排版符号：引号、破折号、省略号、书名号、括号。
                // 它们不发音，但会改变 TTS 的韵律（也会让缓存键变来变去）。
                c in TYPOGRAPHY -> Unit
                else -> sb.append(c)
            }
        }
        val cleaned = sb.toString()
            .replace(Regex(" {2,}"), " ")
            .trim()
            .trimEnd('，', '、', '：', '；', ',', ':', ';')
        return if (READABLE.containsMatchIn(cleaned)) cleaned else ""
    }

    /** 兼容 legado 原有的过滤规则，避免两套清洗逻辑打架。 */
    fun cleanForSpeech(text: String): String =
        speechText(text.replace(AppPattern.notReadAloudRegex, ""))
}
