package com.localkeep.core.common

/**
 * Single Pro/free authority.
 *
 * Phase 14 binds Play Billing for `play`; `foss` remains permanently free.
 */
interface Tier {
    val isPro: Boolean
}
