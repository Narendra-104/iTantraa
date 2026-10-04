package org.coresense.itantra.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "metric_samples")
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val languageCode: String,
    val rtf: Float,
    val wer: Float,
    val ramPssMb: Float,
    val cpuPercent: Float,
    val batteryLevelPercent: Int,
    val payloadBytes: Int,
    val pcmBytes: Int,
    val compressionRatio: Float,
    val latencyMs: Long
)

@Dao
interface MetricSampleDao {
    @Query("SELECT * FROM metric_samples ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSamples(limit: Int = 50): Flow<List<MetricSampleEntity>>

    @Query("SELECT * FROM metric_samples ORDER BY timestamp DESC")
    suspend fun getAllSamples(): List<MetricSampleEntity>

    @Insert
    suspend fun insertSample(sample: MetricSampleEntity)

    @Query("DELETE FROM metric_samples")
    suspend fun clearAll()
}
