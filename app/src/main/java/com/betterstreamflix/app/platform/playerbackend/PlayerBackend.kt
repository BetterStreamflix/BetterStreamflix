package com.betterstreamflix.app.platform.playerbackend

enum class PlayerBackendKind {
    EXO,
    EXTERNAL_MPV,
}

interface PlayerBackend {
    val kind: PlayerBackendKind
    val displayName: String
}
