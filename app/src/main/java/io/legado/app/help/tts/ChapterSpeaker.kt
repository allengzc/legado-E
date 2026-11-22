package io.legado.app.help.tts

import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import java.io.File

/**
 * 一章的「分角色稿」：切好的句子 + 每句的角色与情绪。
 *
 * 落盘成 JSON 缓存，键 = 章节内容哈希 + 提示词版本。重开同一章 0 token，
 * 改了音色或角色表才需要重算（见 [signature]）。
 */
class ChapterSpeaker(
    val segments: List<Segment>
) {
    data class Segment(
        /** 原文偏移，用于把朗读位置映射回正文 */
        val start: Int,
        val end: Int,
        /** 实际送 TTS 的文本（已去排版符号） */
        val text: String,
        /** narration / dialogue / inner */
        val kind: String,
        /** 角色 id；旁白为 [TtsRole.NARRATOR] */
        val speakerId: String,
        /** 2–4 字情绪，空表示没有明显情绪 */
        val emotion: String
    )

    companion object {
    /** 合成本章：切分（确定性）→ 标注（LLM）→ 落到角色 id。 */
    fun build(
        chapterText: String,
        units: List<Segmenter.Unit>,
        labeler: ChapterLabeler,
        roster: MutableList<TtsRole>
    ): Result<ChapterSpeaker> {
        if (units.isEmpty()) return Result.success(ChapterSpeaker(emptyList()))
        val labelled = when (val r = labeler.label(units.map { it.text }, roster)) {
            is ChapterLabeler.Result.Ok -> r.labels
            is ChapterLabeler.Result.Err -> return Result.failure(IllegalStateException(r.message))
        }
        val byIndex = labelled.associateBy { it.i }
        val segments = units.mapIndexed { index, unit ->
            val label = byIndex[index]
            val speakerName = label?.speaker?.trim().orEmpty().ifEmpty { "旁白" }
            val speakerId = if (speakerName == "旁白" || speakerName == "旁白") {
                TtsRole.NARRATOR
            } else {
                // 名字第一次出现时建角色；之后一律按名字精确匹配，绝不做模糊合并
                // （错合并会让两个角色整本书共用一把嗓子，比多一张卡糟得多）。
                roster.firstOrNull { it.name == speakerName }?.id
                    ?: TtsRole("sp_${MD5Utils.md5Encode16(speakerName)}", speakerName).also {
                        roster += it
                    }.id
            }
            if (label?.kind == "dialogue") {
                val idx = roster.indexOfFirst { it.id == speakerId }
                if (idx >= 0 && roster[idx].sampleLine.isBlank()) {
                    roster[idx] = roster[idx].copy(sampleLine = unit.text.take(60))
                }
            }
            Segment(
                start = unit.start,
                end = unit.end,
                text = unit.text,
                kind = label?.kind ?: unit.kind,
                speakerId = speakerId,
                emotion = label?.emotion.orEmpty()
            )
        }
        return Result.success(ChapterSpeaker(segments))
    }

    fun load(file: File): ChapterSpeaker? {
        if (!file.exists()) return null
        return runCatching { GSON.fromJson(file.readText(), ChapterSpeaker::class.java) }.getOrNull()
    }

    /** 写缓存。[speaker] 显式传入：companion 里的 `this` 是伴生对象，不是章节稿。 */
    fun save(file: File, speaker: ChapterSpeaker) {
        file.parentFile?.mkdirs()
        file.writeText(GSON.toJson(speaker))
    }

        /** 提示词版本：改了措辞就换它，旧缓存自动失效。 */
        const val PROMPT_VERSION = "legado.tts.v1"

        fun cacheKey(chapterText: String): String =
            MD5Utils.md5Encode16(PROMPT_VERSION + "|" + chapterText)

        /**
         * 角色表的签名。音色或角色增删都会改变它——否则用户换了音色，
         * 却还在听旧缓存里的那把嗓子。
         */
        fun signature(roster: List<TtsRole>): String =
            MD5Utils.md5Encode16(
                roster.sortedBy { it.id }
                    .joinToString("|") { "${it.id}:${MiMoVoices.pickFor(it)}:${it.role}" }
            )

        fun file(cacheDir: File, chapterText: String, roster: List<TtsRole>): File =
            File(cacheDir, "speaker_${cacheKey(chapterText)}_${signature(roster)}.json")
    }
}
