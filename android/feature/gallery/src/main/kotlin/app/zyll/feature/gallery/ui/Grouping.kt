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

private fun pattern(skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault())

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
