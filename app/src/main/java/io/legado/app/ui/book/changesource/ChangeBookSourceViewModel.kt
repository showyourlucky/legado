package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

enum class AutoCycleState {
    Idle,
    Running,
    Paused,
    Completed,
    Stopped
}

data class AutoCycleStatus(
    val state: AutoCycleState = AutoCycleState.Idle,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val sourceName: String = "",
    val lastSkipReason: String = "",
    val message: String = ""
)

data class AutoCycleCandidate(
    val searchBook: SearchBook,
    val book: Book,
    val toc: List<BookChapter>,
    val source: BookSource,
    val comparePreview: String,
    val displayPreview: String,
    val diff: ChangeSourceAutoCycleMatcher.PreviewDiff? = null
)

sealed class AutoCycleStepResult {
    data class Candidate(
        val candidate: AutoCycleCandidate,
        val sameAsCurrent: Boolean
    ) : AutoCycleStepResult()

    object Finished : AutoCycleStepResult()
}

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    private var oldBook: Book? = null
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    private var searchBookList = arrayListOf<SearchBook>()
    private val searchBooks = Collections.synchronizedList(arrayListOf<SearchBook>())
    private val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    private val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    private val _autoCycleStatus = MutableStateFlow(AutoCycleStatus())
    val autoCycleStatus = _autoCycleStatus.asStateFlow()
    private var autoCycleQueue = emptyList<SearchBook>()
    private var autoCycleCurrentIndex = 0
    private var autoCycleCurrentPreview: String? = null
    private var autoCycleCurrentDisplayPreview: String? = null
    private var autoCyclePausedCandidate: AutoCycleCandidate? = null
    private var searchCallback: SourceCallback? = null
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()
    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }
            .thenByDescending { SourceConfig.getSourceScore(it.origin) }
    }
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }
            .thenBy { it.originOrder }
    }
    private var task: Job? = null
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                when {
                    screenKey.isEmpty() -> searchBooks.add(searchBook)
                    searchBook.name.contains(screenKey) -> searchBooks.add(searchBook)
                    else -> return
                }
                trySend(arrayOf(searchBooks))
            }

            override fun upAdapter() {
                trySend(arrayOf(searchBooks))
            }

        }

        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            trySend(arrayOf(searchBooks))
        }

        if (searchBooks.isEmpty()) {
            startSearch()
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            getSortedSearchBooks()
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(IO)

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            this.fromReadBookActivity = fromReadBookActivity
            oldBook = book
        }
    }

    private fun initSearchPool() {
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun refresh(): Boolean {
        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            searchCallback?.upAdapter()
        }
        return searchBooks.isEmpty()
    }

    /**
     * 搜索书籍
     */
    fun startSearch() {
        execute {
            stopSearch()
            if (searchBooks.isNotEmpty()) {
                appDb.searchBookDao.delete(*searchBooks.toTypedArray())
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            val searchGroup = AppConfig.searchGroup
            if (searchGroup.isBlank()) {
                bookSourceParts.addAll(appDb.bookSourceDao.allEnabledPart)
            } else {
                val sources = appDb.bookSourceDao.getEnabledPartByGroup(searchGroup)
                if (sources.isEmpty()) {
                    AppConfig.searchGroup = ""
                    bookSourceParts.addAll(appDb.bookSourceDao.allEnabledPart)
                } else {
                    bookSourceParts.addAll(sources)
                }
            }
            initSearchPool()
            search()
        }
    }

    fun startSearch(origin: String) {
        execute {
            stopSearch()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
            searchBooks.removeIf { it.origin == origin }
            initSearchPool()
            search()
        }
    }

    private fun search() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallel(threadCount) {
                try {
                    withTimeout(60000L) {
                        search(it)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                _changeSourceProgress.update { _ ->
                    index + 1 to value.bookSourceName
                }
            }.onCompletion {
                ensureActive()
                searchStateData.postValue(false)
                searchFinishCallback?.invoke(searchBooks.isEmpty())
            }.catch {
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun search(source: BookSource) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val resultBooks = WebBook.searchBookAwait(
            source, name,
            filter = { fName, fAuthor, _ ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            })
        resultBooks.forEach { searchBook ->
            when {
                loadInfo || loadToc || loadWordCount -> {
                    loadBookInfo(source, searchBook.toBook())
                }

                else -> {
                    searchCallback?.searchSuccess(searchBook)
                }
            }
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book)
        } else {
            //从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (AppConfig.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters)
        } else {
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ) = coroutineScope {
        val chapterIndex = if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        searchCallback?.searchSuccess(searchBook)
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        execute {
            stopSearch()
            searchBookList.clear()
            if (onlyRefreshNoWordCountBook) {
                searchBooks.filterTo(searchBookList) {
                    it.chapterWordCountText == null
                }
                searchBooks.removeIf { it.chapterWordCountText == null }
            } else {
                searchBookList.addAll(searchBooks)
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            initSearchPool()
            refreshList()
        }
    }

    private fun refreshList() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in searchBookList) {
                    emit(searchBook)
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallelSafe(threadCount) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(60000L) {
                    loadBookInfo(source, it.toBook())
                }
            }.onCompletion {
                searchStateData.postValue(false)
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private fun getDbSearchBooks(): List<SearchBook> {
        return if (screenKey.isEmpty()) {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceByGroup(
                    name, author, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceByGroup(
                    name, "", AppConfig.searchGroup
                )
            }
        } else {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceSearch(
                    name, author, screenKey, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceSearch(
                    name, "", screenKey, AppConfig.searchGroup
                )
            }
        }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        execute {
            getDbSearchBooks().let {
                searchBooks.clear()
                searchBooks.addAll(it)
                searchCallback?.upAdapter()
            }
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    fun stopSearch() {
        task?.cancel()
        searchPool?.close()
        searchStateData.postValue(false)
    }

    private fun getSortedSearchBooks(): List<SearchBook> {
        val comparator = if (AppConfig.changeSourceLoadWordCount) {
            wordCountComparator
        } else {
            defaultComparator
        }
        return searchBooks.sortedWith(comparator)
    }

    fun hasSearchResults(): Boolean {
        return searchBooks.isNotEmpty()
    }

    fun isAutoCycleActive(): Boolean {
        return when (_autoCycleStatus.value.state) {
            AutoCycleState.Running, AutoCycleState.Paused -> true
            else -> false
        }
    }

    fun isAutoCyclePaused(): Boolean {
        return _autoCycleStatus.value.state == AutoCycleState.Paused
    }

    fun clearAutoCycleState() {
        autoCycleQueue = emptyList()
        autoCycleCurrentIndex = 0
        autoCycleCurrentPreview = null
        autoCycleCurrentDisplayPreview = null
        autoCyclePausedCandidate = null
        _autoCycleStatus.value = AutoCycleStatus()
    }

    fun stopAutoCycle(message: String = "") {
        autoCycleQueue = emptyList()
        autoCyclePausedCandidate = null
        autoCycleCurrentPreview = null
        autoCycleCurrentDisplayPreview = null
        _autoCycleStatus.value = AutoCycleStatus(
            state = AutoCycleState.Stopped,
            message = message.ifBlank { "自动试切已停止" }
        )
    }

    fun pauseAutoCycle(message: String = "") {
        if (_autoCycleStatus.value.state != AutoCycleState.Running) return
        _autoCycleStatus.value = _autoCycleStatus.value.copy(
            state = AutoCycleState.Paused,
            message = message.ifBlank { "已暂停自动试切" }
        )
    }

    suspend fun prepareAutoCycle(currentBookUrl: String?): Result<Boolean> {
        return kotlin.runCatching {
            if (!fromReadBookActivity) {
                return@runCatching false
            }
            val currentBook = oldBook ?: return@runCatching false
            val sortedSearchBooks = getSortedSearchBooks()
            if (sortedSearchBooks.isEmpty()) {
                return@runCatching false
            }
            val currentIndex = sortedSearchBooks.indexOfFirst { it.bookUrl == currentBookUrl }
            autoCycleQueue = if (currentIndex >= 0) {
                sortedSearchBooks.drop(currentIndex + 1)
            } else {
                sortedSearchBooks.filter { it.bookUrl != currentBook.bookUrl }
            }
            if (autoCycleQueue.isEmpty()) {
                clearAutoCycleState()
                return@runCatching false
            }
            autoCycleCurrentIndex = 0
            autoCyclePausedCandidate = null
            loadCurrentChapterPreview(currentBook).let { preview ->
                autoCycleCurrentPreview = preview.comparePreview
                autoCycleCurrentDisplayPreview = preview.displayPreview
            }
            _autoCycleStatus.value = AutoCycleStatus(
                state = AutoCycleState.Running,
                total = autoCycleQueue.size,
                lastSkipReason = "",
                message = "自动试切准备完成"
            )
            true
        }
    }

    suspend fun evaluateNextAutoCycleCandidate(): AutoCycleStepResult {
        val currentPreview = autoCycleCurrentPreview
            ?: throw NoStackTraceException("当前源正文不足，无法自动试切")
        while (autoCycleCurrentIndex < autoCycleQueue.size) {
            val candidateIndex = autoCycleCurrentIndex
            val searchBook = autoCycleQueue[candidateIndex]
            autoCycleCurrentIndex++
            _autoCycleStatus.value = _autoCycleStatus.value.copy(
                state = AutoCycleState.Running,
                currentIndex = candidateIndex + 1,
                total = autoCycleQueue.size,
                sourceName = searchBook.originName,
                message = "自动试切进行中"
            )
            val candidateResult = kotlin.runCatching {
                prepareAutoCycleCandidate(searchBook)
            }
            if (candidateResult.isFailure) {
                val error = candidateResult.exceptionOrNull()
                if (error is CancellationException) {
                    throw error
                }
                val reason = error?.getReadableAutoCycleReason() ?: "未知错误"
                AppLog.put("自动试切跳过 ${searchBook.originName}\n$reason")
                _autoCycleStatus.value = _autoCycleStatus.value.copy(
                    lastSkipReason = "${searchBook.originName}: $reason"
                )
                continue
            }
            val candidate = candidateResult.getOrThrow()
            val compareResult = ChangeSourceAutoCycleMatcher.comparePreview(
                currentPreview = currentPreview,
                candidatePreview = candidate.comparePreview
            )
            return AutoCycleStepResult.Candidate(
                candidate = candidate.copy(
                    comparePreview = compareResult.alignedCandidatePreview,
                    diff = compareResult.diff
                ),
                sameAsCurrent = compareResult.sameContent
            )
        }
        val total = autoCycleQueue.size
        autoCycleQueue = emptyList()
        autoCyclePausedCandidate = null
        _autoCycleStatus.value = _autoCycleStatus.value.copy(
            state = AutoCycleState.Completed,
            currentIndex = total,
            total = total,
            message = "未找到正文不同的候选源"
        )
        return AutoCycleStepResult.Finished
    }

    fun onAutoCycleCandidateApplied(
        candidate: AutoCycleCandidate,
        sameAsCurrent: Boolean
    ) {
        autoCyclePausedCandidate = if (sameAsCurrent) {
            null
        } else {
            candidate
        }
        _autoCycleStatus.value = _autoCycleStatus.value.copy(
            state = if (sameAsCurrent) AutoCycleState.Running else AutoCycleState.Paused,
            sourceName = candidate.searchBook.originName,
            message = if (sameAsCurrent) {
                "正文一致，继续尝试下一个源"
            } else {
                "正文不同，已暂停自动试切"
            }
        )
    }

    fun pauseAutoCycleForCandidate(candidate: AutoCycleCandidate) {
        autoCyclePausedCandidate = candidate
        _autoCycleStatus.value = _autoCycleStatus.value.copy(
            state = AutoCycleState.Paused,
            sourceName = candidate.searchBook.originName,
            message = "正文不同，已暂停自动试切"
        )
    }

    fun continueAutoCycle() {
        if (_autoCycleStatus.value.state != AutoCycleState.Paused) return
        autoCyclePausedCandidate = null
        _autoCycleStatus.value = _autoCycleStatus.value.copy(
            state = AutoCycleState.Running,
            lastSkipReason = "",
            message = "继续自动试切"
        )
    }

    fun getAutoCycleCurrentPreview(): String {
        return autoCycleCurrentPreview ?: ""
    }

    fun getAutoCycleCurrentDisplayPreview(): String {
        return autoCycleCurrentDisplayPreview ?: ""
    }

    private suspend fun prepareAutoCycleCandidate(searchBook: SearchBook): AutoCycleCandidate {
        val currentBook = oldBook ?: throw NoStackTraceException("当前阅读书籍不存在")
        val candidateBook = bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
        val candidateToc = tocMap[candidateBook.primaryStr()]
        val (toc, source) = if (candidateToc != null) {
            val candidateSource = appDb.bookSourceDao.getBookSource(candidateBook.origin)
                ?: throw NoStackTraceException("书源不存在")
            candidateToc to candidateSource
        } else {
            val result = getToc(candidateBook).getOrThrow()
            tocMap[candidateBook.primaryStr()] = result.first
            bookMap[candidateBook.primaryStr()] = candidateBook
            result
        }
        val chapterIndex = ChangeSourceAutoCycleMatcher.findMatchedChapterIndex(
            currentTitle = currentBook.durChapterTitle,
            chapters = toc,
            currentChapterIndex = currentBook.durChapterIndex,
            oldChapterListSize = currentBook.totalChapterNum
        )
        if (chapterIndex < 0) {
            throw NoStackTraceException("未找到章节：${currentBook.durChapterTitle}")
        }
        val chapter = toc[chapterIndex]
        val nextChapterUrl = toc.getOrNull(chapterIndex + 1)?.url
        val comparePreview = loadComparablePreview(
            source = source,
            book = candidateBook,
            chapter = chapter,
            nextChapterUrl = nextChapterUrl
        )
        return AutoCycleCandidate(
            searchBook = searchBook,
            book = candidateBook,
            toc = toc,
            source = source,
            comparePreview = comparePreview.comparePreview,
            displayPreview = comparePreview.displayPreview
        )
    }

    private suspend fun loadCurrentChapterPreview(book: Book): LoadedPreview {
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: throw NoStackTraceException("当前书源不存在")
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)
            ?: throw NoStackTraceException("当前章节不存在")
        val nextChapterUrl = appDb.bookChapterDao
            .getChapter(book.bookUrl, book.durChapterIndex + 1)
            ?.url
        return loadComparablePreview(
            source = source,
            book = book,
            chapter = chapter,
            nextChapterUrl = nextChapterUrl
        )
    }

    private suspend fun loadComparablePreview(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?
    ): LoadedPreview {
        var content = BookHelp.getContent(book, chapter)
        if (content.isNullOrBlank()) {
            content = WebBook.getContentAwait(source, book, chapter, nextChapterUrl, false)
        }
        if (content.isBlank()) {
            throw NoStackTraceException("正文内容为空")
        }
        val processedContent = ContentProcessor.get(book)
            .getContent(
                book = book,
                chapter = chapter,
                content = content,
                includeTitle = false,
                useReplace = book.getUseReplaceRule()
            )
            .toString()
        val comparePreview = ChangeSourceAutoCycleMatcher.buildComparablePreview(processedContent)
            ?: throw NoStackTraceException(
                "正文有效内容不足${ChangeSourceAutoCycleMatcher.MIN_EFFECTIVE_COMPARE_CHAR_COUNT}字"
            )
        return LoadedPreview(
            comparePreview = comparePreview,
            displayPreview = processedContent.take(ChangeSourceAutoCycleMatcher.DEFAULT_COMPARE_CHAR_COUNT)
        )
    }

    data class LoadedPreview(
        val comparePreview: String,
        val displayPreview: String
    )

    /**
     * 自动试切经常会包多层异常，直接读 localizedMessage 容易得到空值。
     * 这里沿着 cause 链取第一个可读中文原因，兜底再返回异常类型名。
     */
    private fun Throwable.getReadableAutoCycleReason(): String {
        var current: Throwable? = this
        while (current != null) {
            val message = current.localizedMessage?.trim().orEmpty()
            if (message.isNotEmpty() && message.lowercase() != "null") {
                return message
            }
            current = current.cause
        }
        return this::class.simpleName ?: "未知错误"
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return kotlin.runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            searchBooks.remove(searchBook)
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun updateSource(searchBook: SearchBook) {
        appDb.searchBookDao.update(searchBook)
    }

    fun del(searchBook: SearchBook) {
        execute {
            SourceHelp.deleteBookSource(searchBook.origin)
            appDb.searchBookDao.delete(searchBook)
        }
        searchBooks.remove(searchBook)
        searchCallback?.upAdapter()
    }

    fun autoChangeSource(
        bookType: Int?,
        onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        execute {
            searchBooks.forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}
