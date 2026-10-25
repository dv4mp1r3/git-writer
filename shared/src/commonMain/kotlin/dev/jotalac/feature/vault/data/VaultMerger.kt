package dev.jotalac.feature.vault.data

import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.crypto.UnlockedVault
import dev.jotalac.feature.vault.crypto.VaultFileCodec

/**
 * Merges the conflicted versions of one encrypted note: decrypts the three index stages, combines
 * their content line by line (see [diff3Merge]) and encrypts the result back under the same name.
 * Notes edited in different places merge silently, like plain text notes always did; notes edited
 * in the same place stay conflicted for the resolution dialog.
 */
class VaultMerger {

    sealed interface Outcome {

        /** The versions combined cleanly - [encryptedContent] replaces the conflicted file. */
        class Merged(val encryptedContent: ByteArray) : Outcome

        /** The content needs a human decision; [relPath] names the note for the UI. */
        class Conflicted(val relPath: String) : Outcome

        /** No automatic merge is possible: a side deleted the note, or the ciphertext does not decode. */
        data object Manual : Outcome
    }

    /**
     * Merges the index stages of one note: 1 = common ancestor (missing when both sides added the
     * note independently), 2 = local, 3 = remote. A side that deleted the note has no stage, and
     * deletion versus edit is a decision for the user.
     */
    suspend fun mergeOne(cname: String, versions: Map<Int, ByteArray>, session: OpenedVault): Outcome {
        val vault = session.vault
        val oursBlob = versions[2] ?: return Outcome.Manual
        val theirsBlob = versions[3] ?: return Outcome.Manual
        val ours = decodeOrNull(oursBlob, vault, cname) ?: return Outcome.Manual
        val theirs = decodeOrNull(theirsBlob, vault, cname) ?: return Outcome.Manual
        val base = versions[1]?.let { decodeOrNull(it, vault, cname) }

        val chunks = diff3Merge(
            base = base?.content?.decodeToString()?.split('\n') ?: listOf(""),
            ours = ours.content.decodeToString().split('\n'),
            theirs = theirs.content.decodeToString().split('\n'),
        )
        if (chunks.any { it is MergedChunk.Conflict }) return Outcome.Conflicted(ours.relPath)

        val merged = chunks.flatMap { (it as MergedChunk.Clean).lines }.joinToString("\n")
        return Outcome.Merged(VaultFileCodec.encode(vault, ours.relPath, merged.encodeToByteArray()))
    }

    /** The real relative path of a note that cannot be merged, decoded from the side that still has it. */
    suspend fun noteRelPath(cname: String, versions: Map<Int, ByteArray>, session: OpenedVault): String? =
        (versions[2] ?: versions[3])?.let { decodeOrNull(it, session.vault, cname) }?.relPath

    private suspend fun decodeOrNull(
        blob: ByteArray,
        vault: UnlockedVault,
        cname: String,
    ): VaultFileCodec.DecodedVaultFile? = runCatching { VaultFileCodec.decode(blob, vault, cname) }.getOrNull()
}
