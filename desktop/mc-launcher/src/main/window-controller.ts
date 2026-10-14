import { BrowserWindow } from 'electron'

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
}
