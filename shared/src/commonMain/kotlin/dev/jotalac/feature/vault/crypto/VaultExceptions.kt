package dev.jotalac.feature.vault.crypto

/** Base class for all vault crypto failures. */
open class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The ciphertext failed AEAD verification - wrong key, tampered data, or a blob swapped between files. */
class VaultTamperedDataException(
    message: String = "Vault data failed integrity verification",
    cause: Throwable? = null,
) : VaultCryptoException(message, cause)

/** The stored container/keystore bytes are not a parseable vault structure. */
class VaultFormatException(message: String, cause: Throwable? = null) : VaultCryptoException(message, cause)

/** The passphrase (or recovery key) did not open the keystore. */
class VaultWrongPassphraseException(cause: Throwable? = null) :
    VaultCryptoException("Wrong passphrase or corrupted keystore", cause)
