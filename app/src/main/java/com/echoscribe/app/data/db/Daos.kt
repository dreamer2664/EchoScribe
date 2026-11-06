package com.echoscribe.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE deletedAt IS NULL AND isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE deletedAt IS NULL AND folderId IS :folderId ORDER BY updatedAt DESC")
    fun observeInFolder(folderId: String?): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<TranscriptEntity?>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun getById(id: String): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE id = :id")
    fun getByIdOnce(id: String): TranscriptEntity?

    @Upsert
    suspend fun upsert(transcript: TranscriptEntity)

    @Query("UPDATE transcripts SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE transcripts SET originalText = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateOriginalText(id: String, text: String, now: Long)

    @Query("UPDATE transcripts SET cleanedText = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateCleanedText(id: String, text: String?, now: Long)

    @Query("UPDATE transcripts SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, now: Long)

    @Query("UPDATE transcripts SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE transcripts SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE transcripts SET isFinalised = :finalised, updatedAt = :now WHERE id = :id")
    suspend fun setFinalised(id: String, finalised: Boolean, now: Long)

    @Query("SELECT * FROM transcripts WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun getTrashOlderThan(before: Long): List<TranscriptEntity>

    /**
     * Full-text search across title and body. `match` uses FTS4 query syntax; the
     * repository sanitises user input into prefix terms so a plain phrase like
     * "riunione budget" behaves as expected and never throws on stray characters.
     */
    @Query(
        """
        SELECT t.* FROM transcripts t
        JOIN transcripts_fts f ON f.rowid = t.rowid
        WHERE transcripts_fts MATCH :match AND t.deletedAt IS NULL
        ORDER BY t.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun search(match: String, limit: Int = 100): List<TranscriptEntity>

    @Query("SELECT COUNT(*) FROM transcripts WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM transcripts WHERE deletedAt IS NULL")
    fun observeTotalDuration(): Flow<Long>

    @Query("SELECT DISTINCT language FROM transcripts WHERE deletedAt IS NULL")
    fun observeLanguages(): Flow<List<String>>
}

@Dao
interface SegmentDao {

    @Query("SELECT * FROM segments WHERE transcriptId = :transcriptId ORDER BY idx ASC")
    suspend fun getForTranscript(transcriptId: String): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE transcriptId = :transcriptId ORDER BY idx ASC")
    fun observeForTranscript(transcriptId: String): Flow<List<SegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE transcriptId = :transcriptId")
    suspend fun deleteForTranscript(transcriptId: String)

    @Query("SELECT COUNT(*) FROM segments WHERE transcriptId = :transcriptId")
    suspend fun countForTranscript(transcriptId: String): Int
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE isSystem = 0 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT COUNT(*) FROM transcripts WHERE folderId = :folderId AND deletedAt IS NULL")
    fun observeCount(folderId: String): Flow<Int>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)

    /** Moving a folder to the trash must not orphan its children. */
    @Query("UPDATE transcripts SET folderId = :toFolderId WHERE folderId = :fromFolderId")
    suspend fun reassignTranscripts(fromFolderId: String, toFolderId: String?)

    @Query("UPDATE folders SET parentId = :toParentId WHERE parentId = :fromParentId")
    suspend fun reassignChildFolders(fromParentId: String, toParentId: String?)

    @Query("SELECT COUNT(*) FROM folders WHERE parentId = :parentId")
    suspend fun childCount(parentId: String): Int

    @Transaction
    suspend fun deleteAndRehome(id: String, newParent: String?) {
        reassignTranscripts(id, newParent)
        reassignChildFolders(id, newParent)
        delete(id)
    }

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM folders")
    suspend fun maxSortOrder(): Int
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE")
    suspend fun findByName(name: String): TagEntity?

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: TranscriptTagCrossRef)

    @Query("DELETE FROM transcript_tags WHERE transcriptId = :transcriptId AND tagId = :tagId")
    suspend fun unlink(transcriptId: String, tagId: String)

    @Query(
        """
        SELECT folders.* FROM tags
        JOIN transcript_tags ON tags.id = transcript_tags.tagId
        JOIN transcripts ON transcripts.id = transcript_tags.transcriptId
        WHERE tags.name = :name AND transcripts.deletedAt IS NULL
        """
    )
    suspend fun transcriptsForTagPlaceholder(name: String): List<FolderEntity>
}
