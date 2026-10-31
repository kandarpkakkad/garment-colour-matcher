package com.garmentcolour.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// docs/01-platform/storage/schema.md. Enums are stored by name. Paths are not
// stored: they are derived from the id (storage HLD).

@Entity(tableName = "captures")
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val source: String,
    @ColumnInfo(name = "capture_path") val capturePath: String,
    /** RAW's exposure gain (raw LLD §6); 1 for JPEG. */
    @ColumnInfo(name = "exposure_gain") val exposureGain: Double,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "source_megapixels") val sourceMegapixels: Double,
    val downscaled: Boolean,
    val tier: String,
    @ColumnInfo(name = "est_r") val estR: Double?,
    @ColumnInfo(name = "est_g") val estG: Double?,
    @ColumnInfo(name = "est_b") val estB: Double?,
    @ColumnInfo(name = "est_answers") val estAnswers: String,
    @ColumnInfo(name = "est_disagreement") val estDisagreement: Double?,
    @ColumnInfo(name = "est_support") val estSupport: Double,
    @ColumnInfo(name = "est_score") val estScore: Double,
    @ColumnInfo(name = "est_level") val estLevel: String,
    @ColumnInfo(name = "est_reason") val estReason: String?,
    @ColumnInfo(name = "applied_r") val appliedR: Double,
    @ColumnInfo(name = "applied_g") val appliedG: Double,
    @ColumnInfo(name = "applied_b") val appliedB: Double,
    @ColumnInfo(name = "applied_method") val appliedMethod: String,
    @ColumnInfo(name = "applied_x") val appliedX: Double,
    @ColumnInfo(name = "applied_y") val appliedY: Double,
    @ColumnInfo(name = "engine_version") val engineVersion: Int,
)

@Entity(
    tableName = "garments",
    foreignKeys = [ForeignKey(CaptureEntity::class, ["id"], ["capture_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("capture_id")],
)
data class GarmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val rank: Int,
    val label: String,
    val fraction: Double,
    @ColumnInfo(name = "box_l") val boxL: Double,
    @ColumnInfo(name = "box_t") val boxT: Double,
    @ColumnInfo(name = "box_r") val boxR: Double,
    @ColumnInfo(name = "box_b") val boxB: Double,
    @ColumnInfo(name = "lin_r") val linR: Double,
    @ColumnInfo(name = "lin_g") val linG: Double,
    @ColumnInfo(name = "lin_b") val linB: Double,
    @ColumnInfo(name = "xyz_x") val xyzX: Double,
    @ColumnInfo(name = "xyz_y") val xyzY: Double,
    @ColumnInfo(name = "xyz_z") val xyzZ: Double,
    @ColumnInfo(name = "lab_l") val labL: Double,
    @ColumnInfo(name = "lab_a") val labA: Double,
    @ColumnInfo(name = "lab_b") val labB: Double,
    val hex: String,
    val clipped: Boolean,
)

@Entity(
    tableName = "adjustments",
    foreignKeys = [ForeignKey(CaptureEntity::class, ["id"], ["capture_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("capture_id")],
)
data class AdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: String,
    @ColumnInfo(name = "tap_u") val tapU: Double?,
    @ColumnInfo(name = "tap_v") val tapV: Double?,
    @ColumnInfo(name = "box_l") val boxL: Double?,
    @ColumnInfo(name = "box_t") val boxT: Double?,
    @ColumnInfo(name = "box_r") val boxR: Double?,
    @ColumnInfo(name = "box_b") val boxB: Double?,
    @ColumnInfo(name = "before_r") val beforeR: Double,
    @ColumnInfo(name = "before_g") val beforeG: Double,
    @ColumnInfo(name = "before_b") val beforeB: Double,
    @ColumnInfo(name = "after_r") val afterR: Double,
    @ColumnInfo(name = "after_g") val afterG: Double,
    @ColumnInfo(name = "after_b") val afterB: Double,
    @ColumnInfo(name = "angle_deg") val angleDeg: Double,
)

data class CaptureWithGarments(
    @Embedded val capture: CaptureEntity,
    @Relation(parentColumn = "id", entityColumn = "capture_id") val garmentRows: List<GarmentEntity>,
) {
    val garments: List<GarmentEntity> get() = garmentRows.sortedBy { it.rank }
}

data class CaptureFull(
    @Embedded val capture: CaptureEntity,
    @Relation(parentColumn = "id", entityColumn = "capture_id") val garments: List<GarmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "capture_id") val adjustments: List<AdjustmentEntity>,
)
