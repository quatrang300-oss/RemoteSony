package com.quatrang.remotesony.tv

/**
 * Ký tự -> phím bấm Android (KEYCODE_*), để gõ được vào MỌI ô nhập trên TV
 * (trình duyệt của Downloader, ô không nhận bộ gõ từ xa…) giống như cắm bàn phím thật.
 * [shift] = phải giữ Shift khi bấm (chữ hoa, ! ? : _ …).
 */
object TvChars {
    data class Key(val code: Int, val shift: Boolean = false)

    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val SHIFT_LEFT = 59

    fun of(c: Char): Key? = when (c) {
        in 'a'..'z' -> Key(29 + (c - 'a'))
        in 'A'..'Z' -> Key(29 + (c - 'A'), true)
        in '0'..'9' -> Key(7 + (c - '0'))
        ' ' -> Key(SPACE)
        '\n' -> Key(ENTER)
        '.' -> Key(56)
        ',' -> Key(55)
        '/' -> Key(76)
        '-' -> Key(69)
        '=' -> Key(70)
        '[' -> Key(71)
        ']' -> Key(72)
        '\\' -> Key(73)
        ';' -> Key(74)
        '\'' -> Key(75)
        '`' -> Key(68)
        '@' -> Key(77)
        '#' -> Key(18)
        '*' -> Key(17)
        '+' -> Key(81)
        '(' -> Key(162) // NUMPAD_LEFT_PAREN: không cần Shift
        ')' -> Key(163)
        '!' -> Key(8, true)
        '$' -> Key(11, true)
        '%' -> Key(12, true)
        '^' -> Key(13, true)
        '&' -> Key(14, true)
        '_' -> Key(69, true)
        ':' -> Key(74, true)
        '"' -> Key(75, true)
        '?' -> Key(76, true)
        '<' -> Key(55, true)
        '>' -> Key(56, true)
        '~' -> Key(68, true)
        '{' -> Key(71, true)
        '}' -> Key(72, true)
        '|' -> Key(73, true)
        else -> {
            // chữ có dấu: gõ chữ gốc (ô -> o, đ -> d)
            val base = com.quatrang.remotesony.stripAccents(c.toString())
            if (base.length == 1 && base[0] != c) of(base[0]) else null
        }
    }
}
