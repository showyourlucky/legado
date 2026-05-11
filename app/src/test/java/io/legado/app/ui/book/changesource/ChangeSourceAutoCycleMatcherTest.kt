package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSourceAutoCycleMatcherTest {

    @Test
    fun `章节名标准化后可匹配不同序号同标题`() {
        val chapters = listOf(
            BookChapter(title = "第11节 明天", index = 10),
            BookChapter(title = "第22章 昨天", index = 21),
            BookChapter(title = "第20章 明天", index = 19)
        )

        val matchedIndex = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "第222章 明天",
            chapters = chapters
        )

        assertEquals(0, matchedIndex)
    }

    @Test
    fun `章节标题尾部字数尾注会被忽略`() {
        val chapters = listOf(
            BookChapter(title = "第425章 回家(3K)", index = 424)
        )

        val matchedIndex = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "第425章 回家",
            chapters = chapters
        )

        assertEquals(0, matchedIndex)
    }

    @Test
    fun `正文标准化后忽略空格换行和逗号`() {
        val previewA = ChangeSourceAutoCycleMatcher.buildComparablePreview("天 气，真\n好，准备出发")
        val previewB = ChangeSourceAutoCycleMatcher.buildComparablePreview("天气真好准备出发")

        assertEquals(previewA, previewB)
    }

    @Test
    fun `正文有效字符不足时返回空`() {
        val preview = ChangeSourceAutoCycleMatcher.buildComparablePreview(
            content = " \n，。！？",
            minEffectiveCharCount = 1
        )

        assertNull(preview)
    }

    @Test
    fun `正文真实不同会得到不同预览`() {
        val previewA = ChangeSourceAutoCycleMatcher.buildComparablePreview("天气真好准备出发")
        val previewB = ChangeSourceAutoCycleMatcher.buildComparablePreview("天气不好准备回家")

        assertFalse(previewA == previewB)
    }

    @Test
    fun `正文开头带站点前缀时会先对齐再比较`() {
        val previewA = ChangeSourceAutoCycleMatcher.buildComparablePreview(
            "今天天气很好适合出门散步然后去买菜顺便喝杯咖啡最后回家看书"
        )!!
        val previewB = ChangeSourceAutoCycleMatcher.buildComparablePreview(
            "aaa想小说网今天天气很好适合出门散步然后去买菜顺便喝杯咖啡最后回家看书"
        )!!

        val compareResult = ChangeSourceAutoCycleMatcher.comparePreview(previewA, previewB)

        assertTrue(compareResult.sameContent)
        assertNotNull(compareResult.diff)
    }

    /**
     * 精确匹配失败时，若传入 currentChapterIndex > 0 则复用 BookHelp.getDurChapter 模糊匹配兜底，
     * 不再简单返回 -1，从而避免“手动换源能成功但自动换源直接跳过”的回归。
     *
     * 此用例验证精确匹配失败 + 传入当前章节索引时，不再返回 -1（是否真正匹配到取决于 BookHelp.getDurChapter 的实现）。
     */
    @Test
    fun `精确匹配失败时若传入章节索引则不轻易返回负一`() {
        val chapters = listOf(
            BookChapter(title = "第11节 明天", index = 10),
            BookChapter(title = "第22章 昨天", index = 21),
            BookChapter(title = "第20章 明天", index = 19)
        )
        // 当前章节标题标准化后与候选目录中任何一个都不相等（精确匹配失败）
        // 但因为传入了 currentChapterIndex > 0，会触发模糊兜底而非直接返回 -1
        val result = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "第222章 明天",
            chapters = chapters,
            currentChapterIndex = 3,  // 传入手动换源会传入的当前章节索引
            oldChapterListSize = 100
        )
        // 模糊匹配可能找到也可能找不到（取决于 Jaccard 相似度阈值），
        // 但只要返回 >= 0 就说明没有简单返回 -1，符合“尝试模糊兜底”的设计
        assertTrue("精确匹配失败时应尝试模糊兜底，结果=$result", result >= 0)
    }

    @Test
    fun `标题和章节号都不匹配时不能接受模糊兜底结果`() {
        val chapters = listOf(
            BookChapter(title = "第487章 愉悦", index = 486)
        )

        val matchedIndex = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "第425章 回家",
            chapters = chapters,
            currentChapterIndex = 424,
            oldChapterListSize = 425
        )

        assertEquals(-1, matchedIndex)
    }

    /**
     * 当两个源的章节号差异大（如425 vs 499）时，getDurChapter 的 ±10 窗口无法覆盖目标，
     * 全目录按章节号兜底搜索应能正确匹配到同章节号的章节。
     *
     * 场景：源A 第425章"回家"，源B 有 第425章"明天" 和 第499章"回家"。
     * 期望：匹配到第499章"回家"（章节号一致 + 标题一致），而非第425章"明天"。
     */
    @Test
    fun `章节号差异大时通过全目录搜索正确匹配`() {
        val chapters = listOf(
            BookChapter(title = "第425章 明天", index = 424),
            BookChapter(title = "第499章 回家", index = 498)
        )

        val matchedIndex = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "第425章 回家",
            chapters = chapters,
            currentChapterIndex = 424,
            oldChapterListSize = 500
        )

        assertEquals(1, matchedIndex)
    }

    /**
     * 当前章节标题为空时，无论是否传入章节索引都应返回 -1。
     */
    @Test
    fun `标题为空时直接返回负一`() {
        val chapters = listOf(
            BookChapter(title = "第11节 明天", index = 10)
        )
        assertEquals(-1, ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = null,
            chapters = chapters,
            currentChapterIndex = 5,
            oldChapterListSize = 100
        ))
        assertEquals(-1, ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = "   ",
            chapters = chapters,
            currentChapterIndex = 5,
            oldChapterListSize = 100
        ))
    }

    @Test
    fun `站点前缀导致一方变短时通过包含检测判定一致`() {
        // 模拟站点前缀吃掉大量字符后一方变短的场景
        val longText = "今天天气晴朗万里无云适合出门散步然后去买菜顺便喝杯咖啡最后回家看书继续看下一段内容"
        val previewA = ChangeSourceAutoCycleMatcher.buildComparablePreview(longText)!!
        val shortVersion = "今天天气晴朗万里无云适合出门散步"
        val previewB = ChangeSourceAutoCycleMatcher.buildComparablePreview(shortVersion)!!

        val result = ChangeSourceAutoCycleMatcher.comparePreview(previewA, previewB)

        assertTrue("包含关系应判定为正文一致", result.sameContent)
    }

    @Test
    fun `buildDiffDisplay能在差异位置生成标记`() {
        val current = "今天天气晴朗万里无云适合出门散步"
        val candidate = "今天天气阴沉万里无云适合出门散步"
        val diff = ChangeSourceAutoCycleMatcher.findPreviewDiff(current, candidate)

        val display = ChangeSourceAutoCycleMatcher.buildDiffDisplay(current, diff)

        assertTrue("应包含方括号差异标记", display.contains("["))
        assertTrue("应包含方括号差异标记", display.contains("]"))
    }
}
