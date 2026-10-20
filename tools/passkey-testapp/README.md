# `tools/passkey-testapp` — a relying party for LocalKeep's passkey and browser-domain paths

A throwaway Android app (`com.localkeep.passkeytest`) that plays the *other* side of three flows
LocalKeep can only be tested against from outside its own process:

1. **Create passkey** — `CredentialManager.createCredential` with a `CreatePublicKeyCredentialRequest`
   for rpId `example.test`, which drives `LocalCredentialProviderService.onBeginCreateCredentialRequest`
   → `CredentialActivity.handleCreate` → the **native-binding confirmation** (`RpIdValidator`
   returns `NativeUnconfirmed` for any caller that has no web origin, which every native app is).
2. **Sign in with passkey** — `GetCredentialRequest(GetPublicKeyCredentialOption)` with a fresh
   challenge and no `allowCredentials`, so the provider lists whatever it discovers for `example.test`
   and `PasskeySigner.sign` has to satisfy `validateForAssertion`'s package/certificate binding.
3. **Open web login** — a `WebView` on an `https://example.test/` origin, which is what makes
   LocalKeep's autofill service see a *web domain* coming from a non-browser package and send the
   pair through `ConfirmDomainActivity`, the one-time browser-domain trust gate.

Every result is printed into an on-screen log rather than logcat, because the intended reader is an
agent driving the device with `uiautomator dump`.

## Why `example.test`

`credential/src/main/kotlin/com/localkeep/credential/RpIdValidator.kt`'s `registrableRpId` accepts a
canonical host only when it has an internal dot and a rightmost label that is not all-numeric —
`localhost` is rejected outright, and `127.0.0.1` passes only through an explicit special case that
exists for the loopback *origin* arm of `isSameSiteOrigin`. `example.test` satisfies the rule, and
`.test` is reserved by RFC 2606 so the name can never resolve to a real site. The WebView uses the
same host, so one domain covers both halves of this app.

## What it is not

It does not verify signatures. That would need a server-side public-key registry, which is a second
system to get wrong. The one property checked is the one a broken provider actually breaks: that the
`clientDataJSON` in the response echoes the challenge that was sent, with the right ceremony `type`.
`PASS` / `FAIL` lines in the log say which.

The WebView serves its page from `src/main/assets/login.html` through `shouldInterceptRequest`, and
answers every other path with 404 — nothing reaches a network. That is `WebViewAssetLoader`'s own
mechanism, hand-rolled here because `androidx.webkit` is not in this repo's version catalog; doing it
by hand also frees the host name, which the loader otherwise pins to `appassets.androidplatform.net`.

## Build and install

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :tools:passkey-testapp:assembleDebug
adb install -r tools/passkey-testapp/build/outputs/apk/debug/passkey-testapp-debug.apk
```

Wired exactly like `tools/autofill-testapp`: an included module in the root `settings.gradle.kts`,
so the plain `-p` form (`./gradlew -p tools/passkey-testapp assembleDebug`) is not how it builds.

**Export `ANDROID_SERIAL` before any `adb` command** — `connectedAndroidTest` and `adb` act on
every attached device, and your own phone must never receive this. Use an emulator:

```bash
export ANDROID_SERIAL=emulator-5554
```

## Device prerequisites

- **API 34 or newer.** `android.service.credentials.CredentialProviderService` does not exist below
  34, so LocalKeep cannot be offered as a provider at all. The app prints its API level on launch
  and warns when it is too low. (`minSdk` stays at the catalog's 28 to match the other test app;
  the warning is the guard.)
- **LocalKeep installed as a `foss` *debug* build** — a release build is `FLAG_SECURE`, so
  `screencap` comes back black.
- **A vault that has been created and unlocked at least once.**
- **LocalKeep enabled as the credential provider**: Settings → Passwords, passkeys & accounts →
  *Change* under the passwords/passkeys service → tick **LocalKeep** → *OK* on the system warning.
  There is no `adb` equivalent: unlike the autofill service below, the credential-provider list is
  not a single `settings put secure` value, so this one has to be tapped through by hand.
- **LocalKeep enabled as the autofill service** (needed only for flow C):
  `adb shell settings put secure autofill_service com.localkeep.foss/com.localkeep.autofill.LocalAutofillService`
- **For flow C only**, a Login item in the vault whose URI matches `example.test`
  (e.g. `https://example.test`). Without a match, `FillResponseFactory` returns early and the
  confirmation gate is never reached — the gate is deliberately downstream of "do we hold anything
  for this site".

