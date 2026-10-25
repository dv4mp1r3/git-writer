package dev.jotalac.feature.vault.crypto

import platform.Foundation.NSString
import platform.Foundation.create

actual fun nfcNormalize(text: String): String =
    NSString.create(string = text).precomposedStringWithCanonicalMapping
