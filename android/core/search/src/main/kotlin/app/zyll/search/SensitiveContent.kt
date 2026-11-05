package app.zyll.search

import kotlin.math.exp

/**
 * 敏感画面识别：用 CLIP 做零样本分类，不需要额外模型。
 * 把图片和一组“敏感”“正常”描述逐一比较，按 CLIP 的方式做 softmax，
 * 敏感描述加起来的概率超过阈值就打码。阈值偏低：宁可多打码，也不要在公共场合直接显示出来。
 */
object SensitiveContent {
    val sensitivePrompts = listOf(
        "一张裸体照片",
        "裸露的人体",
        "色情图片",
        "露出胸部的照片",
        "穿着内衣的性感照片",
        "a nude photo",
        "explicit sexual content",
    )

    val safePrompts = listOf(
        "穿着衣服的人",
        "穿着泳衣在海边的人",
        "一张自拍",
        "风景照片",
        "食物",
        "宠物",
        "一张截图",
        "文件或文字",
        "城市街道",
        "室内房间",
        "a normal everyday photo",
    )

    /** CLIP 训练时的温度（logit scale）。 */
    const val LOGIT_SCALE = 100f
    const val DEFAULT_THRESHOLD = 0.4f

    /** 返回敏感的概率（0~1）。所有向量都应已归一化。 */
    fun probability(image: FloatArray, sensitive: List<FloatArray>, safe: List<FloatArray>, imageOffset: Int = 0): Float {
        val logits = (sensitive + safe).map { dot(it, image, imageOffset) * LOGIT_SCALE }
        val max = logits.max()
        val e = logits.map { exp((it - max).toDouble()) }
        val total = e.sum()
        return (e.take(sensitive.size).sum() / total).toFloat()
    }

    fun isSensitive(image: FloatArray, sensitive: List<FloatArray>, safe: List<FloatArray>, threshold: Float = DEFAULT_THRESHOLD): Boolean =
        probability(image, sensitive, safe) >= threshold
}

/** 找出索引里所有判定为敏感的图片。 */
fun VectorIndex.sensitive(sensitive: List<FloatArray>, safe: List<FloatArray>, threshold: Float = SensitiveContent.DEFAULT_THRESHOLD): Set<Long> =
    matching { data, offset -> SensitiveContent.probability(data, sensitive, safe, offset) >= threshold }
