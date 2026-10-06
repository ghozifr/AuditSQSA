package com.suruhaja

/** Keeps Play availability and minimum-version gates explicit and independently testable. */
internal fun shouldStartImmediatePlayUpdate(
    updateAvailable: Boolean,
    immediateAllowed: Boolean,
): Boolean = updateAvailable && immediateAllowed

/** A positive server minimum greater than the installed version must block the app. */
internal fun requiresHardPlayUpdate(
    installedVersionCode: Int,
    minimumVersionCode: Long,
): Boolean = minimumVersionCode > 0L && installedVersionCode.toLong() < minimumVersionCode