## Tap sequence for the device agent

Launch: `adb shell am start -n com.localkeep.passkeytest/.MainActivity`

The four buttons are, in view order: **Create passkey**, **Sign in with passkey**,
**Open web login**, **Clear log**. Below them is a selectable monospace log: the single
`android.widget.TextView` inside the single `android.widget.ScrollView` on the screen. Read it with
`adb shell uiautomator dump` and parse the XML tree; its first line is always `rpId: example.test`.

### A — Create a passkey (exercises the native-binding gate)

1. Tap **Create passkey**. The log prints `-- create: challenge <b64url>`.
2. Android's own credential sheet appears. Tap the **LocalKeep** entry — its description reads
   *"Save a passkey for example.test"*.
3. If the vault is locked, LocalKeep asks to unlock. Answer the biometric prompt, or tap
   **Use password** and type the master password.
4. **The gate under test**: a dialog titled **"Allow this app?"** saying
   *"com.localkeep.passkeytest wants to create a passkey for "example.test". LocalKeep cannot verify
   this offline app-to-site binding."* Tap **Allow**. (Tapping **Cancel** is the negative case —
   the log must then read `FAIL create: … native app binding declined`.)
5. A second verification prompt follows (`credential_verify_password_subtitle`). Answer it.
6. Read the log. A pass looks like:
   ```
   credential id : <b64url>
   clientData type: webauthn.create
   origin        : android:apk-key-hash:<b64url>
   androidPackage: com.localkeep.passkeytest
   PASS type is webauthn.create
   PASS challenge echoed: <the same b64url printed at step 1>
   ```
7. In LocalKeep the new item is titled **"Passkey Test — example.test"** and carries two hidden
   custom fields, `credential.boundPackage` and `credential.boundCertSha256`. Those are what step B
   is checked against.

### B — Sign in (exercises assertion + the package/certificate binding)

Run **after** A, in the same install of both apps — re-installing the test app changes its signing
certificate and `validateForAssertion` will then correctly refuse.

1. Tap **Sign in with passkey**. The log prints `-- sign in: challenge <b64url>` (a *different*
   challenge; a reused one would prove nothing).
2. The credential sheet lists one passkey, labelled **`passkey@example.test`** with display name
   **Passkey Test**. Tap it.
3. Unlock / answer the verification prompt.
4. A pass reads `PASS type is webauthn.get` and `PASS challenge echoed: …` matching step 1,
   followed by `authenticatorData`, `signature` and `userHandle` prefixes.

Negative case worth running once: conceal the passkey item in LocalKeep, then repeat. The sheet must
offer **no** passkey at all — `CredentialEntryFactory` reads through the visibility projection.

### C — Web login (exercises `ConfirmDomainActivity`)

1. Tap **Open web login**. A WebView loads `https://example.test/login.html`: heading *Web login*,
   a **Username** field, a **Password** field, a **Sign in** button.
2. Tap the **Username** field. If nothing appears within ~2 s, tap it once more — the first tap on a
   WebView field sometimes only moves focus and does not open an autofill session.
3. **The gate under test**: LocalKeep's prompt *"Allow com.localkeep.passkeytest to fill saved logins
   for example.test?"* with *"Only allow this if you recognize both the app and site. LocalKeep
   remembers this pairing and won't ask again."* and **Allow** / **Deny**.
4. Tap **Allow**. The decision is pinned permanently for the (package, domain) pair, so this prompt
   appears **once per install of this app**. To see it again, either reinstall this app, or in
   LocalKeep go to Settings → **Trusted apps** and tap **Stop filling example.test** (or
   **Forget LocalKeep Passkey Test**, which drops the pinned signature too).
5. Tap the **Username** field again. The autofill suggestion row now names the stored login; tap it,
   answer the unlock prompt, and both fields fill.
6. Tap **Deny** on a fresh install instead to check the negative case: no suggestion is offered.

### Resetting between runs

- Test app only: `adb uninstall com.localkeep.passkeytest` — this clears the confirmed-domain pin and
  the pinned signature, so C can be re-run from the top. It also changes nothing in the vault, but it
  *does* invalidate the passkey created in A for flow B (new certificate, new binding).
- Passkey item: delete **"Passkey Test — example.test"** in LocalKeep.
- Never `adb uninstall com.localkeep.foss`, and never run any `connected*` Gradle task here.
