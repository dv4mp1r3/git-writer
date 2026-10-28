package dev.jotalac.feature.vault.crypto

/** Unicode NFC normalization, so the same visually identical name hashes identically on every platform. */
expect fun nfcNormalize(text: String): String
