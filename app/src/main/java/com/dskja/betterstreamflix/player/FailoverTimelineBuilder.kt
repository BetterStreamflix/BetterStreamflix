package com.dskja.betterstreamflix.player

/**
 * Pure failover storytelling — builds mini-timeline steps from session attempts.
 * JVM-safe for unit tests (no Android Context).
 */
object FailoverTimelineBuilder {
    const val MAX_VISIBLE_STEPS = 4

    fun kindFromError(error: Throwable?): FailoverStepKind {
        return when (StreamFailureReason.classify(error).kind) {
            StreamFailureReason.Kind.TIMEOUT -> FailoverStepKind.TIMEOUT
            StreamFailureReason.Kind.CAPTCHA -> FailoverStepKind.CAPTCHA
            StreamFailureReason.Kind.GEO -> FailoverStepKind.GEO
            StreamFailureReason.Kind.DOMAIN_DEAD -> FailoverStepKind.DOMAIN
            StreamFailureReason.Kind.ALL_SERVERS,
            StreamFailureReason.Kind.GENERIC,
            -> FailoverStepKind.GENERIC
        }
    }

    /** Show after play succeeds only when at least one prior failure was recorded. */
    fun shouldShowAfterSuccess(attempts: List<FailoverAttempt>): Boolean {
        if (attempts.isEmpty()) return false
        val hasFailure = attempts.any { it.kind != FailoverStepKind.OK }
        val hasOk = attempts.any { it.kind == FailoverStepKind.OK }
        return hasFailure && hasOk
    }

    /** Show on final give-up when the user saw more than one attempt. */
    fun shouldShowOnFailure(attempts: List<FailoverAttempt>): Boolean =
        attempts.count { it.kind != FailoverStepKind.OK } >= 2 ||
            (attempts.size >= 2 && attempts.none { it.kind == FailoverStepKind.OK })

    /**
     * Collapse consecutive identical server+kind rows, then cap to [MAX_VISIBLE_STEPS]
     * (keep first + last, insert ellipsis when truncated).
     */
    fun buildSteps(attempts: List<FailoverAttempt>): List<FailoverTimelineStep> {
        if (attempts.isEmpty()) return emptyList()
        val collapsed = ArrayList<FailoverAttempt>(attempts.size)
        for (attempt in attempts) {
            val last = collapsed.lastOrNull()
            if (last != null &&
                last.serverOrdinal == attempt.serverOrdinal &&
                last.kind == attempt.kind
            ) {
                continue
            }
            collapsed += attempt
        }
        if (collapsed.size <= MAX_VISIBLE_STEPS) {
            return collapsed.map { FailoverTimelineStep(it.serverOrdinal, it.kind) }
        }
        val head = collapsed.take(MAX_VISIBLE_STEPS - 2)
        val tail = collapsed.last()
        return buildList {
            head.forEach { add(FailoverTimelineStep(it.serverOrdinal, it.kind)) }
            add(FailoverTimelineStep(0, FailoverStepKind.GENERIC, ellipsis = true))
            add(FailoverTimelineStep(tail.serverOrdinal, tail.kind))
        }
    }

    /** Test-friendly wire labels: `S1:TIMEOUT`, `SW`, `OK`, `…`. */
    fun wireLabels(steps: List<FailoverTimelineStep>): List<String> =
        steps.map { step ->
            when {
                step.ellipsis -> "…"
                step.kind == FailoverStepKind.SOFTWARE_DECODER -> "SW"
                step.kind == FailoverStepKind.EXTERNAL_PLAYER -> "EXT"
                step.kind == FailoverStepKind.OK && step.serverOrdinal > 0 ->
                    "S${step.serverOrdinal}:OK"
                step.serverOrdinal > 0 -> "S${step.serverOrdinal}:${step.kind.name}"
                else -> step.kind.name
            }
        }

    fun joinWire(steps: List<FailoverTimelineStep>, separator: String = " → "): String =
        wireLabels(steps).joinToString(separator)
}
