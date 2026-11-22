package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.tts.ChapterLabeler
import io.legado.app.help.tts.MiMoTtsClient
import io.legado.app.help.tts.MiMoVoices
import io.legado.app.help.tts.Segmenter
import io.legado.app.help.tts.TtsRole
import io.legado.app.help.tts.TtsStyle
import io.legado.app.utils.GSON
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在 Android runtime 上跑通「切分 → 标注 → 分角色 → 合成」。
 * 单元测试跑在 JVM 上，验证不了 Android 的 Base64/OkHttp 行为。
 *
 * 需要 -Pandroid.testInstrumentationRunnerArguments.ENW_API=... 与 ENW_API_key
 */
@RunWith(AndroidJUnit4::class)
class RoleplayLiveTest {

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun endToEndRoleplay() {
        val base = args.getString("ENW_API")
        val key = args.getString("ENW_API_key")
        assumeTrue("no credentials", !base.isNullOrBlank() && !key.isNullOrBlank())

        val text = "雨从傍晚下起，到子夜还没停。\n\n" +
            "“你确定他今晚会来？”苏打把烟摁灭在窗台上。\n\n" +
            "“他不来，我们就白等了。”白桦没有抬头，“他说过的话，从来没不算数。”\n\n" +
            "“你们不该找我。”茉莉的声音很轻。\n"

        val units = Segmenter.segment(text)
        check(units.isNotEmpty()) { "segmentation produced nothing" }
        println("units=${units.size} kinds=${units.groupBy { it.kind }.mapValues { it.value.size }}")

        val roster = mutableListOf(TtsRole(TtsRole.NARRATOR, "旁白", role = "旁白"))
        val labeler = ChapterLabeler(base!!, key!!, "deepseek-v4.1-flash")
        val labelled = when (val r = labeler.label(units.map { it.text }, roster)) {
            is ChapterLabeler.Result.Ok -> r.labels
            is ChapterLabeler.Result.Err -> throw AssertionError("label failed: ${r.message}")
        }
        check(labelled.size == units.size) { "labelled ${labelled.size}/${units.size}" }

        val speakers = labelled.map { it.speaker }.distinct()
        println("speakers=$speakers")
        speakers.filter { it != "旁白" }.forEach { roster += TtsRole("sp_$it", it) }
        println("voice map=" + roster.joinToString { "${it.name}:${MiMoVoices.pickFor(it)}" })

        // 两个不同角色必须拿到不同音色，否则「分角色」名存实亡
        val voices = roster.map { MiMoVoices.pickFor(it) }
        check(voices.distinct().size >= minOf(3, roster.size)) {
            "voices collapsed: $voices"
        }

        // 真的合成，且要拿到可播放的 mp3 头
        val tts = MiMoTtsClient(base, key)
        val first = units.first()
        val style = TtsStyle.forRole(roster.first(), first.kind)
        when (val a = tts.synthesize(first.text, "白桦", style)) {
            is MiMoTtsClient.Result.Ok -> {
                println("tts bytes=${a.audio.size}")
                check(a.audio.size > 3000) { "audio suspiciously small: ${a.audio.size}" }
                val head = a.audio.copyOfRange(0, 3)
                // "ID3" (mp3 with tag) 或 0xFF 0xFB（裸 mp3 帧）
                val looksMp3 = (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte()) ||
                    (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
                check(looksMp3) { "not mp3: ${head.joinToString { "%02x".format(it) }}" }
            }
            is MiMoTtsClient.Result.Err -> throw AssertionError("tts failed: ${a.message}")
        }
    }
}
