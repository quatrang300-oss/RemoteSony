package com.quatrang.remotesony.ir

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Phím điều khiển. [code] = mã phím Android (gửi qua Wi-Fi),
 * [irCmd]/[irAddr]/[irAddrBits] = mã hồng ngoại Sony SIRC (gửi bằng mắt IR của điện thoại).
 */
enum class TvKey(val code: Int, val irCmd: Int?, val irAddr: Int = 1, val irAddrBits: Int = 5) {
    POWER(26, 21),
    VOL_UP(24, 18),
    VOL_DOWN(25, 19),
    MUTE(164, 20),
    CH_UP(166, 16),
    CH_DOWN(167, 17),
    INPUT(178, 37),
    UP(19, 116),
    DOWN(20, 117),
    LEFT(21, 52),
    RIGHT(22, 51),
    OK(23, 101),
    HOME(3, 96),
    BACK(4, 35, 0x97, 8),
    MENU(82, 54, 0x97, 8),       // nút Options trên remote Sony
    PLAY_PAUSE(85, 26, 0x97, 8),
    ENTER(66, 101),
    DEL(67, null),
    SETTINGS(176, null),
    D1(8, 0), D2(9, 1), D3(10, 2), D4(11, 3), D5(12, 4),
    D6(13, 5), D7(14, 6), D8(15, 7), D9(16, 8), D0(7, 9);

    companion object {
        val digits = listOf(D1, D2, D3, D4, D5, D6, D7, D8, D9, D0)
    }
}

/** Phát mã hồng ngoại Sony SIRC (12/15 bit, sóng mang 40 kHz). */
class SonyIr(context: Context) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean = runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false)

    fun send(key: TvKey): Boolean {
        val cmd = key.irCmd ?: return false
        val mgr = ir ?: return false
        if (!available) return false
        return runCatching {
            mgr.transmit(40000, pattern(cmd, key.irAddr, key.irAddrBits))
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val FRAME_US = 45000

        fun frame(cmd: Int, addr: Int, addrBits: Int): IntArray {
            val p = ArrayList<Int>()
            p += 2400; p += 600
            for (i in 0 until 7) { p += if ((cmd shr i) and 1 == 1) 1200 else 600; p += 600 }
            for (i in 0 until addrBits) { p += if ((addr shr i) and 1 == 1) 1200 else 600; p += 600 }
            val used = p.sum()
            p[p.size - 1] = 600 + (FRAME_US - used).coerceAtLeast(0)
            return p.toIntArray()
        }

        /** Sony yêu cầu gửi lặp tối thiểu 3 lần. */
        fun pattern(cmd: Int, addr: Int, addrBits: Int): IntArray {
            val f = frame(cmd, addr, addrBits)
            return f + f + f
        }
    }
}
