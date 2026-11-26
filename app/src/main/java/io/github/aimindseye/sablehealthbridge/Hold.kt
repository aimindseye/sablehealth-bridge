package io.github.aimindseye.sablehealthbridge

internal class Hold(
    val label: String,
    val retryable: Boolean = false,
) : Exception(label)
