package com.gongfpp.sonfolio.processing

import kotlinx.coroutines.sync.Mutex

/** Serializes consumers and explicit cleanup, never the AudioRecord capture loop. */
internal object AudioFileAccess {
    val mutex = Mutex()
}
