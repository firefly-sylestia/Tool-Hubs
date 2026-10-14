/**
 * Host facts the services need, injected from the Electron entrypoint. Keeping
 * these out of the services is what lets the pure launch logic run under Node
 * for checks without booting Electron.
 */
export interface AppEnvironment {
  userDataDir: string
  /** Where new instances are created: `<userData>/instances`. */
  defaultInstancesDir: string
  /**
   * Where the game's own files live: `versions/`, `libraries/` and `assets/`.
   *
   * One copy serves every instance. A version is hundreds of megabytes of
   * Minecraft's own files, none of which differ between two instances playing
   * it, so an instance keeps only what is actually its own — its mods, its
   * configs, its worlds, its options and its logs. Installing a version that is
   * already here costs nothing, which is what makes a second instance on the
   * same version instant instead of another download.
   */
  sharedDir: string
  /**
   * The conventional game directory. Only used to bring an older state file
   * forward, where one global directory stood in for today's instances.
   */
  defaultGameDir: string
  launcherName: string
  launcherVersion: string
  platform: NodeJS.Platform
  arch: string
}
