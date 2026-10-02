// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

/** UI attachment identity is distinct from native-session identity: a recreated Activity can reuse it. */
class ChatAttachmentGuard<T : Any> {
    private var generation = 0L
    private var current: T? = null
    @Synchronized fun attach(session: T): Long { current = session; return ++generation }
    @Synchronized fun isCurrent(session: T?, epoch: Long?): Boolean =
        session != null && current === session && epoch != null && epoch == generation
    @Synchronized fun invalidate() { current = null; generation++ }
}
