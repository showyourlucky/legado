package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.StringUtils
import org.apache.commons.text.similarity.JaccardSimilarity

/**
 * 阅读页自动试切换源使用的纯文本匹配工具。
 *
 * 这里只处理“如何比对”，不处理网络、数据库和界面状态，
 * 这样既方便单元测试，也能让 ViewModel 保持更清晰的职责边界。
 */
object ChangeSourceAutoCycleMatcher {

    const val DEFAULT_COMPARE_CHAR_COUNT = 200
    const val MIN_EFFECTIVE_COMPARE_CHAR_COUNT = 50
    private const val DEFAULT_COMPARE_ANCHOR_CHAR_COUNT = 10
    private const val FALLBACK_TITLE_SIMILARITY_THRESHOLD = 0.8
    private const val CONTENT_SIMILARITY_THRESHOLD = 0.95

    private val chapterSpaceRegex = "\\s+".toRegex()
    private val jaccardSimilarity by lazy { JaccardSimilarity() }

    /**
     * 章节前缀标准化：去掉常见的“第xx章/回/节”或“12、”这类序号前缀，
     * 让不同源只要标题主体一致，就能匹配到同一章。
     */
    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val chapterPrefixRegex = (
        "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)" +
            "|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))"
        ).toRegex()

    /**
     * 去掉标题前后括号包裹或附加标记，减少噪声。
     */
    private val chapterWrapperRegex =
        "(?!^)(?:[【《〔\\[{(][^【《〔\\[{()】》〕\\]}]+)?[)】》〕\\]}]$|^[【《〔\\[{(](?:[^【《〔\\[{()】》〕\\]}]+[】》〕\\]})])?(?!$)".toRegex()

    /**
     * 去掉章节标题尾部常见的短元信息，例如“(3K)”“【2更】”“[1500字]”。
     * 这里只移除明显是字数/更数/短标记的尾注，避免误伤“(上)”“(终)”这类真实标题。
     */
    private val trailingMetaRegex =
        "(?:[\\[(【(（](?:\\d+(?:\\.\\d+)?(?:[kKwW千萬万字])?|\\d+更|\\d+章|\\d+节|\\d+回|\\d+话|vip|正文|修|加更)[\\])】)）])+$".toRegex()

