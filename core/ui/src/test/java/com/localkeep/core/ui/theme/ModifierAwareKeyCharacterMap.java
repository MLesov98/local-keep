package com.localkeep.core.ui.theme;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyCharacterMap;

/**
 * Robolectric's key map ignores Ctrl, Alt and Meta, so under it Ctrl+C reports the character
 * {@code c} and Compose's text field <em>types</em> it over the selection instead of treating it as
 * a shortcut. A Ctrl+C test then fails its plain-field control, and passes on a guarded field for a
 * reason that has nothing to do with the guard.
 *
 * <p>The platform's native {@code KeyCharacterMap::getCharacter} matches a behaviour only when it
 * names exactly the Ctrl/Alt/Meta state the event carries, and letter keys in {@code Generic.kcm}
 * name none, so on a device that lookup returns 0 and the chord reaches the shortcut table. This
 * restores that one rule and leaves every other lookup to the stock shadow.
 *
 * <p>Java rather than Kotlin because it hides a static of its superclass: a Kotlin
 * {@code @JvmStatic} with the same JVM signature is rejected as an accidental override.
 */
@Implements(KeyCharacterMap.class)
public class ModifierAwareKeyCharacterMap extends ShadowKeyCharacterMap {

    private static final int CHORD =
            KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK;

    @Implementation
    protected static char nativeGetCharacter(long ptr, int keyCode, int metaState) {
        if ((metaState & CHORD) != 0) return 0;
        return ShadowKeyCharacterMap.nativeGetCharacter(ptr, keyCode, metaState);
    }
}
