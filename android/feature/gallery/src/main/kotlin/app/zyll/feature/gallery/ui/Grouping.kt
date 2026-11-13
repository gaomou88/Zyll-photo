package app.zyll.feature.gallery.ui

import android.text.format.DateFormat
import app.zyll.feature.gallery.R
import app.zyll.media.MediaItem
import app.zyll.media.tr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 时间线上的一组（一天或一个月）。 */
data class Section(val key: String, val title: String, val subtitle: String?, val items: List<MediaItem>)

enum class Density(val columns: Int, val byMonth: Boolean) {
    Large(2, false), Comfortable(3, false), Compact(4, false), Month(6, true);

    fun denser() = entries.getOrElse(ordinal + 1) { this }
    fun looser() = entries.getOrElse(ordinal - 1) { this }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
fun MediaItem.localDate(): LocalDate = Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDate()

private val patterns = java.util.concurrent.ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

/** 按语言缓存日期格式器：每组标题都新建一个很慢（几万张照片时上千组）。格式器不可变，可以跨线程共用。 */
private fun pattern(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return patterns.getOrPut(locale to skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

fun dayTitle(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (ChronoUnit.DAYS.between(d, today)) {
    0L -> tr(R.string.date_today)
    1L -> tr(R.string.date_yesterday)
    in 2L..6L -> weekday(d)
    else -> pattern(if (d.year == today.year) "MMMd" else "yMMMd").format(d)
}

fun monthTitle(d: LocalDate, today: LocalDate = LocalDate.now()): String =
    pattern(if (d.year == today.year) "MMMM" else "yMMMM").format(d)

fun group(items: List<MediaItem>, byMonth: Boolean): List<Section> {
    val today = LocalDate.now()
    if (byMonth) {
        return items.groupBy { it.localDate().withDayOfMonth(1) }.map { (d, list) ->
            Section("m$d", monthTitle(d, today), tr(R.string.common_items_n, list.size), list)
        }
    }
    return items.groupBy { it.localDate() }.map { (d, list) ->
        val title = dayTitle(d, today)
        // 显示成日期的那几组（一周以前），副标题补上星期几
        val sub = if (ChronoUnit.DAYS.between(d, today) !in 0L..6L) weekday(d) else null
        Section("d$d", title, sub, list)
    }
}

/** 快速滚动条上的标签，比如“2024年3月”“March 2024”。 */
fun scrubLabel(item: MediaItem): String = pattern("yMMM").format(item.localDate())

/** “那年今日”：往年同一天（前后各 1 天）拍的照片，按年份分组。 */
fun onThisDay(items: List<MediaItem>, today: LocalDate = LocalDate.now()): List<Pair<Int, List<MediaItem>>> =
    items.filter { !it.isScreenshot }
        .filter {
            val d = it.localDate()
            d.year < today.year && kotlin.math.abs(ChronoUnit.DAYS.between(d.withYear(today.year), today)) <= 1
        }
        .groupBy { today.year - it.localDate().year }
        .toSortedMap()
        .map { it.key to it.value }

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** 网格的布局：分组、每个位置对应的照片（标题处为 null，给快速滚动条用），以及照片的先后顺序（拖动多选用）。 */
class GridLayout(
    val sections: List<Section>,
    val flat: List<MediaItem?>,
    val ids: List<Long>,
    val indexOf: Map<Long, Int>,
)

/**
 * 网格布局在后台线程算，并按“同一个列表对象”缓存几份：打开大图、切标签页再回来时网格会重建，
 * 照片列表没变就直接用算好的，不在主线程上再分一次组（几万张照片时要一两百毫秒）。
 */
object GridLayouts {
    // 键只弱引用照片列表，值用软引用：照片几万张时一份布局有十几 MB，内存紧时让系统收回，不靠后来的条目挤出去
    private class Key(items: List<MediaItem>, val byMonth: Boolean, val grouped: Boolean, val header: Boolean) {
        private val ref = java.lang.ref.WeakReference(items)
        private val hash = (System.identityHashCode(items) * 31 + byMonth.hashCode()) * 31 + grouped.hashCode() * 7 + header.hashCode()
        val alive get() = ref.get() != null
        override fun equals(other: Any?) = other is Key && other.ref.get().let { it != null && it === ref.get() } &&
            other.byMonth == byMonth && other.grouped == grouped && other.header == header
        override fun hashCode() = hash
    }

    private val cache = object : LinkedHashMap<Key, java.lang.ref.SoftReference<GridLayout>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, java.lang.ref.SoftReference<GridLayout>>?) = size > 3
    }

    fun peek(items: List<MediaItem>, byMonth: Boolean, grouped: Boolean, header: Boolean): GridLayout? =
        synchronized(cache) { cache[Key(items, byMonth, grouped, header)]?.get() }

    /** 在后台线程调用。 */
    fun compute(items: List<MediaItem>, byMonth: Boolean, grouped: Boolean, header: Boolean): GridLayout {
        peek(items, byMonth, grouped, header)?.let { return it }
        val sections = if (grouped) group(items, byMonth) else listOf(Section("all", "", null, items))
        val flat = ArrayList<MediaItem?>(items.size + sections.size + 1)
        if (header) flat.add(null)
        sections.forEach { s ->
            if (s.title.isNotEmpty()) flat.add(null)
            flat.addAll(s.items)
        }
        val ids = ArrayList<Long>(items.size)
        sections.forEach { s -> s.items.forEach { ids.add(it.id) } }
        val indexOf = HashMap<Long, Int>(ids.size * 2)
        ids.forEachIndexed { i, id -> indexOf[id] = i }
        val layout = GridLayout(sections, flat, ids, indexOf)
        synchronized(cache) {
            // 列表已被回收、或布局已被系统收回的条目先清掉
            cache.entries.removeAll { !it.key.alive || it.value.get() == null }
            cache[Key(items, byMonth, grouped, header)] = java.lang.ref.SoftReference(layout)
        }
        return layout
    }
}

/** “那年今日”的结果按同一个列表对象缓存一份，在后台线程算（同 [GridLayouts]）。 */
object Memories {
    // 按“哪份列表 + 哪一天”缓存：过了零点同一份列表也要重算
    private class Entry(val items: List<MediaItem>, val day: LocalDate, val result: List<Pair<Int, List<MediaItem>>>)
    @Volatile private var last: Entry? = null

    fun peek(items: List<MediaItem>): List<Pair<Int, List<MediaItem>>>? =
        last?.takeIf { it.items === items && it.day == LocalDate.now() }?.result

    fun compute(items: List<MediaItem>): List<Pair<Int, List<MediaItem>>> {
        peek(items)?.let { return it }
        val day = LocalDate.now()
        return onThisDay(items, day).also { last = Entry(items, day, it) }
    }
}

/** 每个网格当前显示的格子（见 [GridLayout.flat]），大图翻页时用来让网格滚到同一张照片。 */
object GridPositions {
    private val shown = java.util.WeakHashMap<androidx.compose.foundation.lazy.grid.LazyGridState, GridLayout>()

    fun attach(state: androidx.compose.foundation.lazy.grid.LazyGridState, layout: GridLayout) {
        synchronized(shown) { shown[state] = layout }
    }

    /** 这个网格上一次显示的布局：新列表还没在后台算好时先顶上，不用空列表去测量（那样会把滚动位置清零）。 */
    fun last(state: androidx.compose.foundation.lazy.grid.LazyGridState): GridLayout? = synchronized(shown) { shown[state] }

    /**
     * 这张照片没有完整显示在网格里时，让网格下次布局时滚过去（网格这时可能不在屏幕上）。
     * 尽量少滚：在上面就顶到最上面，在下面就让它出现在底部。
     */
    fun reveal(state: androidx.compose.foundation.lazy.grid.LazyGridState, id: Long) {
        val flat = synchronized(shown) { shown[state] }?.flat ?: return
        val index = flat.indexOfFirst { it?.id == id }
        if (index < 0) return
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return state.requestScrollToItem(index)
        val top = info.viewportStartOffset + info.beforeContentPadding
        val bottom = info.viewportEndOffset - info.afterContentPadding
        val full = visible.filter { it.offset.y >= top && it.offset.y + it.size.height <= bottom }
        if (full.any { it.index == index }) return
        val firstFull = full.firstOrNull()?.index ?: visible.first().index
        if (index < firstFull) return state.requestScrollToItem(index)
        // 往下：让这张落在最后一整行。按一屏能完整放下的格子数往回推，再多退一行以免落在屏幕外
        val columns = visible.groupingBy { it.row }.eachCount().values.maxOrNull() ?: 1
        val target = (index - full.size + columns).coerceIn(0, index)
        state.requestScrollToItem(target)
    }
}
