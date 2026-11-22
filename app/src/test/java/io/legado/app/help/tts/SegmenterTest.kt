package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切分规则是「多少钱」的问题：每一段都是一个付费 TTS 请求。
 */
class SegmenterTest {

    @Test
    fun `只在句末标点切分，逗号不切`() {
        val units = Segmenter.segment("雨从傍晚下起，到子夜还没停。")
        assertEquals(1, units.size)
        assertEquals("雨从傍晚下起，到子夜还没停。", units[0].text)
    }

    @Test
    fun `引号对决定类型`() {
        val units = Segmenter.segment("他说：“我明天就走。”然后沉默了。")
        assertEquals("narration", units[0].kind)
        assertEquals("dialogue", units[1].kind)
        assertEquals("我明天就走。", units[1].text)
        assertEquals("narration", units[2].kind)
    }

    @Test
    fun `直角引号是心声`() {
        val units = Segmenter.segment("『他为什么要骗我？』她想。")
        assertEquals("inner", units[0].kind)
    }

    @Test
    fun `纯标点不单独成段`() {
        val units = Segmenter.segment("他说完就走了。\n\n***\n\n她站在原地。")
        assertEquals(listOf("他说完就走了。", "她站在原地。"), units.map { it.text })
    }

    @Test
    fun `超长句强制切断`() {
        val long = "他".repeat(200)
        val units = Segmenter.segment(long, maxUnit = 80)
        assertTrue(units.all { it.text.length <= 80 })
        assertTrue(units.size >= 3)
    }

    @Test
    fun `朗读文本去掉排版符号保留停顿标点`() {
        val out = Segmenter.speechText("“《人间》——他说……然后走了。”")
        assertTrue(!out.contains("“"))
        assertTrue(!out.contains("《"))
        assertTrue(!out.contains("—"))
        assertTrue(out.contains("。"))
    }

    @Test
    fun `悬空的结尾逗号会被去掉`() {
        assertEquals("苏打把烟摁灭在窗台上", Segmenter.speechText("苏打把烟摁灭在窗台上，"))
    }

    @Test
    fun `零宽字符不影响朗读文本`() {
        assertEquals("他说完就走了。", Segmenter.speechText("他​说﻿完　就走了。").replace(" ", ""))
    }

    @Test
    fun `偏移能映射回原文`() {
        val text = "夜深了。\n\n他还在等。"
        val units = Segmenter.segment(text)
        for (u in units) {
            val slice = text.substring(u.start, u.end).trim()
            assertTrue("offset must contain the sentence: $slice", slice.contains(u.text) || u.text.contains(slice))
        }
    }

    @Test
    fun `切分是确定性的`() {
        val text = "第一句。第二句。“第三句？”"
        assertEquals(
            Segmenter.segment(text).map { it.text },
            Segmenter.segment(text).map { it.text }
        )
    }
}

class TtsRoleTest {

    @Test
    fun `同名角色每次拿到同一个音色`() {
        val a = TtsRole(id = "1", name = "林晚", gender = "女")
        val b = TtsRole(id = "1", name = "林晚", gender = "女")
        assertEquals(MiMoVoices.pickFor(a), MiMoVoices.pickFor(b))
    }

    @Test
    fun `女性角色只会分到女声`() {
        val voice = MiMoVoices.pickFor(TtsRole(id = "x", name = "林晚", gender = "女"))
        assertTrue(voice in setOf("冰糖", "茉莉", "Mia", "Chloe"))
    }

    @Test
    fun `旁白固定用说书人音色`() {
        assertEquals("白桦", MiMoVoices.pickFor(TtsRole(id = TtsRole.NARRATOR, name = "旁白")))
    }

    @Test
    fun `角色名恰好是内置音色时尊重原意`() {
        assertEquals("茉莉", MiMoVoices.pickFor(TtsRole(id = "x", name = "茉莉")))
    }

    @Test
    fun `判不出性别时用全池子而不是瞎猜`() {
        assertEquals("", MiMoVoices.inferGender(TtsRole(id = "x", name = "无名")))
    }

    @Test
    fun `旁白风格带讲故事语气`() {
        val style = TtsStyle.forRole(TtsRole(id = TtsRole.NARRATOR, name = "旁白"), "narration")
        assertTrue(style.contains("讲故事"))
    }

    @Test
    fun `情绪会拼进风格指令`() {
        val role = TtsRole(id = "x", name = "苏打", gender = "男")
        val style = TtsStyle.forRole(role, "dialogue", "讥讽")
        assertTrue(style.contains("讥讽"))
    }
}

class LabelParserTest {

    @Test
    fun `解析规范 labels 对象`() {
        val raw = """{"labels":[{"i":0,"speaker":"旁白","emotion":"平静","kind":"narration"},{"i":1,"speaker":"林晚","emotion":"讥讽","kind":"dialogue"}]}"""
        val out = LabelParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("林晚", out[1].speaker)
    }

    @Test
    fun `解析裸数组与 markdown 围栏`() {
        val raw = "```json\n[{\"index\":3,\"speakerName\":\"白桦\",\"emotion\":\"低沉\",\"type\":\"inner\"}]\n```"
        val out = LabelParser.parse(raw)
        assertEquals(1, out.size)
        assertEquals(3, out[0].i)
        assertEquals("白桦", out[0].speaker)
    }

    @Test
    fun `缺少情绪时默认平静`() {
        val out = LabelParser.parse("""{"labels":[{"i":0}]}""")
        assertEquals("平静", out[0].emotion)
    }

    @Test
    fun `无关文本返回空`() {
        assertTrue(LabelParser.parse("抱歉，我无法完成。").isEmpty())
    }
}
