package com.gongfpp.sonfolio.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 独立缓存，不级联删除录音、转写或基础小结；hash 防止旧 AI 结果覆盖新录音。 */
@Entity(tableName = "summary_runs")
data class SummaryRunEntity(
    @PrimaryKey val sourceKey: String,
    val sourceHash: String,
    val provider: String,
    val model: String,
    val outputJson: String?,
    val state: String,
    val message: String?,
    val updatedAtMillis: Long,
)
