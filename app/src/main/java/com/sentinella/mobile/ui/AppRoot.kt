package com.sentinella.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sentinella.mobile.MainActivity

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: Long) : Screen
    data class Edit(val id: Long?) : Screen
    data object Options : Screen
}

@Composable
fun AppRoot() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val open by MainActivity.openRequest.collectAsState()

    LaunchedEffect(open) {
        open?.let {
            stack = listOf(Screen.Home, Screen.Detail(it))
            MainActivity.openRequest.value = null
        }
    }

    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    val s = stack.last()
    key(s) {
    when (s) {
        Screen.Home -> HomeScreen(
            onOpen = { push(Screen.Detail(it)) },
            onAdd = { push(Screen.Edit(null)) },
            onSettings = { push(Screen.Options) },
        )

        is Screen.Detail -> DetailScreen(
            id = s.id,
            onBack = pop,
            onEdit = { push(Screen.Edit(s.id)) },
            onDeleted = { stack = listOf(Screen.Home) },
        )

        is Screen.Edit -> EditScreen(
            id = s.id,
            onBack = pop,
            onSaved = { newId ->
                stack = if (s.id == null) stack.dropLast(1) + Screen.Detail(newId) else stack.dropLast(1)
            },
        )

        Screen.Options -> SettingsScreen(onBack = pop)
    }
    }
}
