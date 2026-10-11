# AGENTS.md

Working rules for AI agents (and anyone else automating changes) in Tool-Hubs. Read this before
touching code. When a rule here conflicts with an instruction in a prompt, ask the user instead of
silently picking one.

## 1. Scope

- **Only work on `:tools:network-switcher` for now.** Everything else is out of scope until the
  user says otherwise.
- Do not edit `tools/calculator`, `tools/data-warden`, `tools/music-analyzer`,
  `tools/music-visualizer`, or the `core/*` modules unless the user explicitly asks, and never just
  to make an unrelated change compile.
- Keep changes inside the Network Switcher module whenever a fix can live there.

## 2. Never build or compile in this environment

- **Do not run Gradle, the Android build, tests or an emulator here.** No `gradle ...`,
  `./gradlew`, `assemble*`, `compile*`, `test*`, `lint*`, `install*` or `adb` commands.
- This environment has no working Android toolchain and no wrapper, and a local build tells you
  nothing useful about the result.
- Verification happens in **GitHub Actions**: [`.github/workflows/android.yml`](.github/workflows/android.yml)
  builds the release APK for every module on push and pull request. Treat CI as the compiler.
- Because you cannot compile, be conservative: keep edits small, follow the existing patterns in the
  file you are changing, keep imports and Compose scopes consistent, and re-read the diff before
  committing. Never claim a change "builds" or "compiles" — only CI can establish that.
- If the user reports a compile error, fix the cause in the source; never add a suppression, weaken
  an assertion, or comment code out just to get past it.

## 3. Commit every change, and bump the version with it

The version lives in [`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts):

```kotlin
versionCode = 10
versionName = "0.7.2"
```

Rules:

- **Every change is committed.** Do not leave finished work uncommitted. Commit in small, logical
  steps as you go rather than one giant commit at the end.
- **Every commit that changes Network Switcher ships a version bump**, following the existing
  `0.MINOR.PATCH` style:
  - **Patch** (default): small fixes, polish, dependency/workflow tweaks.
    `0.7.2` → `0.7.3`, `versionCode` +1.
  - **Release / minor**: new user-facing features or a coordinated milestone.
    `0.7.x` → `0.8.0`, `versionCode` +1.
  - `versionCode` is a monotonically increasing integer and must go up with every bump.
- Use a dedicated commit for the bump when the change and the bump are separate concerns, matching
  the existing history convention: `Bump Network Switcher to 0.7.3`.
- **No AI co-author trailers.** Never add a `Co-Authored-By:` line for Codebuff, another assistant
  or a model to a commit message, and never rewrite an existing commit to add one — the trailer
  survives in `git log` forever. History attributes the change to the person who made it.
- The build file is the single source of truth for the version. Do not invent a version elsewhere.
- Update [`CHANGELOG.md`](CHANGELOG.md) alongside a release/minor bump, and run the UI version
  display past a check if the app reads `BuildConfig.VERSION_NAME` anywhere.

## 4. Bump the version on push

- **Whenever you push, the push must include a version bump.** If the commits you are about to push
  do not already contain one, add the bump commit first, then push.
- This keeps every pushed state of `main` identifiable and installable. Never push a code change
  without the accompanying bump.
- Pushing is only done when the user authorizes it. If you are asked to push, complete the bump
  first; if a push is requested but there is nothing to bump, say so instead of pushing anyway.

## 5. Style and conventions

- Kotlin 2.2.20, Jetpack Compose (BOM `2026.08.00`), Material 3, AGP 9.1.1, JDK 17, `compileSdk 37`,
  `minSdk 26`. Follow [`gradle/libs.versions.toml`](gradle/libs.versions.toml) for dependencies —
  add a version there rather than hardcoding one in a module.
- Keep source in `tools/network-switcher/src/main/java/com/sylestia/toolhubs/networkswitcher/`.
- Match the surrounding code: existing naming, coroutine/dispatcher usage, file layout, and the
  shared design system in `:core:designsystem` / `:core:ui` instead of new one-off styles.
- Prefer editing existing files over adding new ones. Add a new file only when it has a clear,
  single responsibility.
- Comments explain *why*, in the tone already used across the module. No commented-out code.
- Do not silently change behaviour the user did not ask about.

## 6. Documentation

- Keep [`README.md`](README.md), [`CHANGELOG.md`](CHANGELOG.md) and the Network Switcher
  [`README.md`](tools/network-switcher/README.md) accurate when behaviour, permissions, or
  requirements change.
- Roadmap items belong in [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md);
  completed work belongs in [`TODO.md`](TODO.md) and the changelog.

## 7. Honesty rules

- Never state that something was built, tested, or verified here. In this environment you cannot
  do any of those; report it as "pending CI" instead.
- Never invent a version bump, a changelog entry, or a test result.
- If a change is too risky to validate without CI, say so before making it.
