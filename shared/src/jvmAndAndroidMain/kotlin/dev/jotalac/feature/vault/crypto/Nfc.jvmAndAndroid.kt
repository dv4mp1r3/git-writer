package dev.jotalac.feature.vault.crypto

import java.text.Normalizer

actual fun nfcNormalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
