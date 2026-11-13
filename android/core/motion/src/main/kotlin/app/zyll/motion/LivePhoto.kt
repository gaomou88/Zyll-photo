package app.zyll.motion

/**
 * iPhone 实况照片拷到别的设备上是两个文件：IMG_1234.HEIC + IMG_1234.MOV。
 * 同一个相簿里、去掉扩展名后名字相同（不分大小写）的照片和 MOV 算一对（和网页版 livePairKey 一致）。
 */
object LivePhoto {
    data class Key(val key: String, val still: Boolean)

    /** 配对用的键；不能参与配对时返回 null。[album] 用相簿名（网盘里的照片只有名字）。 */
    fun pairKey(album: String, name: String, mime: String): Key? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1).lowercase()
        val key = album + "\u0000" + name.substring(0, dot).lowercase()
        if (ext == "mov" && mime.startsWith("video/")) return Key(key, still = false)
        if (ext in STILL_EXT && mime.startsWith("image/")) return Key(key, still = true)
        return null
    }

    private val STILL_EXT = setOf("heic", "heif", "jpg", "jpeg")
}
