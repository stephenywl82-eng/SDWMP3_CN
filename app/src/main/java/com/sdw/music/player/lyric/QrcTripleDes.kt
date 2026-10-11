package com.sdw.music.player.lyric

/**
 * QQ 音乐 QRC 歌词专用「非标准 Triple-DES」解密实现。
 *
 * 与标准 DES 的差异：S 盒、P 置换、PC-2 偏移量均为 QQ 私有变体（逆向所得），
 * 因此不能直接用 javax.crypto 的标准 DES/3DES —— 必须自带这份实现。
 * 参考实现：WXRIW/QQMusicDecoder (C#)、luren-dc/QQMusicApi (Python)。
 *
 * 只需解密方向，故只保留 DECRYPT 路径。
 */
internal object QrcTripleDes {

    private const val DECRYPT = 0
    private const val ENCRYPT = 1

    /** QRC 3DES 固定密钥（QQ 客户端内置常量） */
    private val QRC_KEY: ByteArray = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val SBOX: Array<IntArray> = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11)
    )

    private val KEY_PERM_C = intArrayOf(
56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    )
    private val KEY_PERM_D = intArrayOf(
62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    )
    private val KEY_COMPRESSION = intArrayOf(
13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )
    private val RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private data class BitTerm(val src: String, val bit: Int, val pos: Int)

    // ==== 以下置换表由 Python 参考实现机械转写，勿手改 ====
    private val IP_S0 = listOf(BitTerm("v1", 6, 31), BitTerm("v1", 14, 30), BitTerm("v1", 22, 29), BitTerm("v1", 30, 28), BitTerm("v0", 6, 27), BitTerm("v0", 14, 26), BitTerm("v0", 22, 25), BitTerm("v0", 30, 24), BitTerm("v1", 4, 23), BitTerm("v1", 12, 22), BitTerm("v1", 20, 21), BitTerm("v1", 28, 20), BitTerm("v0", 4, 19), BitTerm("v0", 12, 18), BitTerm("v0", 20, 17), BitTerm("v0", 28, 16), BitTerm("v1", 2, 15), BitTerm("v1", 10, 14), BitTerm("v1", 18, 13), BitTerm("v1", 26, 12), BitTerm("v0", 2, 11), BitTerm("v0", 10, 10), BitTerm("v0", 18, 9), BitTerm("v0", 26, 8), BitTerm("v1", 0, 7), BitTerm("v1", 8, 6), BitTerm("v1", 16, 5), BitTerm("v1", 24, 4), BitTerm("v0", 0, 3), BitTerm("v0", 8, 2), BitTerm("v0", 16, 1), BitTerm("v0", 24, 0))
    private val IP_S1 = listOf(BitTerm("v1", 7, 31), BitTerm("v1", 15, 30), BitTerm("v1", 23, 29), BitTerm("v1", 31, 28), BitTerm("v0", 7, 27), BitTerm("v0", 15, 26), BitTerm("v0", 23, 25), BitTerm("v0", 31, 24), BitTerm("v1", 5, 23), BitTerm("v1", 13, 22), BitTerm("v1", 21, 21), BitTerm("v1", 29, 20), BitTerm("v0", 5, 19), BitTerm("v0", 13, 18), BitTerm("v0", 21, 17), BitTerm("v0", 29, 16), BitTerm("v1", 3, 15), BitTerm("v1", 11, 14), BitTerm("v1", 19, 13), BitTerm("v1", 27, 12), BitTerm("v0", 3, 11), BitTerm("v0", 11, 10), BitTerm("v0", 19, 9), BitTerm("v0", 27, 8), BitTerm("v1", 1, 7), BitTerm("v1", 9, 6), BitTerm("v1", 17, 5), BitTerm("v1", 25, 4), BitTerm("v0", 1, 3), BitTerm("v0", 9, 2), BitTerm("v0", 17, 1), BitTerm("v0", 25, 0))
    private val INV_0 = listOf(BitTerm("s1", 27, 7), BitTerm("s0", 27, 6), BitTerm("s1", 19, 5), BitTerm("s0", 19, 4), BitTerm("s1", 11, 3), BitTerm("s0", 11, 2), BitTerm("s1", 3, 1), BitTerm("s0", 3, 0))
    private val INV_1 = listOf(BitTerm("s1", 26, 7), BitTerm("s0", 26, 6), BitTerm("s1", 18, 5), BitTerm("s0", 18, 4), BitTerm("s1", 10, 3), BitTerm("s0", 10, 2), BitTerm("s1", 2, 1), BitTerm("s0", 2, 0))
    private val INV_2 = listOf(BitTerm("s1", 25, 7), BitTerm("s0", 25, 6), BitTerm("s1", 17, 5), BitTerm("s0", 17, 4), BitTerm("s1", 9, 3), BitTerm("s0", 9, 2), BitTerm("s1", 1, 1), BitTerm("s0", 1, 0))
    private val INV_3 = listOf(BitTerm("s1", 24, 7), BitTerm("s0", 24, 6), BitTerm("s1", 16, 5), BitTerm("s0", 16, 4), BitTerm("s1", 8, 3), BitTerm("s0", 8, 2), BitTerm("s1", 0, 1), BitTerm("s0", 0, 0))
    private val INV_4 = listOf(BitTerm("s1", 31, 7), BitTerm("s0", 31, 6), BitTerm("s1", 23, 5), BitTerm("s0", 23, 4), BitTerm("s1", 15, 3), BitTerm("s0", 15, 2), BitTerm("s1", 7, 1), BitTerm("s0", 7, 0))
    private val INV_5 = listOf(BitTerm("s1", 30, 7), BitTerm("s0", 30, 6), BitTerm("s1", 22, 5), BitTerm("s0", 22, 4), BitTerm("s1", 14, 3), BitTerm("s0", 14, 2), BitTerm("s1", 6, 1), BitTerm("s0", 6, 0))
    private val INV_6 = listOf(BitTerm("s1", 29, 7), BitTerm("s0", 29, 6), BitTerm("s1", 21, 5), BitTerm("s0", 21, 4), BitTerm("s1", 13, 3), BitTerm("s0", 13, 2), BitTerm("s1", 5, 1), BitTerm("s0", 5, 0))
    private val INV_7 = listOf(BitTerm("s1", 28, 7), BitTerm("s0", 28, 6), BitTerm("s1", 20, 5), BitTerm("s0", 20, 4), BitTerm("s1", 12, 3), BitTerm("s0", 12, 2), BitTerm("s1", 4, 1), BitTerm("s0", 4, 0))
    private val F_OUT = listOf(BitTerm("s1", 16, 31), BitTerm("s1", 25, 30), BitTerm("s1", 12, 29), BitTerm("s1", 11, 28), BitTerm("s1", 3, 27), BitTerm("s1", 20, 26), BitTerm("s1", 4, 25), BitTerm("s1", 15, 24), BitTerm("s1", 31, 23), BitTerm("s1", 17, 22), BitTerm("s1", 9, 21), BitTerm("s1", 6, 20), BitTerm("s1", 27, 19), BitTerm("s1", 14, 18), BitTerm("s1", 1, 17), BitTerm("s1", 22, 16), BitTerm("s1", 30, 15), BitTerm("s1", 24, 14), BitTerm("s1", 8, 13), BitTerm("s1", 18, 12), BitTerm("s1", 0, 11), BitTerm("s1", 5, 10), BitTerm("s1", 29, 9), BitTerm("s1", 23, 8), BitTerm("s1", 13, 7), BitTerm("s1", 19, 6), BitTerm("s1", 2, 5), BitTerm("s1", 26, 4), BitTerm("s1", 10, 3), BitTerm("s1", 21, 2), BitTerm("s1", 28, 1), BitTerm("s1", 7, 0))

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) shr 1) or ((a and 1) shl 4)

    private fun permute(terms: List<BitTerm>, v0: Long, v1: Long, s0: Long, s1: Long): Long {
        var acc = 0L
        for (t in terms) {
            val src = when (t.src) {
                "v0" -> v0; "v1" -> v1; "s0" -> s0; "s1" -> s1; else -> s1
            }
            acc = acc or (((src shr t.bit) and 1L) shl t.pos)
        }
        return acc
    }

    private fun initialPermutation(input: ByteArray): LongArray {
        val v0 = ((input[0].toLong() and 0xFF) or ((input[1].toLong() and 0xFF) shl 8) or
                  ((input[2].toLong() and 0xFF) shl 16) or ((input[3].toLong() and 0xFF) shl 24))
        val v1 = ((input[4].toLong() and 0xFF) or ((input[5].toLong() and 0xFF) shl 8) or
                  ((input[6].toLong() and 0xFF) shl 16) or ((input[7].toLong() and 0xFF) shl 24))
        return longArrayOf(
            permute(IP_S0, v0, v1, 0L, 0L),
            permute(IP_S1, v0, v1, 0L, 0L),
        )
    }

    private fun inversePermutation(s0: Long, s1: Long): ByteArray {
        val data = ByteArray(8)
        val tables = arrayOf(INV_0, INV_1, INV_2, INV_3, INV_4, INV_5, INV_6, INV_7)
        for (i in 0..7) data[i] = permute(tables[i], 0L, 0L, s0, s1).toByte()
        return data
    }

    private fun f(state: Long, key: IntArray): Long {
        val t1 = ((state and 1L) shl 31) or
                 ((state and 0xF8000000L) shr 1) or
                 ((state and 0x1F800000L) shr 3) or
                 ((state and 0x01F80000L) shr 5) or
                 ((state and 0x001F8000L) shr 7)
        val t2 = ((state and 0x0001F800L) shl 15) or
                 ((state and 0x00001F80L) shl 13) or
                 ((state and 0x000001F8L) shl 11) or
                 ((state and 0x0000001FL) shl 9) or
                 ((state and 0x80000000L) shr 23)

        val k0 = (((t1 shr 24) and 0xFFL).toInt()) xor key[0]
        val k1 = (((t1 shr 16) and 0xFFL).toInt()) xor key[1]
        val k2 = (((t1 shr 8) and 0xFFL).toInt()) xor key[2]
        val k3 = (((t2 shr 24) and 0xFFL).toInt()) xor key[3]
        val k4 = (((t2 shr 16) and 0xFFL).toInt()) xor key[4]
        val k5 = (((t2 shr 8) and 0xFFL).toInt()) xor key[5]

        val st = ((SBOX[0][sboxBit(k0 shr 2)].toLong() shl 28) or
                  (SBOX[1][sboxBit(((k0 and 0x03) shl 4) or (k1 shr 4))].toLong() shl 24) or
                  (SBOX[2][sboxBit(((k1 and 0x0F) shl 2) or (k2 shr 6))].toLong() shl 20) or
                  (SBOX[3][sboxBit(k2 and 0x3F)].toLong() shl 16) or
                  (SBOX[4][sboxBit(k3 shr 2)].toLong() shl 12) or
                  (SBOX[5][sboxBit(((k3 and 0x03) shl 4) or (k4 shr 4))].toLong() shl 8) or
                  (SBOX[6][sboxBit(((k4 and 0x0F) shl 2) or (k5 shr 6))].toLong() shl 4) or
                  (SBOX[7][sboxBit(k5 and 0x3F)].toLong()))

        return permute(F_OUT, 0L, 0L, 0L, st)
    }

    private fun crypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        val ip = initialPermutation(input)
        var s0 = ip[0]
        var s1 = ip[1]
        for (idx in 0..14) {
            val prevS1 = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = prevS1
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val v0 = ((key[0].toLong() and 0xFF) or ((key[1].toLong() and 0xFF) shl 8) or
                  ((key[2].toLong() and 0xFF) shl 16) or ((key[3].toLong() and 0xFF) shl 24))
        val v1 = ((key[4].toLong() and 0xFF) or ((key[5].toLong() and 0xFF) shl 8) or
                  ((key[6].toLong() and 0xFF) shl 16) or ((key[7].toLong() and 0xFF) shl 24))
        var c = 0L
        for (i in KEY_PERM_C.indices) {
            val b = KEY_PERM_C[i]
            val bit = if (b < 32) ((v0 shr (31 - b)) and 1L) else ((v1 shr (63 - b)) and 1L)
            c = c or (bit shl (31 - i))
        }
        var d = 0L
        for (i in KEY_PERM_D.indices) {
            val b = KEY_PERM_D[i]
            val bit = if (b < 32) ((v0 shr (31 - b)) and 1L) else ((v1 shr (63 - b)) and 1L)
            d = d or (bit shl (31 - i))
        }
        for (i in 0..15) {
            val sh = RND_SHIFT[i]
            c = ((c shl sh) or (c shr (28 - sh))) and 0xFFFFFFF0L
            d = ((d shl sh) or (d shr (28 - sh))) and 0xFFFFFFF0L
            val togen = if (mode == DECRYPT) 15 - i else i
            for (j in 0..5) schedule[togen][j] = 0
            for (j in 0..23) {
                val bit = (c shr (31 - KEY_COMPRESSION[j])) and 1L
                schedule[togen][j / 8] = schedule[togen][j / 8] or ((bit shl (7 - (j % 8))).toInt())
            }
            for (j in 24..47) {
                val bit = (d shr (31 - (KEY_COMPRESSION[j] - 27))) and 1L
                schedule[togen][j / 8] = schedule[togen][j / 8] or ((bit shl (7 - (j % 8))).toInt())
            }
        }
        return schedule
    }

    /**
     * 解密一整段 QRC 密文（长度须为 8 的倍数）。
     * @return 3DES 解密后的原始字节（通常仍是 zlib 流，需再 inflate）。
     */
    fun decrypt(encrypted: ByteArray): ByteArray {
        // 解密子密钥顺序：K3(D) -> K2(E) -> K1(D)
        val schedule = arrayOf(
            keySchedule(QRC_KEY.copyOfRange(16, 24), DECRYPT),
            keySchedule(QRC_KEY.copyOfRange(8, 16), ENCRYPT),
            keySchedule(QRC_KEY.copyOfRange(0, 8), DECRYPT),
        )
        val out = ByteArray(encrypted.size)
        var i = 0
        while (i + 8 <= encrypted.size) {
            var blk = encrypted.copyOfRange(i, i + 8)
            for (round in 0..2) blk = crypt(blk, schedule[round])
            System.arraycopy(blk, 0, out, i, 8)
            i += 8
        }
        return out
    }
}
