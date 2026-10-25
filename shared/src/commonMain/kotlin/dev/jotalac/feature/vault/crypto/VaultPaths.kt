package dev.jotalac.feature.vault.crypto

/**
 * Canonical note path inside a notebook: NFC-normalized, forward slashes, relative.
 * This exact string is what gets hashed into the encrypted file name and stored in the
 * encrypted header, so it must be stable across platforms.
 */
fun canonicalRelPath(raw: String): String {
    val normalized = nfcNormalize(raw).replace('\\', '/')
    if (normalized.isEmpty()) throw VaultFormatException("Empty note path")
    if (normalized.startsWith('/')) throw VaultFormatException("Absolute note path: $raw")
    if (normalized.endsWith('/')) throw VaultFormatException("Note path must point to a file: $raw")
    if (normalized.contains('\u0000')) throw VaultFormatException("Invalid character in note path: $raw")
    for (segment in normalized.split('/')) {
        if (segment.isEmpty()) throw VaultFormatException("Empty segment in note path: $raw")
        if (segment == "..") throw VaultFormatException("Path traversal in note path: $raw")
    }
    return normalized
}
