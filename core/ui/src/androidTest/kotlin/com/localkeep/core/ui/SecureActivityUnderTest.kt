package com.localkeep.core.ui

/**
 * A concrete [SecureActivity] with no content, declared in this module's androidTest manifest.
 *
 * `SecureActivity` is abstract, and the flag it sets is a property of a real window — asserting on
 * the resource alone would pass even if the `if` were inverted or deleted. Launching this gives the
 * test an actual window to read `WindowManager.LayoutParams.FLAG_SECURE` off.
 */
class SecureActivityUnderTest : SecureActivity()
