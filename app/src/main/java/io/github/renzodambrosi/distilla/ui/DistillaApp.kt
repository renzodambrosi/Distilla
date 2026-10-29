package io.github.renzodambrosi.distilla.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DistillaApp(vm: MainViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle()
    val conflictTotal by vm.conflictTotal.collectAsStateWithLifecycle()
    val conflictDialog by vm.conflictDialog.collectAsStateWithLifecycle()

    when (screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.READER -> ReaderScreen(
            vm = vm,
            // Leaving the story doesn't stop listening: the queue goes on in the background
            onBack = { if (vm.openedFromShare) onExit() else vm.goHome() },
        )
        Screen.SETTINGS -> SettingsScreen(vm = vm, onBack = vm::closeSettings)
        Screen.QUEUE -> QueueScreen(vm = vm, onBack = vm::closeQueue)
    }

    draft?.let { RuleEditDialog(vm, it) }

    // A background sync must not interrupt reading: conflicts wait until the user leaves the reader.
    if (conflictDialog && conflicts.isNotEmpty() && screen != Screen.READER && draft == null) {
        ConflictDialog(
            conflict = conflicts.first(),
            position = conflictTotal - conflicts.size + 1,
            total = conflictTotal,
            onChoice = vm::resolveConflict,
        )
    }
}
