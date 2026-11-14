package com.baystudio.droide.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 
data class PendingQuestion(
    val id: Long,
    val header: String,
    val question: String,
    val options: List<String>,
    val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
)

class QuestionManager {
    private var nextId = 1L
    private val _pending = MutableStateFlow<PendingQuestion?>(null)
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()
    private val conts = mutableMapOf<Long, CancellableContinuation<String>>()
    private val askMutex = Mutex()
    private val stateLock = Any()

     
    suspend fun ask(
        header: String,
        question: String,
        options: List<String>,
        provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
    ): String = askMutex.withLock {
        val q = PendingQuestion(
            id = nextId++,
            header = provenance.decorateHeader(header),
            question = question.take(500),
            options = options.take(6),
            provenance = provenance,
        )
        kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            cont.invokeOnCancellation {
                synchronized(stateLock) {
                    conts.remove(q.id)
                    if (_pending.value?.id == q.id) _pending.value = null
                }
            }
            synchronized(stateLock) {
                if (cont.isActive) {
                    // A visible question must already own its answer continuation.
                    conts[q.id] = cont
                    _pending.value = q
                }
            }
        }
    }

    /** Stale, cancelled, and already answered question callbacks have no effect. */
    @OptIn(InternalCoroutinesApi::class)
    fun answer(questionId: Long, text: String): Boolean {
        val resumed = synchronized(stateLock) {
            if (_pending.value?.id != questionId) return false
            val cont = conts[questionId] ?: return false
            val token = cont.tryResume(text) ?: return false
            conts.remove(questionId)
            _pending.value = null
            cont to token
        }
        resumed.first.completeResume(resumed.second)
        return true
    }
}
