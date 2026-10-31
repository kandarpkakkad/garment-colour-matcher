package com.garmentcolour.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    @Transaction
    @Query("SELECT * FROM captures ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CaptureWithGarments>>

    @Transaction
    @Query("SELECT * FROM captures WHERE id = :id")
    fun observe(id: String): Flow<CaptureWithGarments?>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: String): CaptureEntity?

    @Query("SELECT id FROM captures")
    suspend fun allIds(): List<String>

    @Transaction
    @Query("SELECT * FROM captures ORDER BY created_at ASC")
    suspend fun everything(): List<CaptureFull>

    @Insert
    suspend fun insertCapture(capture: CaptureEntity)

    @Update
    suspend fun updateCapture(capture: CaptureEntity)

    @Insert
    suspend fun insertGarments(garments: List<GarmentEntity>)

    @Query("DELETE FROM garments WHERE capture_id = :id")
    suspend fun deleteGarments(id: String)

    @Insert
    suspend fun insertAdjustment(adjustment: AdjustmentEntity)

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun deleteCapture(id: String)
}

/** Version 1 is the first; there is nothing to migrate from (storage HLD). */
@Database(
    entities = [CaptureEntity::class, GarmentEntity::class, AdjustmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "garment_colour.db").build()
    }
}
