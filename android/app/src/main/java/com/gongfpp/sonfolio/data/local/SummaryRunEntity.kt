package com.gongfpp.sonfolio.data.local

import androidx.room.ColumnInfo
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
    /** 断点续跑：已完成的片段数与累计小结 JSON；完成后清零。仅长对话中途失败时非零。 */
    @ColumnInfo(defaultValue = "0") val progressIndex: Int = 0,
    val progressJson: String? = null,
)
