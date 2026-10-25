package dev.jotalac.feature.vault

import dev.jotalac.feature.vault.crypto.Hex
import dev.jotalac.feature.vault.crypto.VaultCrypto
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeys
import dev.jotalac.feature.vault.crypto.canonicalRelPath
import dev.jotalac.feature.vault.crypto.deriveKek
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultKeysTest {

    private fun fixedMasterKey(): ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun subkeys_are_domain_separated() = runTest {
        val keys = VaultKeys.fromMasterKey(fixedMasterKey())
        assertFalse(keys.contentKey.contentEquals(keys.nameKey))
        assertFalse(keys.contentKey.contentEquals(keys.masterKey))
    }

    @Test
    fun same_master_key_produces_same_subkeys() = runTest {
        val a = VaultKeys.fromMasterKey(fixedMasterKey())
        val b = VaultKeys.fromMasterKey(fixedMasterKey())
        assertContentEquals(a.contentKey, b.contentKey)
        assertContentEquals(a.nameKey, b.nameKey)
    }

    @Test
    fun cname_is_stable_and_well_formed() = runTest {
        val keys = VaultKeys.fromMasterKey(fixedMasterKey())
        val cname1 = keys.cnameOf("Дневник/2025.md")
        val cname2 = keys.cnameOf("Дневник/2025.md")
        assertEquals(cname1, cname2)
        assertEquals(32, cname1.length)
        assertTrue(cname1.all { it in 'a'..'z' || it in '2'..'7' })
    }

    @Test
    fun cname_differs_between_paths_and_keys() = runTest {
        val keys = VaultKeys.fromMasterKey(fixedMasterKey())
        val other = VaultKeys.fromMasterKey(ByteArray(32) { (it * 11 + 5).toByte() })
        assertFalse(keys.cnameOf("a.md") == keys.cnameOf("b.md"))
        assertFalse(keys.cnameOf("a.md") == other.cnameOf("a.md"))
    }

    @Test
    fun cname_is_nfc_insensitive() = runTest {
        val keys = VaultKeys.fromMasterKey(fixedMasterKey())
        // é as a single code point vs e + combining acute - same name, same cname
        val composed = keys.cnameOf("café.md")
        val decomposed = keys.cnameOf("café.md")
        assertEquals(composed, decomposed)
    }

    @Test
    fun canonical_path_rejects_malformed_input() {
        assertFailsWith<VaultFormatException> { canonicalRelPath("") }
        assertFailsWith<VaultFormatException> { canonicalRelPath("/abs/path.md") }
        assertFailsWith<VaultFormatException> { canonicalRelPath("notes/../secret.md") }
        assertFailsWith<VaultFormatException> { canonicalRelPath("folder/") }
        assertFailsWith<VaultFormatException> { canonicalRelPath("a//b.md") }
        assertEquals("folder/заметка.md", canonicalRelPath("folder\\заметка.md"))
        assertEquals("a/b.md", canonicalRelPath("a/b.md"))
    }

    // Pinned subkey derivation - catches accidental changes to the KDF contexts or hash setup.
    @Test
    fun subkeys_known_answer_vector() = runTest {
        VaultCrypto.init()
        val keys = VaultKeys.fromMasterKey(fixedMasterKey())
        assertEquals("39d0d4c6430bdd0f4c4988a151c4fcdcdedc6e59201db7282fdcf619ffa5198e", Hex.encode(keys.contentKey))
        assertEquals("61d7e7ccc22cc5143a0df4a9a9520b05178fef2600ba74f0660ee37fb35ef740", Hex.encode(keys.nameKey))
        assertEquals("5uulo2g3zqirvjdiwth6jnf2ew3samgo", keys.cnameOf("Идеи/черновик.md"))
    }

    @Test
    fun kek_is_deterministic_and_parameter_sensitive() = runTest {
        VaultCrypto.init()
        val salt = ByteArray(16) { it.toByte() }
        val kek1 = deriveKek("пароль", salt, opsLimit = 1, memLimit = 8_388_608)
        val kek2 = deriveKek("пароль", salt, opsLimit = 1, memLimit = 8_388_608)
        assertContentEquals(kek1, kek2)
        assertEquals(32, kek1.size)
        val otherSalt = deriveKek("пароль", salt.copyOf().also { it[0] = 9 }, opsLimit = 1, memLimit = 8_388_608)
        assertFalse(kek1.contentEquals(otherSalt))
    }
}
