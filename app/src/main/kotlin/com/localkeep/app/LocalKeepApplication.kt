package com.localkeep.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.localkeep.core.common.PendingReveal
import com.localkeep.core.common.PendingSecret
import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.backup.RollingBackupManager
import com.localkeep.feature.backup.VaultExporter
import com.localkeep.feature.files.BlobGarbageCollector
import com.localkeep.feature.files.FileBlobStore
import com.localkeep.feature.settings.SettingsPreferences
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class LocalKeepApplication : Application() {
    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var rollingBackupManager: RollingBackupManager

    @Inject
    lateinit var blobGarbageCollector: BlobGarbageCollector

    @Inject
    lateinit var fileBlobStore: FileBlobStore

    @Inject
    lateinit var vaultExporter: VaultExporter

    @Inject
    lateinit var vaultRepository: VaultRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Off the main thread, because nothing here needs it and everything here waits for it. The
        // EFF long list is 7,776 lines that have to be read out of the APK, split, and then checked
        // by `setWordlist` — a Set build plus a regex over every word — and all of that ran inline
        // in `onCreate`, on every process start. Not just the ones the user sees: the platform also
        // starts this process to service an autofill request or a credential-provider query, and
        // neither of those will ever generate a passphrase.
        //
        // Ordering is safe rather than lucky. Only two places read the list — the generator screen
        // and onboarding's Suggest link — and both sit behind either an unlock or several
        // onboarding taps, seconds after this launch of a job measured in tens of milliseconds.
        // Both already treat a missing list as a retryable message rather than a crash, so even the
        // race that cannot realistically happen degrades to "tap it again", not to a broken screen.
        // Deliberately not wrapped in runCatching: a bundled asset that fails to load is a build
        // defect, and it should still be as loud as it was when this ran on the main thread.
        appScope.launch(Dispatchers.IO) {
            PasswordGenerator.setWordlist(
                assets.open("eff-large-wordlist.txt").bufferedReader().use { it.readLines() },
            )
        }
        lockManager.applyAutoLockPreset(SettingsPreferences.autoLockPreset(this))
        // Before anything can rotate the DEK. File blobs are encrypted under a key derived from it,
        // and `:core:vault` cannot reach the module that owns them, so this registration is the
        // whole seam — without it a master-password change refuses to run rather than quietly
        // orphaning every stored file. See BlobReKeyStore.
        lockManager.registerBlobReKeyStore(fileBlobStore)
        // The other registration seam on this line: an item trashed from its detail screen has to
        // be restorable after that screen — and its ViewModel — is gone. See TrashUndoBus (4A-F7).
        TrashUndoBus.register(vaultRepository)

        // App-wide foreground/background signal (§10.2). ProcessLifecycleOwner rather than an
        // Activity callback: LocalKeep has several activities (autofill save/auth, credential
        // provider), and switching between them must not read as "the user left the app".
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    lockManager.onEnterForeground()
                    // A copy whose clear-timer died with the previous process — swiping the app out
                    // of recents a few seconds after copying a password does exactly that. Not a
                    // scheduled job because the platform refuses clipboard access to anything that
                    // is not the focused app.
                    //
                    // **This call is no longer the one that does the work** (since
                    // 2026-09-13). It runs in `Activity.onStart`, before
                    // `onResume` and therefore before any window holds focus, so the read it makes
                    // is denied — measured on emulator-5554 as two `Denying clipboard access` lines
                    // at this exact instant. It is kept because it is now harmless (with no window
                    // focused the sweep keeps the recovery record rather than discarding it) and
                    // still useful on API 28, where there is no focus gate. The sweep that actually
                    // clears is SecureActivity.onWindowFocusChanged.
                    ClipboardHelper.clearStaleClip(this@LocalKeepApplication)
                }

                override fun onStop(owner: LifecycleOwner) = lockManager.onEnterBackground()
            },
        )

        // Screen-off is its own lock trigger (functional spec §4.2 lists it separately from "app
        // background"), and it was the one of the three that nothing implemented. The gap is real
        // but narrow: `onStop` covers the ordinary case of the screen going dark, yet it does not
        // fire when something draws over the app without backgrounding it — an incoming call's
        // screen, an emergency-dialer overlay, a system dialog — and in those cases the vault
        // stayed open on a device the user has just put in their pocket, until the idle timer
        // eventually noticed. Which timer that is depends on the preset: five minutes on Relaxed.
        //
        // Locking here is immediate and unconditional. There is nothing to lose by it — unlocking
        // is what the user was going to do on waking the phone anyway — and it makes the screen
        // being off mean the same thing as the app being closed.
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) lockManager.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )

        rollingBackupManager.start()

        // Finish cleanup of any replacement that died between staging and publishing. This must
        // run before the reachability sweep: a PREPARED journal's new refs are intentionally not
        // in the vault yet, while its old refs remain the readable recovery copy.
        // Bounded work: a finished transaction now deletes its journal, so in steady state this
        // reads an empty directory. It stays on the main thread deliberately — the ordering above
        // is the reason it exists, and a coroutine would race the sweep it must precede.
        runCatching { fileBlobStore.recoverJournals() }

        // Same shape, different directory: an export killed mid-copy leaves a complete vault
        // container in `noBackupFilesDir` that nothing else ever deletes.
        runCatching { vaultExporter.sweepStaleExportStages() }

        // Reclaim encrypted blobs orphaned by item delete / trash purge (VaultRepository can't reach
        // FileBlobStore) on each unlock — the vault is loaded then, so the referenced set is known.
        // Safe/idempotent: deletes only blobs no live-or-trashed item points at.
        appScope.launch {
            lockManager.lockState
                .map { it is LockState.Unlocked }
                .distinctUntilChanged()
                .collect { unlocked ->
                    if (unlocked) {
                        // Finish a blob re-key that a process death interrupted mid-rotation. The
                        // session DEK is the only key that opens the journal, so this is the one
                        // moment it can run. Before the sweep, which reasons about refs and would
                        // otherwise race a rename it has no interest in.
                        runCatching { lockManager.resumeBlobReKey() }
                        // The same shape, over the container files rather than the blobs: a walk
                        // that `removeDuress` runs after its live write, one stale container at a
                        // time, which a process death can leave half-done with nothing to retry it
                        // (F38 follow-up). Gated on a journal, so with nothing pending it is one
                        // `isFile` and a return — no container read, no AEAD open — which is what
                        // makes it safe to run at every unlock for ever.
                        runCatching { lockManager.resumeContainerMaintenance() }
                        runCatching { blobGarbageCollector.sweep() }
                    } else {
                        // A password the generator offered but no editor collected — the user tapped
                        // "Save as a new login" and then locked instead of saving. It is consumed on
                        // arrival in the ordinary path, so this only ever covers the abandoned one.
                        PendingSecret.clear()
                        // And a highlight nobody is waiting for any more. Not a secret — just an
                        // id — but a flash of emphasis on an unrelated row after the next unlock is
                        // noise, and the reason to reveal it went away with the session.
                        PendingReveal.clear()
                    }
                }
        }
    }
}
