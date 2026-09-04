package com.mostafazahra.chesswake.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mostafazahra.chesswake.alarm.data.AlarmDao
import com.mostafazahra.chesswake.alarm.data.AlarmEntity
import com.mostafazahra.chesswake.puzzle.data.AttemptDao
import com.mostafazahra.chesswake.puzzle.data.AttemptEntity
import com.mostafazahra.chesswake.puzzle.data.PuzzleDao
import com.mostafazahra.chesswake.puzzle.data.PuzzleEntity

/**
 * The single local database behind ChessWake.
 *
 * Everything the app knows — alarms, the bundled puzzle set, and every attempt
 * you have ever made — lives here and nowhere else. There is no network layer,
 * so there is also no migration path that could leak data off the device.
 *
 * Schema versions are exported to `app/schemas/` (see the `room.schemaLocation`
 * KSP arg) so that every migration can be reviewed and tested.
 */
@Database(
    entities = [
        AlarmEntity::class,
        PuzzleEntity::class,
        AttemptEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ChessWakeDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao
    abstract fun puzzleDao(): PuzzleDao
    abstract fun attemptDao(): AttemptDao

    companion object {
        const val NAME = "chesswake.db"
    }
}
