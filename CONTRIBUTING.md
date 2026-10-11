# Contributing

Thanks for helping with Tool-Hubs. This document covers the versioning, release and commit process;
[`AGENTS.md`](AGENTS.md) covers the rules AI agents follow in this repository.

## Current scope

`:tools:network-switcher` is the only module under active development. The calculator, Data Warden,
Music Analyzer and Music Visualizer are frozen — their theme tokens, shared header and metric
primitives are deliberately left untouched so they keep rendering and behaving exactly as before.

The `core/*` modules are shared, so changes there affect every tool. Treat them as out of scope
unless a Network Switcher change genuinely requires it.

## Where builds happen

**CI only.** [`.github/workflows/android.yml`](.github/workflows/android.yml) builds the release APK
for every module on pushes to `main` and `dev`, on pull requests, and on manual dispatch, then
uploads the APKs as artifacts (1-day retention).

There is no Gradle wrapper in the repository; CI pins Gradle `9.3.1` and JDK 17, and installs the
Android SDK itself. Do not add wrapper files or a local toolchain as part of a change.

Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` are present (CI decodes
`KEYSTORE_BASE64` and friends from repository secrets), and fall back to a throwaway debug key
otherwise so fork and pull-request builds still work.

## Versioning

The version lives in [`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts)
and is the single source of truth:

```kotlin
defaultConfig {
    versionCode = 10
    versionName = "0.7.2"
}
```

- `versionName` follows `0.MINOR.PATCH`.
- `versionCode` is a monotonically increasing integer and must increase with every bump.
- **Every change ships with a version bump.**
  - **Patch** — the default for fixes, polish, docs and workflow tweaks:
    `0.7.2` → `0.7.3`, `versionCode` +1.
  - **Release / minor** — new user-facing features or a coordinated milestone:
    `0.7.x` → `0.8.0`, `versionCode` +1.
- Version bumps get their own commit when the change and bump are separate concerns, matching the
  existing history:

  ```text
  Bump Network Switcher to 0.7.3
  ```

- **On push, the version is bumped.** Every push must include the accompanying bump commit, so any
  pushed state of `main` is identifiable and installable.

## Adding a release entry

1. Update `versionCode` / `versionName` in the module build file.
2. Add a `## [X.Y.Z]` section to [`CHANGELOG.md`](CHANGELOG.md) with the user-visible changes.
3. Move anything completed out of the roadmap
   ([`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md)) into
   [`TODO.md`](TODO.md).
4. Commit and push. Wait for CI to go green before treating the release as done.

## Commit messages

- Imperative subject, sentence case, one line, describing the intent: `Fix fake interval
  accounting`, `Add backup SIM 5G recovery pause toggle`.
- Keep commits small and focused. Prefer several logical commits over one large one.
- Module-scoped changes may use the `network-switcher:` prefix, as some history does.
- Do not commit build outputs, `.ci/` keystores, `local.properties`, or IDE files.

## Pull requests

- Target `main` (or `dev` for work in progress) and keep the scope to the module you are changing.
- Describe what changed and why, and call out anything that needs device testing.
- A green CI build is required; a successful local build is not available to you.
