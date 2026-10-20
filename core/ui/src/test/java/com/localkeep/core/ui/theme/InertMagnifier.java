package com.localkeep.core.ui.theme;

import android.widget.Magnifier;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * A long-press on a text field raises the platform {@link Magnifier}, and under Robolectric its
 * popup window never gets a {@code Surface}: the first {@code dismiss()} throws a
 * NullPointerException from {@code InternalPopupWindow.destroy}, inside composition, before the
 * selection menu is ever asked for. The magnifier has nothing to do with what is being tested, so
 * it is made inert rather than worked around.
 */
@Implements(Magnifier.class)
public class InertMagnifier {

    @Implementation
    protected void show(float sourceCenterX, float sourceCenterY, float magnifierCenterX, float magnifierCenterY) {}

    @Implementation
    protected void dismiss() {}

    @Implementation
    protected void update() {}
}
