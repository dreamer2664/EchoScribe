package com.echoscribe.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Storage model.
 *
 * A two-hour recording is roughly 270 KB of text and several thousand timed segments.
 * Storing those as one JSON blob would make every edit rewrite the whole document, so
 * text body and segments are separate tables and the editor only ever updates the
 * text body.
 */
@Entity(
    tableName = "transcripts",
    indices = [Index("folderId"), Index("updatedAt"), Index("isFavorite"), Index("deletedAt")],
)
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Body exactly as the engine produced it — never overwritten by cleanup. */
    val originalText: String,
    /** Optional AI/heuristic cleanup. `null` = user has not run cleanup. */
    val cleanedText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    /** BCP-47-ish short code: "en", "it", "auto". */
    val language: String,
    /** Which model produced this transcript, for provenance and reproducibility. */
    val modelId: String,
    /** Relative file name inside the app's recordings dir; null when audio was not kept. */
    val audioPath: String? = null,
    val audioBytes: Long = 0L,
    val folderId: String? = null,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    /** Soft delete so an accidental delete is recoverable. */
    val deletedAt: Long? = null,
    /** Number of timed segments, denormalised for cheap list rendering. */
    val segmentCount: Int = 0,
    /** True once the final (VAD + full-model) pass has completed. */
    val isFinalised: Boolean = false,
)

@Entity(
    tableName = "segments",
    primaryKeys = ["transcriptId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("transcriptId")],
)
data class SegmentEntity(
    val transcriptId: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** "LIVE" or "FINAL" — lets the UI show which parts are still provisional. */
    val origin: String,
    val confidence: Float? = null,
)

@Entity(
    tableName = "folders",
    indices = [Index("parentId")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Null = top-level section. Enables arbitrarily nested folders. */
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    /** Built-in sections (All, Favorites, Recent) are not user-editable. */
    val isSystem: Boolean = false,
    val iconKey: String? = null,
    val colorArgb: Int? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "transcript_tags",
    primaryKeys = ["transcriptId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class TranscriptTagCrossRef(
    val transcriptId: String,
    val tagId: String,
)

/**
 * Full-text index over title + body.
 *
 * `contentEntity` makes this an external-content FTS table, so the text is not
 * duplicated on disk; Room keeps the index in sync via triggers. Search therefore
 * stays O(index) even with hundreds of hours of transcripts.
 */
@Fts4(contentEntity = TranscriptEntity::class)
@Entity(tableName = "transcripts_fts")
data class TranscriptFts(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "originalText") val originalText: String,
)

/** Read model: a transcript row plus the tags attached to it. */
data class TranscriptWithTags(
    @Embedded val transcript: TranscriptEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = androidx.room.Junction(
            value = TranscriptTagCrossRef::class,
            parentColumn = "transcriptId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity> = emptyList(),
)

/** Read model: folder rows plus the number of non-deleted transcripts inside. */
data class FolderWithCount(
    @Embedded val folder: FolderEntity,
    val transcriptCount: Int = 0,
)
