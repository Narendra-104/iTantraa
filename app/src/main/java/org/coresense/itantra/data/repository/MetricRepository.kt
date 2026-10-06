package org.coresense.itantra.data.repository

import kotlinx.coroutines.flow.Flow
import org.coresense.itantra.storage.MetricSampleDao
import org.coresense.itantra.storage.MetricSampleEntity

class MetricRepository(private val metricDao: MetricSampleDao) {
    suspend fun getAllSamples(): List<MetricSampleEntity> {
        return metricDao.getAllSamples()
    }

    suspend fun insertSample(sample: MetricSampleEntity) {
        metricDao.insertSample(sample)
    }

    suspend fun clearAll() {
        metricDao.clearAll()
    }
}
