# AGENTS.md

Working rules for AI agents (and anyone else automating changes) in Tool-Hubs. Read this before
touching code. When a rule here conflicts with an instruction in a prompt, ask the user instead of
silently picking one.

## 1. Scope

- **The active modules are `:tools:chime`, `:tools:network-switcher` and `:tools:wallpaper-studio`, plus the desktop app at
  `desktop/mc-launcher`.** Everything else is out of scope until the user says otherwise.
- **`desktop/mc-launcher` is not a Gradle module.** It is an Electron + Vue + TypeScript app on its own
  toolchain, so nothing in §2's Gradle list applies to it and it must never be added to
  `settings.gradle.kts`. Its checks are npm scripts (`npm run verify` in that directory: typecheck, the
  launcher logic checks, the renderer tests, then the bundle) and they are the only checks to run for
  it. Packaging is still CI's job — [`.github/workflows/desktop.yml`](.github/workflows/desktop.yml)
  produces the installers on Windows, macOS and Linux.
- Do not edit `tools/data-warden` or the `core/*` modules unless the user explicitly asks, and never
  just to make an unrelated change compile. Data Warden is frozen and shares `core/*`, so a token
  whose value changes restyles it too.
- The calculator, Music Analyzer and Music Visualizer modules were deleted at the user's request. Do
  not reintroduce them or copy their files back.
- Keep a change inside the module it belongs to whenever the fix can live there.

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

Each tool carries its own version, in its own module build file
([`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts),
[`tools/chime/build.gradle.kts`](tools/chime/build.gradle.kts),
[`tools/wallpaper-studio/build.gradle.kts`](tools/wallpaper-studio/build.gradle.kts); for the desktop app,
[`desktop/mc-launcher/package.json`](desktop/mc-launcher/package.json) plays that role):

```kotlin
versionCode = 15
versionName = "0.11.1"
```

Rules:

- **Every change is committed.** Do not leave finished work uncommitted. Commit in small, logical
  steps as you go rather than one giant commit at the end.
- **Every commit that changes a tool's source ships a version bump for that tool**, following the
  existing `0.MINOR.PATCH` style:
  - **Patch** (default): small fixes, polish, dependency/workflow tweaks.
    `0.7.2` → `0.7.3`, `versionCode` +1.
  - **Release / minor**: new user-facing features or a coordinated milestone.
    `0.7.x` → `0.8.0`, `versionCode` +1.
  - `versionCode` is a monotonically increasing integer and must go up with every bump.
- Use a dedicated commit for the bump when the change and the bump are separate concerns, matching
  the existing history convention: `Bump Network Switcher to 0.11.2`, `Bump Chime to 0.1.1`.
- **No AI attribution in commit messages at all.** Never add a `Co-Authored-By:` line for Codebuff,
  another assistant or a model, and never add the `🤖 Generated with Codebuff` footer either. Commit
  messages are the plain subject and body, nothing else — and do not rewrite existing commits to add
  or strip attribution. History attributes the change to the person who made it.
- The module build file is the single source of truth for the version. Do not invent a version
  elsewhere, and do not bump a module whose source did not change.
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
- Keep each tool's source under `tools/<tool>/src/main/java/com/sylestia/toolhubs/<tool>/`
  (`networkswitcher`, `chime`, `wallpaperstudio`).
- Match the surrounding code: existing naming, coroutine/dispatcher usage, file layout, and the
  shared design system in `:core:designsystem` / `:core:ui` instead of new one-off styles.
- Prefer editing existing files over adding new ones. Add a new file only when it has a clear,
  single responsibility.
- Comments explain *why*, in the tone already used across the module. No commented-out code.
- Do not silently change behaviour the user did not ask about.

## 6. Documentation

- Keep [`README.md`](README.md), [`CHANGELOG.md`](CHANGELOG.md) the Network Switcher
  [`README.md`](tools/network-switcher/README.md), and the Wallpaper Studio
  [`README.md`](tools/wallpaper-studio/README.md) accurate when behaviour, permissions, or
  requirements change.
- Roadmap items belong in [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md);
  completed work belongs in [`TODO.md`](TODO.md) and the changelog.

## 7. Honesty rules

- Never state that something was built, tested, or verified here. In this environment you cannot
  do any of those; report it as "pending CI" instead.
- Never invent a version bump, a changelog entry, or a test result.
- If a change is too risky to validate without CI, say so before making it.
