package com.suruhaja

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayUpdatePolicyTest {
    @Test fun startsOnlyForAvailableImmediateUpdate() {
        assertTrue(shouldStartImmediatePlayUpdate(updateAvailable = true, immediateAllowed = true))
        assertFalse(shouldStartImmediatePlayUpdate(updateAvailable = false, immediateAllowed = true))
        assertFalse(shouldStartImmediatePlayUpdate(updateAvailable = true, immediateAllowed = false))
    }

    @Test fun blocksOnlyWhenInstalledVersionIsBelowPositiveMinimum() {
        assertTrue(requiresHardPlayUpdate(installedVersionCode = 8, minimumVersionCode = 9))
        assertFalse(requiresHardPlayUpdate(installedVersionCode = 9, minimumVersionCode = 9))
        assertFalse(requiresHardPlayUpdate(installedVersionCode = 10, minimumVersionCode = 9))
        assertFalse(requiresHardPlayUpdate(installedVersionCode = 8, minimumVersionCode = 0))
    }
}
