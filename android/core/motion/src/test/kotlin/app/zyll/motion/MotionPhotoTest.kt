package app.zyll.motion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** spec/test-vectors/motion：网页版跑的是同一份文件。 */
class MotionPhotoTest {
    private val dir = File(System.getProperty("zyll.vectorsDir") ?: "../../../spec/test-vectors", "motion")

    private fun reader(b: ByteArray) = RandomRead { off, len ->
        if (off >= b.size) ByteArray(0) else b.copyOfRange(off.toInt(), minOf(b.size.toLong(), off + len).toInt())
    }

    /** 不引 JSON 库：向量文件格式固定，按正则取字段。 */
    private data class Vec(val file: String, val size: Long, val quick: MotionVideo?, val scan: MotionVideo?)

    private fun vectors(): List<Vec> {
        val text = File(dir, "motion.json").readText()
        fun hit(s: String?): MotionVideo? {
            if (s == null || s.trim() == "null") return null
            val o = Regex("\"offset\":\\s*(\\d+)").find(s)!!.groupValues[1].toLong()
            val l = Regex("\"length\":\\s*(\\d+)").find(s)!!.groupValues[1].toLong()
            val m = Regex("\"mime\":\\s*\"([^\"]+)\"").find(s)!!.groupValues[1]
            return MotionVideo(o, l, m)
        }
        val obj = "(null|\\{[^}]*\\})"
        return Regex("\\{\\s*\"file\":\\s*\"([^\"]+)\",\\s*\"size\":\\s*(\\d+),\\s*\"quick\":\\s*$obj,\\s*\"scan\":\\s*$obj")
            .findAll(text).map { Vec(it.groupValues[1], it.groupValues[2].toLong(), hit(it.groupValues[3]), hit(it.groupValues[4])) }.toList()
    }

    @TestFactory
    fun sharedVectors(): List<DynamicTest> {
        val list = vectors()
        assertTrue(list.size >= 12, "向量没读全：${list.size}")
        return list.map { v ->
            DynamicTest.dynamicTest(v.file) {
                val b = File(dir, v.file).readBytes()
                assertEquals(v.size, b.size.toLong())
                assertEquals(v.quick, MotionPhoto.find(reader(b), b.size.toLong(), scan = false), "quick")
                assertEquals(v.scan, MotionPhoto.find(reader(b), b.size.toLong(), scan = true), "scan")
            }
        }
    }

    @Test
    fun writtenXmpIsFoundAgain() {
        val still = File(dir, "still-with-ftyp-bytes.jpg").readBytes()
        val g = File(dir, "google-motion.jpg").readBytes()
        val video = MotionPhoto.find(reader(g), g.size.toLong())!!
        val v = g.copyOfRange(video.offset.toInt(), (video.offset + video.length).toInt())
        val out = MotionXmp.insertInto(still, v.size.toLong(), video.mime) + v
        val found = MotionPhoto.find(reader(out), out.size.toLong(), scan = false)
        assertEquals(MotionVideo((out.size - v.size).toLong(), v.size.toLong(), "video/mp4"), found)
        // APP0 还在 SOI 后面
        assertEquals(0xE0, out[3].toInt() and 0xFF)
    }

    @Test
    fun xmpLengthsOnlyFromVideoItem() {
        val x = "<rdf:li><Container:Item Item:Mime=\"image/jpeg\" Item:Length=\"0\"/></rdf:li><rdf:li><Container:Item Item:Mime=\"video/mp4\" Item:Length=\"1234\"/></rdf:li>"
        assertEquals(listOf(1234L), MotionPhoto.xmpTailLengths(x))
        assertEquals(listOf(99L), MotionPhoto.xmpTailLengths("GCamera:MicroVideoOffset=\"99\" <rdf:li Item:Semantic=\"MotionPhoto\" Item:Length=\"99\"/></rdf:li>"))
    }

    @Test
    fun livePairs() {
        val a = LivePhoto.pairKey("相机", "IMG_1234.HEIC", "image/heic")!!
        val b = LivePhoto.pairKey("相机", "img_1234.mov", "video/quicktime")!!
        assertTrue(a.still && !b.still)
        assertEquals(a.key, b.key)
        assertTrue(LivePhoto.pairKey("A", "IMG_1.HEIC", "image/heic")!!.key != LivePhoto.pairKey("B", "IMG_1.MOV", "video/quicktime")!!.key)
        assertNull(LivePhoto.pairKey("A", "IMG_1.MP4", "video/mp4"))
        assertNull(LivePhoto.pairKey("A", "IMG", "image/jpeg"))
    }
}
