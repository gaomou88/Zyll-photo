package app.zyll.cloud

import app.zyll.core.cloud.R
import app.zyll.media.tr

/**
 * 端到端加密密码的要求：至少 10 位，同时有字母和数字，不能是常见弱口令。
 * 只在设置新密码时检查（解锁、关闭加密时输入的是以前的密码，不检查）。
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    /** 不合格时返回给用户看的原因；合格返回 null。 */
    fun problem(password: String): String? = when {
        password.length < MIN_LENGTH -> tr(R.string.e2ee_pw_too_short, MIN_LENGTH)
        password.none { it.isLetter() } || password.none { it.isDigit() } -> tr(R.string.e2ee_pw_need_mix)
        isCommon(password) -> tr(R.string.e2ee_pw_common)
        else -> null
    }

    private fun isCommon(password: String): Boolean {
        val p = password.lowercase()
        if (p in COMMON) return true
        // 同一个字符一直重复（aaaa1111）、或者整串是键盘 / 数字顺序
        if (p.toSet().size <= 2) return true
        if (SEQUENCES.any { seq -> p.length >= MIN_LENGTH && seq.contains(p) }) return true
        // 常见单词 + 一串顺序或重复的数字（password2024、qwerty123、abc12345、woaini1314）
        val letters = p.filter { it.isLetter() }
        val digits = p.filter { it.isDigit() }
        val lettersThenDigits = p == letters + digits || p == digits + letters
        if (lettersThenDigits && letters in WORDS && (isSimpleDigits(digits) || isYear(digits))) return true
        return false
    }

    private fun isSimpleDigits(d: String): Boolean =
        d.toSet().size == 1 || "01234567890".contains(d) || "9876543210".contains(d) || d in COMMON_NUMBERS

    private fun isYear(d: String) = d.length == 4 && d.toIntOrNull()?.let { it in 1950..2100 } == true

    private val SEQUENCES = listOf(
        "1234567890qwertyuiop", "qwertyuiopasdfghjklzxcvbnm", "1qaz2wsx3edc4rfv5tgb", "zaq12wsxcde34rfv",
        "abcdefghijklmnopqrstuvwxyz0123456789", "q1w2e3r4t5y6u7i8o9p0", "1q2w3e4r5t6y7u8i9o0p", "a1b2c3d4e5f6g7h8",
    )

    private val COMMON_NUMBERS = setOf("1314", "520", "5201314", "1314520", "147258369", "159357", "123123", "112233", "666", "888", "007")

    private val WORDS = setOf(
        "password", "passw", "pass", "passwd", "pwd", "qwerty", "qwer", "asdf", "zxcv", "qaz", "wsx", "qweasd", "asdfgh", "zxcvbn",
        "abc", "abcd", "abcde", "abcdef", "a", "aa", "aaa", "q", "qq", "qqq", "z", "x", "admin", "root", "user", "test", "guest", "login",
        "welcome", "iloveyou", "love", "letmein", "monkey", "dragon", "master", "football", "baseball", "sunshine", "princess", "shadow",
        "superman", "batman", "hello", "google", "apple", "android", "samsung", "huawei", "xiaomi", "zyll", "photo", "photos", "gallery",
        "woaini", "wang", "zhang", "li", "liu", "chen", "yang", "zhao", "huang", "zhou", "wu", "lin", "aini", "baobao", "laopo", "laogong",
    )

    private val COMMON = setOf(
        "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "passw0rd1", "qwerty123", "qwerty12", "qwerty1234", "1qaz2wsx",
        "1qazxsw2", "zaq12wsx", "1q2w3e4r", "1q2w3e4r5t", "q1w2e3r4", "q1w2e3r4t5", "qwer1234", "1234qwer", "asdf1234", "1234asdf",
        "zxcv1234", "abc12345", "abcd1234", "a1234567", "a12345678", "aa123456", "aa112233", "abc123456", "abc123abc", "12345678a",
        "123456789a", "123456abc", "123qweasd", "qweasd123", "qazwsx123", "asd123456", "zxc123456", "iloveyou1", "iloveyou2", "admin123",
        "admin1234", "welcome1", "welcome123", "letmein1", "monkey123", "dragon123", "football1", "baseball1", "trustno1", "sunshine1",
        "princess1", "master123", "superman1", "test1234", "root1234", "woaini1314", "woaini520", "5201314a", "a5201314", "qq123456",
        "qq5201314", "aa123123", "147258369a", "a147258369", "q123456789", "1a2b3c4d", "a1b2c3d4", "a1s2d3f4", "z1x2c3v4", "zyll1234",
        "zyll12345", "photo123", "android1", "google123", "samsung1", "huawei123", "xiaomi123", "change123", "changeme1", "default1",
    )
}
