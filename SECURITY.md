# Security policy

## Reporting a vulnerability

Email **support@localkeep.app** with "Security" in the subject line. Please do not open a public
issue for anything that could put users' vaults at risk.

Include what you can of:

- the affected build (`foss` or `play`), version, Android version and device;
- steps to reproduce, or a proof of concept;
- which tier of the [threat model](docs/THREAT-MODEL.md) you believe it breaks, and what an
  attacker gains.

You will get an acknowledgement within 7 days. LocalKeep is maintained by one person, so fixes are
prioritised by impact; you will be told what is planned and when a fix ships. Please allow up to
90 days before public disclosure, or tell us if you need a different timeline. There is no bug
bounty. Reporters are credited in the release notes unless they ask not to be.

## Scope

In scope is anything that defeats a protection the [threat model](docs/THREAT-MODEL.md) claims, in
tiers **T0, T1, T2, T4 and T5**:

- vault contents, the master password or key material leaving the device or becoming readable
  without the master password (T0, T4, T5);
- a protected field revealed or copied without the re-authentication the app promises, or a
  disclosure through search, autofill, notifications, the clipboard or screenshots (T1, T2);
- another app on a non-rooted device obtaining vault data, autofill credentials or passkeys it was
  not granted (T2);
- the duress vault revealing its existence beyond the residuals the threat model already states;
- weaknesses in the cryptography, the vault file format, or the parsers for imported files;
- any network capability in the `foss` build, or network use in the `play` build beyond billing.

A user-facing claim (in the app, the store listing or the website) that promises more than the
software delivers is also a security bug. Please report it.

## Out of scope

- **T3: a rooted device, a hostile or modified OS, or a compromised ROM.** No userspace password
  manager can defend against an attacker who controls the operating system, and LocalKeep does not
  claim to.
- Residuals the threat model already documents (for example, the clipboard being readable while a
  copy is live, or accessibility services the user has granted).
- Attacks that require the master password or the Recovery Kit.
- Builds you compiled with modified source.

## Supported versions

Only the latest release of each build (`play` from Google Play, `foss` from the project's own
releases) receives security fixes.
