package com.blackcore.callstar.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 별점 값 정의: 중요(보관/백업) 3점, 정리후보 1점. (0점은 삭제 버튼으로 대체하여 폐지) */
object Rating {
    const val KEEP = 3    // 중요 — 보관, 프리미엄이면 지정 폴더로 백업
    const val LATER = 1   // 정리후보 — 나중에 목록에서 배치 삭제
}

/**
 * [단계 3] 통화녹음 별점 저장.
 *
 * 핵심: mediastoreId 를 기본키로 두고 REPLACE 로 upsert → "한 파일 = 한 별점 행".
 * 중복/크래시 방지용 안전장치일 뿐, 재별점 UI 는 없음.
 * displayName 도 함께 저장(목록 표시 + 단계4 재매칭 보조).
 */
@Entity(tableName = "ratings")
data class RatingEntity(
    @PrimaryKey val mediastoreId: Long,
    val rating: Int,          // 5 / 4 / 3 / 0
    val displayName: String,
    val ratedAt: Long,        // epoch millis
)

@Dao
interface RatingDao {

    /** 같은 mediastoreId 있으면 덮어씀 (upsert) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rating: RatingEntity)

    @Query("SELECT * FROM ratings ORDER BY ratedAt DESC")
    suspend fun getAllOnce(): List<RatingEntity>

    @Query("SELECT * FROM ratings ORDER BY ratedAt DESC")
    fun observeAll(): Flow<List<RatingEntity>>

    @Query("SELECT rating FROM ratings WHERE mediastoreId = :id")
    suspend fun ratingFor(id: Long): Int?

    @Query("SELECT COUNT(*) FROM ratings")
    suspend fun count(): Int

    /** 단계4: 사라진 파일 정리용 */
    @Query("DELETE FROM ratings WHERE mediastoreId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Database(entities = [RatingEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ratingDao(): RatingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "callstar.db",
                ).build().also { instance = it }
            }
    }
}
