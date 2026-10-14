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
   * The conventional game directory. Only used to bring an older state file
   * forward, where one global directory stood in for today's instances.
   */
  defaultGameDir: string
  launcherName: string
  launcherVersion: string
  platform: NodeJS.Platform
  arch: string
}
