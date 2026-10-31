# Contributing

Thanks for helping with Tool-Hubs. This document covers the versioning, release and commit process;
[`AGENTS.md`](AGENTS.md) covers the rules AI agents follow in this repository.

## Current scope

`:tools:chime` and `:tools:network-switcher` are the modules under active development. Data Warden is
frozen — its theme tokens, shared header and metric primitives are deliberately left untouched so it
keeps rendering and behaving exactly as before. The calculator, Music Analyzer and Music Visualizer
have been removed from the repository.

The `core/*` modules are shared, so changes there affect every tool. Treat them as out of scope
unless a change genuinely requires it.

## Where builds happen

**CI only.** [`.github/workflows/android.yml`](.github/workflows/android.yml) builds the release APK
for every module on pushes to `main` and `dev`, on pushes of a `v*` release tag, on pull requests,
and on manual dispatch, then uploads the APKs as artifacts (1-day retention).

A `prune` job runs alongside the build and deletes this repository's artifacts older than a day and
all but the ten newest caches. It is not a prerequisite of the build and its step tolerates failure
on purpose: housekeeping must never skip or redden a build, and it must pass `--repo` to `gh`
because it has no checkout to read a git remote from. Actions storage is one account-wide quota shared by every repository:
an artefact bucket that is full fails the upload step on every module, and a Gradle cache bucket that
is full reaches gigabytes, so neither is left to age out on its own. The prune job holds the only
`actions: write` permission in the workflow; the build job stays `contents: read` because it checks
out and runs repository code.

There is no Gradle wrapper in the repository; CI pins Gradle `9.3.1` and JDK 17, and installs the
Android SDK itself. Do not add wrapper files or a local toolchain as part of a change.

Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` are present (CI decodes
`KEYSTORE_BASE64` and friends from repository secrets), and fall back to a throwaway debug key
otherwise so fork and pull-request builds still work.

## Versioning

Each tool carries its own version in its own module build file
([`tools/chime/build.gradle.kts`](tools/chime/build.gradle.kts),
[`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts)), and that file
is the single source of truth for that tool:

```kotlin
defaultConfig {
    versionCode = 15
    versionName = "0.11.1"
}
```

- `versionName` follows `0.MINOR.PATCH`.
- `versionCode` is a monotonically increasing integer and must increase with every bump.
- **Every change to a tool's source ships with a version bump for that tool.** A workflow-only or
documentation-only commit does not need one.
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
4. Commit and push the bump, then push the `vX.Y.Z` tag with the release notes. Pushing the tag
   starts its own CI run — wait for it to go green before treating the release as done.

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
