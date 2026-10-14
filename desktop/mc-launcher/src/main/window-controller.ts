import { BrowserWindow, dialog } from 'electron'

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
}
