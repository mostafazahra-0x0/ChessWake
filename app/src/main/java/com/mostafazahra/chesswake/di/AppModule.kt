package com.mostafazahra.chesswake.di

import android.content.Context
import androidx.room.Room
import com.mostafazahra.chesswake.alarm.data.AlarmDao
import com.mostafazahra.chesswake.data.local.ChessWakeDatabase
import com.mostafazahra.chesswake.puzzle.data.AttemptDao
import com.mostafazahra.chesswake.puzzle.data.PuzzleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

/**
 * Application-wide bindings: the database, its DAOs, the dispatchers, and the clock.
 *
 * A single Hilt module is enough for an app this size; splitting it further would
 * add indirection without adding safety.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChessWakeDatabase =
        Room.databaseBuilder(context, ChessWakeDatabase::class.java, ChessWakeDatabase.NAME)
            // The puzzle table is regenerated from BundledPuzzles whenever its
            // revision changes, and alarms are user data that must never be
            // destroyed silently — so a destructive migration is the wrong default.
            // Falling back to creating from scratch only happens when no migration
            // path exists, which for version 1 is never.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideAlarmDao(database: ChessWakeDatabase): AlarmDao = database.alarmDao()

    @Provides
    fun providePuzzleDao(database: ChessWakeDatabase): PuzzleDao = database.puzzleDao()

    @Provides
    fun provideAttemptDao(database: ChessWakeDatabase): AttemptDao = database.attemptDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * Scope for work that must survive a screen closing: seeding the puzzle set,
     * recording an attempt after the alarm screen has already finished.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The clock, injected so that alarm scheduling maths can be tested against a
     * fixed instant instead of the wall clock.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
