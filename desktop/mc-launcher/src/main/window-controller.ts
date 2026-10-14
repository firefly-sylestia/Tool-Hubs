import { BrowserWindow, dialog, shell } from 'electron'

/**
 * Window actions exposed to the renderer as `windowController`. This lives in
 * its own module because it is the only service that needs Electron itself; the
 * launcher services stay importable by plain Node.
 */
export class WindowController {
  private windowFor(event: Electron.IpcMainInvokeEvent | undefined): BrowserWindow | null {
    if (event) {
      const fromEvent = BrowserWindow.fromWebContents(event.sender)
      if (fromEvent) return fromEvent
    }
    return BrowserWindow.getAllWindows()[0] ?? null
  }

  minimize(event?: Electron.IpcMainInvokeEvent): { ok: boolean } {
    this.windowFor(event)?.minimize()
    return { ok: true }
  }

  maximize(event?: Electron.IpcMainInvokeEvent): { ok: boolean; maximized: boolean } {
    const window = this.windowFor(event)
    if (!window) return { ok: false, maximized: false }
    if (window.isMaximized()) window.unmaximize()
    else window.maximize()
    return { ok: true, maximized: window.isMaximized() }
  }

  close(event?: Electron.IpcMainInvokeEvent): { ok: boolean } {
    this.windowFor(event)?.close()
    return { ok: true }
  }

  focus(event?: Electron.IpcMainInvokeEvent): { ok: boolean } {
    this.windowFor(event)?.focus()
    return { ok: true }
  }

  isMaximized(event?: Electron.IpcMainInvokeEvent): { maximized: boolean } {
    return { maximized: this.windowFor(event)?.isMaximized() ?? false }
  }

  toggleDevTools(event?: Electron.IpcMainInvokeEvent): { ok: boolean } {
    this.windowFor(event)?.webContents.toggleDevTools()
    return { ok: true }
  }

  /**
   * The OS folder picker, used to point the launcher at an installation or at a
   * single version folder. Asking the OS beats making someone type a Windows
   * path by hand, and it is the only way the renderer can name a path it is not
   * allowed to read.
   */
  async pickFolder(event?: Electron.IpcMainInvokeEvent): Promise<{ path: string | null }> {
    const window = this.windowFor(event)
    const options: Electron.OpenDialogOptions = {
      title: 'Choose a Minecraft folder',
      properties: ['openDirectory']
    }
    const result = window
      ? await dialog.showOpenDialog(window, options)
      : await dialog.showOpenDialog(options)
    return { path: result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0] }
  }

  /**
   * The file picker, for adding mods, resource packs and shader packs.
   *
   * Passing `any: true` drops the filter, which is what choosing a Java binary
   * needs: a picker offering only .jar and .zip cannot choose `java`. It also
   * picks several files, which a Java binary does not want, so a single one is
   * all that comes back in that mode.
   */
  async pickFiles(
    event?: Electron.IpcMainInvokeEvent,
    input: { any?: boolean } = {}
  ): Promise<{ paths: string[] }> {
    const window = this.windowFor(event)
    const options: Electron.OpenDialogOptions = {
      title: input.any ? 'Choose a file' : 'Choose files',
      properties: input.any ? ['openFile'] : ['openFile', 'multiSelections'],
      filters: input.any
        ? []
        : [
            { name: 'Mods, packs and shaders', extensions: ['jar', 'zip'] },
            { name: 'Everything', extensions: ['*'] }
          ]
    }
    const result = window
      ? await dialog.showOpenDialog(window, options)
      : await dialog.showOpenDialog(options)
    return { paths: result.canceled ? [] : result.filePaths }
  }

  /** Hands a path to the OS, so a folder opens in Explorer, Finder or a file manager. */
  async openPath(params: { path: string }): Promise<{ opened: boolean; error: string | null }> {
    if (!params?.path) throw new Error('openPath needs a path.')
    const error = await shell.openPath(params.path)
    return { opened: error === '', error: error === '' ? null : error }
  }
}
