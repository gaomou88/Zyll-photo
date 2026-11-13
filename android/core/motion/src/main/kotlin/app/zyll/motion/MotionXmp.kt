package app.zyll.motion

/**
 * 把一张新做的 JPEG（比如编辑后另存的副本）重新做成动态照片：在 JPEG 开头插入一段 Google Motion Photo 的 XMP，
 * 调用方紧接着把视频原样接在文件末尾。同时写新旧两种写法（Container 目录 + MicroVideoOffset），
 * Google 相册、三星图库、小米相册等都认。
 */
object MotionXmp {
    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"

    fun xmp(videoLength: Long, videoMime: String): String {
        require(videoLength > 0)
        val mime = if (videoMime == "video/quicktime") "video/quicktime" else "video/mp4"
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
            "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" " +
            "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"-1\" " +
            "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"$videoLength\" GCamera:MicroVideoPresentationTimestampUs=\"-1\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"$mime\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    /** 返回插好 XMP 的 JPEG（不含视频）。[jpeg] 必须是不带 XMP 的 JPEG（Bitmap.compress 的输出就是）。 */
    fun insertInto(jpeg: ByteArray, videoLength: Long, videoMime: String): ByteArray {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8) { "not a JPEG" }
        val body = (XMP_ID + xmp(videoLength, videoMime)).toByteArray(Charsets.UTF_8)
        val segLen = body.size + 2
        require(segLen <= 0xFFFF)
        val seg = ByteArray(4 + body.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        seg[2] = (segLen shr 8).toByte()
        seg[3] = segLen.toByte()
        System.arraycopy(body, 0, seg, 4, body.size)
        // 放在 JFIF（APP0）之后：有的读取器要求 APP0 紧跟 SOI
        var at = 2
        if ((jpeg[2].toInt() and 0xFF) == 0xFF && (jpeg[3].toInt() and 0xFF) == 0xE0 && jpeg.size > 6) {
            val app0 = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            if (4 + app0 <= jpeg.size) at = 2 + 2 + app0
        }
        return jpeg.copyOfRange(0, at) + seg + jpeg.copyOfRange(at, jpeg.size)
    }
}
