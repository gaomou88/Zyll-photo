package app.zyll.search

/**
 * “发现”页的自动分类。用 CLIP 的零样本能力：每个分类就是一句中文描述，
 * 不需要额外训练，也不需要另一个模型。
 */
data class SmartCategory(val key: String, val title: String, val prompt: String)

object SmartCategories {
    val all = listOf(
        SmartCategory("people", "人物", "一张人物照片"),
        SmartCategory("selfie", "自拍", "一张自拍照"),
        SmartCategory("cat", "猫咪", "一只猫的照片"),
        SmartCategory("dog", "狗狗", "一只狗的照片"),
        SmartCategory("food", "美食", "一盘美食的照片"),
        SmartCategory("landscape", "风景", "山川湖泊的风景照"),
        SmartCategory("sea", "海边", "海边沙滩的照片"),
        SmartCategory("sunset", "日落", "日落时的天空"),
        SmartCategory("night", "夜景", "城市夜景"),
        SmartCategory("flower", "花草", "花朵的特写照片"),
        SmartCategory("car", "汽车", "一辆汽车的照片"),
        SmartCategory("document", "文档", "一张文件或票据的照片"),
        SmartCategory("screenshot", "截图", "手机屏幕截图"),
    )
}
