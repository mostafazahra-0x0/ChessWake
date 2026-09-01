package com.mostafazahra.chesswake.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mostafazahra.chesswake.puzzle.PuzzleScreen
import com.mostafazahra.chesswake.puzzle.Puzzles
import com.mostafazahra.chesswake.ui.theme.ChessWakeTheme

/**
 * Full-screen activity shown on top of the lock screen when the alarm fires.
 * The alarm sound keeps looping until the puzzle is solved correctly, which
 * stops the service and dismisses this screen (Step 4).
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChessWakeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PuzzleScreen(
                            puzzle = Puzzles.perfectMateInOne,
                            onSolved = {
                                AlarmSoundService.stop(this@AlarmActivity)
                                finish()
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}
