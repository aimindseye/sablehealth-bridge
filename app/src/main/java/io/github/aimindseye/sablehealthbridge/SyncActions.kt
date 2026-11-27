package io.github.aimindseye.sablehealthbridge

internal data class SyncActions(
    val enable: () -> Unit,
    val disable: () -> Unit,
    val now: () -> Unit,
    val interval: (Int) -> Unit,
    val manual: () -> Unit,
    val about: () -> Unit,
)
