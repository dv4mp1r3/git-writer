package dev.jotalac.testing.fakes

import dev.jotalac.feature.notebooks_management.data.NotebookDao
import dev.jotalac.feature.notebooks_management.data.NotebookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The notebooks table in memory, with the same rules as the Room queries in [NotebookDao]. */
class FakeNotebookDao : NotebookDao {

    val rows = MutableStateFlow<List<NotebookEntity>>(emptyList())
    private var lastId = 0L

    /** Makes saving fail, like a broken database. */
    var upsertError: Throwable? = null

    override fun getNotebooksAsFlow(): Flow<List<NotebookEntity>> = rows.map { list -> list.sortedByDescending { it.id } }

    override fun getNotebookByIdAsFlow(id: Long): Flow<NotebookEntity?> = rows.map { list -> list.find { it.id == id } }

    override suspend fun getNotebookById(id: Long): NotebookEntity? = rows.value.find { it.id == id }

    override suspend fun getNotebookByName(name: String): NotebookEntity? = rows.value.find { it.name == name }

    override suspend fun getNotebookByDirectoryPath(directoryPath: String): NotebookEntity? =
        rows.value.find { it.directoryPath == directoryPath }

    override suspend fun getNotebookByNameExcludingId(name: String, excludeId: Long): NotebookEntity? =
        rows.value.find { it.name == name && it.id != excludeId }

    /** Like Room's @Upsert: id 0 inserts with a new id and returns it, an existing id is replaced (-1). */
    override suspend fun upsertNotebook(notebook: NotebookEntity): Long {
        upsertError?.let { throw it }
        if (notebook.id == 0L || rows.value.none { it.id == notebook.id }) {
            val id = if (notebook.id == 0L) ++lastId else notebook.id
            rows.update { it + notebook.copy(id = id) }
            return id
        }
        rows.update { list -> list.map { if (it.id == notebook.id) notebook else it } }
        return -1
    }

    override suspend fun deleteNotebook(id: Long) {
        rows.update { list -> list.filterNot { it.id == id } }
    }
}
