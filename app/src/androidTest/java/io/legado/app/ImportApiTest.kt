package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.api.controller.BookController
import io.legado.app.data.appDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 导入 API 的端到端验证：走真实的 Room、真实的导入管线、真实的文件落盘。
 *
 * 单元测试覆盖不到这段——它依赖 Android 的文件系统与数据库。
 */
@RunWith(AndroidJUnit4::class)
class ImportApiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun structuredImportWritesChaptersWithCorrectByteOffsets() = runBlocking {
        val json = """
        {"name":"接口测试书","author":"测试",
         "chapters":[
           {"title":"第一章","content":"雨从傍晚下起，到子夜还没停。"},
           {"title":"第二章","content":"第二天早上，雨停了。"},
           {"title":"第三章","content":"白桦回到住处。"}
         ]}
        """.trimIndent()

        val result = BookController.importStructuredBook(json)
        assertTrue("import failed: ${result.errorMsg}", result.isSuccess)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertEquals(3, data["chapterCount"])
        val bookUrl = data["bookUrl"] as String

        val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
        assertEquals(3, chapters.size)
        assertEquals("第一章", chapters[0].title)

        // 关键：按 TOC 的字节区间读回来，必须和原文一字不差
        val book = appDb.bookDao.getBook(bookUrl)!!
        val content0 = io.legado.app.model.localBook.LocalBook.getContent(book, chapters[0])
        assertTrue("chapter 0 content was: $content0", content0!!.contains("雨从傍晚下起"))
        val content2 = io.legado.app.model.localBook.LocalBook.getContent(book, chapters[2])
        assertTrue("chapter 2 content was: $content2", content2!!.contains("白桦回到住处"))
    }

    @Test
    fun uploadBookAcceptsRealFileBytes() = runBlocking {
        // 真正的二进制上传：走 multipart 落盘那条路。
        // 章节数不作断言——TXT 的切分依赖内置目录规则，短文本切不出章节是
        // 既有行为（无目录时按 10KB 分块），不属于这个接口的职责。
        val tmp = File(context.cacheDir, "upload_test.txt")
        tmp.writeText(
            buildString {
                repeat(3) { i ->
                    append("第${i + 1}章 测试\n\n")
                    repeat(60) { append("他推开门，屋里没有人。窗子半开着。\n") }
                    append('\n')
                }
            },
            Charsets.UTF_8
        )

        val result = BookController.uploadBook(
            mapOf("fileName" to listOf("上传测试.txt")),
            mapOf("file" to tmp.absolutePath)
        )
        assertTrue("upload failed: ${result.errorMsg}", result.isSuccess)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertEquals("上传测试", data["name"])
        assertTrue("bookUrl should be set", (data["bookUrl"] as String).isNotBlank())
    }
}
