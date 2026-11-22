package io.legado.app.help.tts

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实网络测试。默认跳过；`-Dlegado.live=1` 且提供 ENW_API/ENW_API_key 时运行。
 * 它不是回归测试，而是「这套提示词和接口在真服务上确实能跑」的证据。
 */
class LiveTtsTest {

    private fun enabled(): Pair<String, String>? {
        assumeTrue(System.getProperty("legado.live") == "1")
        val base = System.getenv("ENW_API") ?: return null
        val key = System.getenv("ENW_API_key") ?: return null
        return base to key
    }

    @Test
    fun `端到端：切分 标注 配声 合成`() {
        val (base, key) = enabled() ?: return
        val text = File("src/test/resources/sample_chapter.txt").readText(Charsets.UTF_8)
        val units = Segmenter.segment(text)
        println("units=${units.size}")
        units.take(6).forEach { println("  [${it.kind}] ${it.text}") }

        val labeler = ChapterLabeler(base, key, "deepseek-v4.1-flash")
        val roster = mutableListOf(TtsRole(TtsRole.NARRATOR, "旁白", role = "旁白"))
        when (val r = labeler.label(units.map { it.text }, roster)) {
            is ChapterLabeler.Result.Ok -> {
                println("labelled ${r.labels.size}/${units.size}")
                r.labels.map { it.speaker }.distinct().filter { it != "旁白" }.forEach { n ->
                    if (roster.none { it.name == n }) roster += TtsRole("sp_$n", n)
                }
                println("roster: " + roster.joinToString { "${it.name}->${MiMoVoices.pickFor(it)}" })
                units.zip(r.labels).take(12).forEach { (u, l) ->
                    val role = roster.firstOrNull { it.name == l.speaker }
                    println("  ${l.speaker.padEnd(3)} ${MiMoVoices.pickFor(role ?: roster[0])} [${l.emotion}/${l.kind}] ${u.text.take(22)}")
                }
                val tts = MiMoTtsClient(base, key)
                when (val a = tts.synthesize(units.first().text, "白桦", "用讲故事的语气，语速稍慢")) {
                    is MiMoTtsClient.Result.Ok -> {
                        File("/tmp/legado_mimo.mp3").writeBytes(a.audio)
                        println("TTS ok ${a.audio.size} bytes")
                    }
                    is MiMoTtsClient.Result.Err -> error("TTS failed: ${a.message}")
                }
            }
            is ChapterLabeler.Result.Err -> error("label failed: ${r.message}")
        }
    }
}
