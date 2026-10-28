package dev.jotalac.feature.vault.crypto

/**
 * RFC 4648 base32 (the vault never emits padding; the decoder still tolerates trailing '=').
 * Used for encrypted file names and the recovery key, so only filesystem-safe characters appear.
 */
object Base32 {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val UPPERCASE_ALPHABET = ALPHABET.uppercase()

    /** [uppercase] only affects readability (recovery key); file names stay lowercase. */
    fun encode(bytes: ByteArray, uppercase: Boolean = false): String {
        val alphabet = if (uppercase) UPPERCASE_ALPHABET else ALPHABET
        val sb = StringBuilder(((bytes.size * 8) + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alphabet[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            sb.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }

    /** Case-insensitive; accepts uppercase input and ignores trailing '=' padding. */
    fun decode(text: String): ByteArray {
        val cleaned = text.lowercase().trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        if (cleaned.length == 1) throw VaultFormatException("Invalid base32 length: $text")
        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var outIndex = 0
        for (ch in cleaned) {
            val value = when (ch) {
                in 'a'..'z' -> ch - 'a'
                in '2'..'7' -> ch - '2' + 26
                else -> throw VaultFormatException("Invalid base32 character: $ch")
            }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val cleaned = text.lowercase()
        if (cleaned.length % 2 != 0) throw VaultFormatException("Odd hex length: $text")
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = digit(cleaned[i * 2])
            val lo = digit(cleaned[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        else -> throw VaultFormatException("Invalid hex character: $ch")
    }
}
