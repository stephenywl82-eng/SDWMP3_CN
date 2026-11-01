package com.sdw.music.player.core.audio.metadata

/**
 * [2026-10-25] 移植自 Lyrico（audiotag 模块）的内嵌图片三级回退链。
 *
 * ── 解决什么问题 ──
 * FLAC 的 PICTURE block 和 WAV 内嵌 ID3 的 APIC 帧都允许一个文件带**多张**图
 * （封面 / 封底 / 艺人图 / 图标）。原实现是「见第一张就用」——当封底或艺人图
 * 排在前面时，用户看到的就是错的那张。TagLib 系播放器按类型挑主封面（type 3），
 * 我们对齐这个行为。
 *
 * ── 回退链（对应 Lyrico getPicture 的 preferred + fallbackToAny）──
 *  ① type 3（Front Cover）里优先「无描述」的，其次带描述的
 *  ② type 4（Back Cover），同样先无描述
 *  ③ 任意非空图（fallbackToAny）——老文件/异类编码经常整篇 type 0
 *
 * 类型号 ID3 APIC 与 FLAC PICTURE 共用同一套数值（0=Other, 1=32x32 图标,
 * 2=其他图标, 3=封面, 4=封底, 5=插页, 6=媒体, 7=主艺人大图, 8=艺人图…），
 * 所以一个 Int 字段两种格式通用。
 */
class EmbeddedPicture(
    val data: ByteArray,
    val mime: String,
    /** APIC/PICTURE 携带的描述串（可为空；艺人图常用它标归属）。 */
    val description: String = "",
    /** 图片类型号（ID3 APIC type byte / FLAC PICTURE 32-bit type）。 */
    val type: Int = TYPE_OTHER
) {
    companion object {
        const val TYPE_OTHER = 0
        const val TYPE_FILE_ICON = 1
        const val TYPE_OTHER_FILE_ICON = 2
        const val TYPE_FRONT_COVER = 3
        const val TYPE_BACK_COVER = 4
        /** 图标类：永远不该被选成主封面。 */
        fun isIcon(type: Int): Boolean = type == TYPE_FILE_ICON || type == TYPE_OTHER_FILE_ICON
    }
}

/**
 * 从一组内嵌图里挑主封面。回退链见 [EmbeddedPicture] 注释。
 * 返回 (bytes, mime)；空列表/全是空数据返回 null。
 */
object EmbeddedCoverSelector {

    fun select(pictures: List<EmbeddedPicture>): Pair<ByteArray, String>? {
        val usable = pictures.filter { it.data.isNotEmpty() }
        if (usable.isEmpty()) return null

        // 图标类排除在「任意回退」之外——32x32 文件图标当封面比没有更糟
        val nonIcon = usable.filterNot { EmbeddedPicture.isIcon(it.type) }
            .ifEmpty { usable }

        // ① ② 按优先级试 Front → Back；同级内先「无描述」后「带描述」
        for (preferredType in intArrayOf(
            EmbeddedPicture.TYPE_FRONT_COVER,
            EmbeddedPicture.TYPE_BACK_COVER
        )) {
            val ofType = nonIcon.filter { it.type == preferredType }
            if (ofType.isNotEmpty()) {
                val blank = ofType.firstOrNull { it.description.isBlank() }
                return (blank ?: ofType.first()).let { it.data to it.mime }
            }
        }

        // ③ fallbackToAny：优先级 Other(0) > 其他非图标类型 > 兜底第一张
        val other = nonIcon.firstOrNull { it.type == EmbeddedPicture.TYPE_OTHER }
            ?: nonIcon.firstOrNull()
            ?: return null
        return other.data to other.mime
    }
}
