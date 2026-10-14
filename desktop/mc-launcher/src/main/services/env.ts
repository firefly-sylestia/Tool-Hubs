/**
 * Host facts the services need, injected from the Electron entrypoint. Keeping
 * these out of the services is what lets the pure launch logic run under Node
 * for checks without booting Electron.
 */
export interface AppEnvironment {
  userDataDir: string
  defaultGameDir: string
  launcherName: string
  launcherVersion: string
  platform: NodeJS.Platform
  arch: string
}
