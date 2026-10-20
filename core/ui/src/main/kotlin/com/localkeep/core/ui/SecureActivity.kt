package com.localkeep.core.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

/** Secure default for every LocalKeep activity window. */
abstract class SecureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECURE blocks screenshots, screen recording and the app-switcher preview. It is on
        // in every shipped build; the bool is `true` in core/ui/src/main/res/values/bools.xml and
        // is overridden to `false` in exactly one directory, app/src/debug/res/values/bools.xml,
        // which release variants do not compile.
        //
        // The indirection buys fail-closed behaviour: a typo'd resource name, a deleted override or
        // a mis-merged source set all resolve to the library default, which is secure. A
        // `BuildConfig.DEBUG` branch fails whichever way a sign error points — and core/ui does not
        // enable the buildConfig feature anyway. `checkScreenSecurity` reads the merged release
        // resources and fails the build if this is ever anything but `true` in a release variant.
        // (Debug builds capture normally since 2026-08-10, so screenshots work for QA.)
        if (resources.getBoolean(R.bool.lk_obscure_screen)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        super.onCreate(savedInstanceState)
        // Never gated. This is a tap-jacking defence — it rejects touches delivered while another
        // window overlays ours — and has nothing to do with screenshots. It stays on in every build.
        window.decorView.post {
            findViewById<View>(android.R.id.content)?.let { content ->
                content.filterTouchesWhenObscured = true
                // Never gated either. This narrows what the platform autofill framework can
                // learn about this window; read the limit below before trusting it.
                //
                // FLAG_SECURE above does not cover this. It suppresses screenshots, the
                // app-switcher preview and content capture -- but an autofill *save* session is not
                // gated by it, so on the shipped release build the OS was raising "Save password to
                // LocalKeep?" over the vault list after every single unlock, having tracked the
                // master-password field as the user typed it. On the debug build, which does not
                // set FLAG_SECURE, `dumpsys autofill` additionally showed the augmented autofill
                // service holding a session over the master-password and confirm wells during
                // onboarding. Found on a device by the pre-iOS sweep 2026-09-11.
                //
                // WHAT THIS FLAG ACTUALLY BUYS, and what it does not. The platform honours it when
                // it walks the view tree to build the AssistStructure, so the bound service does
                // not receive this window's fields or their contents. It does NOT stop a session
                // from opening. Compose reports its text fields to `AutofillManager` itself, as
                // *virtual* children of the single AndroidComposeView, and
                // `AndroidAutofillManager.onFocusChanged` gates that call on exactly one thing --
                // `isAutofillable(semanticsConfiguration)`, i.e. whether the node carries
                // `SemanticsActions.OnAutofillText` or `OnFillData`. It never consults the view's
                // `importantForAutofill`, and `TextFieldDecoratorModifierNode.applySemantics` sets
                // those actions unconditionally on every BasicTextField. So focusing a secret well
                // still opens a session with whatever service is bound, disclosing that a fillable
                // field was focused in LocalKeep and its bounds -- measured against Google's
                // provider on 2026-09-12: 5/5 on API 29 (`minSdk` is 28), ~1/5 on API 35.
                //
                // This is not fixable in Compose 1.11.3. `Modifier.contentType` is opt-in only,
                // there is no opt-out property, returning null for the AutofillManager system
                // service makes AndroidComposeView throw ("Autofill service could not be
                // located."), and the one construct that would remove the semantics actions --
                // `clearAndSetSemantics` -- would strip TalkBack from every secret field in the
                // app, which is a strictly worse trade. Tracked as an accepted residual in
                // docs/THREAT-MODEL.md; revisit when Compose ships an opt-out.
                //
                // Keep the flag: blocking the structure walk is the half that protects the
                // contents, and it is the half that matters.
                content.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }
    }

    /**
     * The clipboard's only honest "may I read?" signal, and the moment the stale-clip sweep can
     * finally do its job. Added 2026-09-13.
     *
     * `ClipboardManager` refuses a read to anything that is not the *focused* app and says so —
     * "application is not in focus". The sweep used to run from `ProcessLifecycleOwner.onStart`
     * only, which is `Activity.onStart`: before `onResume`, and therefore before any window can
     * hold focus. Measured on emulator-5554 (API 35) that day: returning to the app produced two
     * `Denying clipboard access` lines at the instant of `onStart` and the recovery record went to
     * `<map />` within 1.5 s, while a paste still yielded the copied password. Full measurement in
     * [ClipboardHelper.windowFocused]; this is the other half of the fix.
     *
     * Every activity in the app extends this class (MainActivity, AuthActivity, PickerActivity,
     * SaveConfirmActivity, ConfirmDomainActivity, CredentialActivity), so this is the one place the
     * signal has to be written.
     *
     * [ClipboardHelper.clearStaleClip] is idempotent and returns at its first line when nothing is
     * pending, so calling it on every focus gain costs a read of an already-warm `SharedPreferences`
     * map. It cannot clear anything that is not ours: the clear stays gated on the persisted clip id
     * still owning the clipboard.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ClipboardHelper.windowFocused = hasFocus
        if (hasFocus) ClipboardHelper.clearStaleClip(this)
    }
}