    /**
     * 提取章节号，供自动试切的兜底校验使用。
     */
    @Suppress("RegExpSimplifiable")
    private val chapterNumberRegex = (
        ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]" +
            "|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*" +
            "([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.(?=[^\\d]))"
        ).toRegex()

    /**
     * 只保留可用于比较的文字内容。
     * 这里会移除空白、标点和其它非字母数字/中日韩文字，
     * 用来满足“比较前去除空格、换行、逗号等非文字”的需求。
     */
    @Suppress("RegExpDuplicateCharacterInClass")
    private val nonTextRegex = "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2EBEF]".toRegex()

    data class PreviewDiff(
        val firstDiffIndex: Int,
        val currentSnippet: String,
        val candidateSnippet: String
    )

    data class PreviewCompareResult(
        val alignedCurrentPreview: String,
        val alignedCandidatePreview: String,
        val sameContent: Boolean,
        val diff: PreviewDiff?
    )

    fun normalizeChapterTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return StringUtils.fullToHalf(title)
            .replace(chapterSpaceRegex, "")
            .replace(chapterPrefixRegex, "")
            .replace(trailingMetaRegex, "")
            .replace(chapterWrapperRegex, "")
            .replace(nonTextRegex, "")
    }

    /**
     * 按当前阅读章节标题在候选源目录中查找匹配的章节索引。
     *
     * 匹配策略：
     * 1. 先做标准化后的精确标题匹配。
     * 2. 精确失败时，可以复用 BookHelp.getDurChapter 得到一个近似索引。
     *    但自动试切不能直接接受这个兜底索引，必须再次校验候选标题：
     *    章节号一致，并且标准化标题存在包含关系或 Jaccard 相似度足够高。
     * 3. 若 getDurChapter 的结果被拒绝（它只在 ±50 章窗口内搜索，
     *    当两个源章节号差异大时会失效），做一次全目录按章节号搜索。
     * 4. 若所有兜底都失败，返回 -1，避免把”最后一章/邻近索引”误当成匹配章。
     */
    fun findMatchedChapterIndex(
        currentTitle: String?,
        chapters: List<BookChapter>,
        currentChapterIndex: Int = 0,
        oldChapterListSize: Int = 0
    ): Int {
        val normalizedCurrentTitle = normalizeChapterTitle(currentTitle)
        if (normalizedCurrentTitle.isEmpty()) return -1
        // 精确标题匹配
        val exactMatchIndex = chapters.indexOfFirst { chapter ->
            normalizeChapterTitle(chapter.title) == normalizedCurrentTitle
        }
        if (exactMatchIndex >= 0) return exactMatchIndex
        if (currentChapterIndex > 0) {
            // 兜底1：BookHelp.getDurChapter（±10 章窗口内搜索）
            val fallbackIndex = BookHelp.getDurChapter(
                oldDurChapterIndex = currentChapterIndex,
                oldDurChapterName = currentTitle,
                newChapterList = chapters,
                oldChapterListSize = oldChapterListSize
            )
            if (fallbackIndex in chapters.indices &&
                isAcceptableFallbackChapterMatch(currentTitle, chapters[fallbackIndex].title)
            ) {
                return fallbackIndex
            }
            // 兜底2：全目录按章节号搜索
            // 当两个源的章节号差异大（如425 vs 499），getDurChapter 的 ±50 窗口
            // 无法覆盖目标章节，需要在完整目录中按章节号定位。
            val currentChapterNumber = extractChapterNumber(currentTitle)
            if (currentChapterNumber > 0) {
                val fullListIndex = chapters.indexOfFirst { chapter ->
                    extractChapterNumber(chapter.title) == currentChapterNumber
                }
                if (fullListIndex >= 0 &&
                    isAcceptableFallbackChapterMatch(currentTitle, chapters[fullListIndex].title)
                ) {
                    return fullListIndex
                }
            }
        }
        return -1
    }

    private fun isAcceptableFallbackChapterMatch(
        currentTitle: String?,
        candidateTitle: String?
    ): Boolean {
        val normalizedCurrentTitle = normalizeChapterTitle(currentTitle)
        val normalizedCandidateTitle = normalizeChapterTitle(candidateTitle)
        if (normalizedCurrentTitle.isBlank() || normalizedCandidateTitle.isBlank()) {
            return false
        }
        val currentChapterNumber = extractChapterNumber(currentTitle)
        val candidateChapterNumber = extractChapterNumber(candidateTitle)
        if (currentChapterNumber > 0 &&
            candidateChapterNumber > 0 &&
            currentChapterNumber != candidateChapterNumber
        ) {
            return false
        }
        if (normalizedCurrentTitle.contains(normalizedCandidateTitle) ||
            normalizedCandidateTitle.contains(normalizedCurrentTitle)
        ) {
            return true
        }
        return jaccardSimilarity.apply(
            normalizedCurrentTitle,
            normalizedCandidateTitle
        ) >= FALLBACK_TITLE_SIMILARITY_THRESHOLD
    }

    private fun extractChapterNumber(title: String?): Int {
        if (title.isNullOrBlank()) return -1
        val normalizedTitle = StringUtils.fullToHalf(title).replace(chapterSpaceRegex, "")
        val matchResult = chapterNumberRegex.find(normalizedTitle) ?: return -1
        val numberText = matchResult.groupValues
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?: return -1
        return StringUtils.stringToInt(numberText)
    }

    fun buildComparablePreview(
        content: String,
        compareCharCount: Int = DEFAULT_COMPARE_CHAR_COUNT,
        minEffectiveCharCount: Int = MIN_EFFECTIVE_COMPARE_CHAR_COUNT
    ): String? {
        val normalizedContent = normalizeContent(content)
        if (normalizedContent.length < minEffectiveCharCount) {
            return null
        }
        return normalizedContent.take(compareCharCount)
    }

    fun normalizeContent(content: String): String {
        return StringUtils.fullToHalf(content).replace(nonTextRegex, "")
    }

    /**
     * 对比前先尝试消除章节开头的站点前缀/广告干扰。
     *
     * 判断逻辑（按优先级）：
     * 1. 完全一致 → sameContent
     * 2. 一方包含另一方 → sameContent（站点前缀导致长度差异的典型场景）
     * 3. Jaccard 相似度 ≥ 0.95 → sameContent（容忍微小排版/标点差异）
     * 4. 以上均不满足 → 视为正文不同，计算首个差异位置
     */
    fun comparePreview(
        currentPreview: String,
        candidatePreview: String,
        anchorCharCount: Int = DEFAULT_COMPARE_ANCHOR_CHAR_COUNT,
        contextLength: Int = 20
    ): PreviewCompareResult {
        val (alignedCurrentPreview, alignedCandidatePreview) = alignPreviewPair(
            currentPreview = currentPreview,
            candidatePreview = candidatePreview,
            anchorCharCount = anchorCharCount
        )
        // 完全一致
        if (alignedCurrentPreview == alignedCandidatePreview) {
            return PreviewCompareResult(
                alignedCurrentPreview = alignedCurrentPreview,
                alignedCandidatePreview = alignedCandidatePreview,
                sameContent = true,
                diff = PreviewDiff(
                    firstDiffIndex = 0,
                    currentSnippet = alignedCurrentPreview.take(contextLength),
                    candidateSnippet = alignedCandidatePreview.take(contextLength)
                )
            )
        }
        // 一方包含另一方（站点前缀裁剪后一方变短的典型场景）
        val containsMatch = alignedCurrentPreview.contains(alignedCandidatePreview) ||
            alignedCandidatePreview.contains(alignedCurrentPreview)
        // Jaccard 相似度足够高时视为正文一致
        val similarMatch = !containsMatch &&
            alignedCurrentPreview.length >= MIN_EFFECTIVE_COMPARE_CHAR_COUNT &&
            alignedCandidatePreview.length >= MIN_EFFECTIVE_COMPARE_CHAR_COUNT &&
            jaccardSimilarity.apply(alignedCurrentPreview, alignedCandidatePreview) >=
            CONTENT_SIMILARITY_THRESHOLD
        if (containsMatch || similarMatch) {
            return PreviewCompareResult(
                alignedCurrentPreview = alignedCurrentPreview,
                alignedCandidatePreview = alignedCandidatePreview,
                sameContent = true,
                diff = PreviewDiff(
                    firstDiffIndex = 0,
                    currentSnippet = alignedCurrentPreview.take(contextLength),
                    candidateSnippet = alignedCandidatePreview.take(contextLength)
                )
            )
        }
        return PreviewCompareResult(
            alignedCurrentPreview = alignedCurrentPreview,
            alignedCandidatePreview = alignedCandidatePreview,
            sameContent = false,
            diff = findPreviewDiff(
                currentPreview = alignedCurrentPreview,
                candidatePreview = alignedCandidatePreview,
                contextLength = contextLength
            )
        )
    }

    private fun alignPreviewPair(
        currentPreview: String,
        candidatePreview: String,
        anchorCharCount: Int
    ): Pair<String, String> {
        if (currentPreview == candidatePreview) {
            return currentPreview to candidatePreview
        }
        val safeAnchorCount = anchorCharCount.coerceAtLeast(1)
        val currentAnchor = currentPreview.take(safeAnchorCount)
        val candidateAnchor = candidatePreview.take(safeAnchorCount)
        val currentAnchorIndexInCandidate = findAnchorOffset(candidatePreview, currentAnchor)
        val candidateAnchorIndexInCurrent = findAnchorOffset(currentPreview, candidateAnchor)
        return when {
            currentAnchorIndexInCandidate > 0 &&
                candidateAnchorIndexInCurrent <= 0 -> {
                currentPreview to candidatePreview.substring(currentAnchorIndexInCandidate)
            }

            candidateAnchorIndexInCurrent > 0 &&
                currentAnchorIndexInCandidate <= 0 -> {
                currentPreview.substring(candidateAnchorIndexInCurrent) to candidatePreview
            }

            currentAnchorIndexInCandidate > 0 &&
                candidateAnchorIndexInCurrent > 0 -> {
                if (currentAnchorIndexInCandidate <= candidateAnchorIndexInCurrent) {
                    currentPreview to candidatePreview.substring(currentAnchorIndexInCandidate)
                } else {
                    currentPreview.substring(candidateAnchorIndexInCurrent) to candidatePreview
                }
            }

            else -> currentPreview to candidatePreview
        }
    }

    private fun findAnchorOffset(content: String, anchor: String): Int {
        if (anchor.isBlank()) return -1
        return content.indexOf(anchor).takeIf { it > 0 } ?: -1
    }

    fun findPreviewDiff(
        currentPreview: String,
        candidatePreview: String,
        contextLength: Int = 20
    ): PreviewDiff {
        val sharedLength = minOf(currentPreview.length, candidatePreview.length)
        val diffIndex = (0 until sharedLength).firstOrNull {
            currentPreview[it] != candidatePreview[it]
        } ?: sharedLength
        val currentSnippet = currentPreview
            .substring(diffIndex, minOf(currentPreview.length, diffIndex + contextLength))
        val candidateSnippet = candidatePreview
            .substring(diffIndex, minOf(candidatePreview.length, diffIndex + contextLength))
        return PreviewDiff(
            firstDiffIndex = diffIndex,
            currentSnippet = currentSnippet,
            candidateSnippet = candidateSnippet
        )
    }

    /**
     * 生成带差异标记的可读文本，用于对话框展示。
     *
     * 格式示例："...今天天气[晴朗万里]无云..."
     * 其中 [晴朗万里] 表示差异所在位置，方括号前后的文本为上下文。
     */
    fun buildDiffDisplay(
        preview: String,
        diff: PreviewDiff?,
        contextLen: Int = 15
    ): String {
        if (diff == null) return preview.take(contextLen * 2)
        val diffStart = diff.firstDiffIndex.coerceIn(0, preview.length)
        val snippet = diff.currentSnippet.ifEmpty { diff.candidateSnippet }
        val diffEnd = (diffStart + snippet.length).coerceAtMost(preview.length)
        // 差异前的上下文
        val prefixStart = (diffStart - contextLen).coerceAtLeast(0)
        val prefix = preview.substring(prefixStart, diffStart)
        // 差异后的上下文
        val suffixStart = diffEnd
        val suffixEnd = (suffixStart + contextLen).coerceAtMost(preview.length)
        val suffix = if (suffixStart < preview.length) {
            preview.substring(suffixStart, suffixEnd)
        } else ""
        val leading = if (prefixStart > 0) "..." else ""
        val trailing = if (suffixEnd < preview.length) "..." else ""
        return "$leading$prefix[$snippet]$suffix$trailing"
    }
}
