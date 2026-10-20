# Contributing

Thanks for considering a contribution. Bug reports, fixes and translation corrections are all
welcome. For anything security-sensitive, follow [`SECURITY.md`](SECURITY.md) instead of opening a
public issue.

## License of contributions (inbound = outbound)

This project is licensed under **GPL-3.0-only with an additional permission for app-store
conveyance** (see `LICENSE` and `LICENSE-EXCEPTIONS.md`). By submitting a contribution you agree
that it is licensed under those same terms, including the additional permission, which is what
allows your code to ship in the Google Play build. There is no CLA and no copyright assignment.

## Developer Certificate of Origin

Every commit must be signed off (`git commit -s`), certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/):

    Signed-off-by: Your Name <you@example.com>

Pull requests with unsigned commits will be asked to add the sign-off.

To have the sign-off added automatically, enable the repository's hook once per clone:

    ln -s ../../.githooks/prepare-commit-msg .git/hooks/prepare-commit-msg

Use a symlink rather than `git config core.hooksPath .githooks`, which replaces `.git/hooks`
wholesale and would disable any other hooks you keep there.

## Ground rules

- **No network for vault data.** A change that adds networking to the vault, crypto, model, import
  or health code paths will not be accepted. The `foss` build's merged manifest must stay within
  its `checkFlavorPermissions` allowlist, which contains no network permission;
  `checkNoNetworkingDeps` blocks networking libraries from the modules that handle vault data.
- **Security-relevant changes** (`:core:crypto`, `:core:vault`, the vault format) need tests,
  including known-answer vectors for cryptographic code, and are reviewed strictly.
- **The vault format is frozen.** Protobuf field numbers, header layout and HKDF labels do not
  change; a format change needs a version bump and a migration path for existing vaults.
- **No hardcoded user-facing strings.** Every visible string is a string resource with positional
  format arguments; `checkNoHardcodedUiStrings` and lint enforce this.
- **Claims must be true.** Copy that states a security property must be one the
  [threat model](docs/THREAT-MODEL.md) supports. Error messages state the real cause and never echo
  exception messages, file paths or URIs.
- **Every fix carries a test** that fails without it.

## Before opening a pull request

Run the unit tests, lint and the guardrail tasks listed in the README's *Building* section, and
describe in the pull request what you verified and on which device or emulator. Call out any change
to navigation, the vault format, permissions, cryptography or networking explicitly. UI changes
should be checked by running the app, not only by compiling it.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), for example
`fix(autofill): ...` or `feat(import): ...`.
