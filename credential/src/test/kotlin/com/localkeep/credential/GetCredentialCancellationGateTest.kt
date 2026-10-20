package com.localkeep.credential

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Regression test for F2: onBeginGetCredentialRequest sampled CancellationSignal only before
 * starting the async get-credential response build, never after it finished. A cancellation
 * racing CredentialEntryFactory.response delivered a result to a request the caller had already
 * given up on — the exact stale-delivery case LocalAutofillService.onFillRequest already guards
 * against with its own post-build check before onSuccess. [shouldDeliverAfterBuild] is the gate
 * LocalCredentialProviderService now samples right before callback.onResult.
 */
class GetCredentialCancellationGateTest {

    @Test
    fun `a response finished after cancellation is not delivered`() {
        assertFalse(shouldDeliverAfterBuild(isCanceledAfterBuild = true))
    }

    @Test
    fun `a response finished while still active is delivered`() {
        assertTrue(shouldDeliverAfterBuild(isCanceledAfterBuild = false))
    }
}
