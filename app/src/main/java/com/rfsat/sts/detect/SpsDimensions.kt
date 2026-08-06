package com.rfsat.sts.detect

/**
 * The picture size, read out of an H.264 sequence parameter set.
 *
 * The decoder has to be told the dimensions before it will start, and the
 * only place they are stated is the SPS the camera sends in its SDP. Guessing
 * costs a decoder that starts and then produces nothing, so it is parsed:
 * Exp-Golomb, with the emulation-prevention bytes removed first and the frame
 * cropping applied at the end, which is what turns 1280 x 736 of macroblocks
 * into the 1280 x 720 the camera actually sends.
 *
 * Pure arithmetic on a byte array, so it is unit-tested rather than trusted.
 */
object SpsDimensions {

    fun of(spsNal: ByteArray): Pair<Int, Int>? = runCatching {
        val rbsp = ArrayList<Byte>(spsNal.size)
        var i = 1                                   // skip the NAL header
        while (i < spsNal.size) {
            if (i + 2 < spsNal.size &&
                spsNal[i].toInt() == 0 && spsNal[i + 1].toInt() == 0 && spsNal[i + 2].toInt() == 3
            ) {
                rbsp.add(0); rbsp.add(0); i += 3    // 00 00 03 -> 00 00
            } else {
                rbsp.add(spsNal[i]); i++
            }
        }
        val bits = object {
            var pos = 0
            fun bit(): Int { val b = (rbsp[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1; pos++; return b }
            fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
            fun ue(): Int { var z = 0; while (bit() == 0 && z < 32) z++; return (1 shl z) - 1 + bits(z) }
            fun se(): Int { val k = ue(); return if (k % 2 == 0) -(k / 2) else (k + 1) / 2 }
        }
        val profile = bits.bits(8)
        bits.bits(16)                               // constraint flags + level
        bits.ue()                                   // sps id
        var chromaIdc = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            chromaIdc = bits.ue()
            if (chromaIdc == 3) bits.bit()
            bits.ue(); bits.ue(); bits.bit()
            if (bits.bit() == 1) {                  // scaling matrices
                val count = if (chromaIdc != 3) 8 else 12
                repeat(count) { idx ->
                    if (bits.bit() == 1) {
                        val size = if (idx < 6) 16 else 64
                        var last = 8; var next = 8
                        repeat(size) {
                            if (next != 0) next = (last + bits.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        bits.ue()                                   // log2_max_frame_num
        when (bits.ue()) {                          // pic_order_cnt_type
            0 -> bits.ue()
            1 -> { bits.bit(); bits.se(); bits.se(); repeat(bits.ue()) { bits.se() } }
        }
        bits.ue(); bits.bit()
        val widthMbs = bits.ue() + 1
        val heightMap = bits.ue() + 1
        val frameMbsOnly = bits.bit()
        if (frameMbsOnly == 0) bits.bit()
        bits.bit()
        var w = widthMbs * 16
        var h = (2 - frameMbsOnly) * heightMap * 16
        if (bits.bit() == 1) {                      // frame cropping
            val cl = bits.ue(); val cr = bits.ue(); val ct = bits.ue(); val cb = bits.ue()
            val cx = if (chromaIdc == 0) 1 else 2
            val cy = (if (chromaIdc <= 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (cl + cr) * cx
            h -= (ct + cb) * cy
        }
        Pair(w, h)
    }.getOrNull()
}
