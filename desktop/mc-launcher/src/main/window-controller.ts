import { BrowserWindow, dialog, shell } from 'electron'
import { FILE_PICKERS } from '@shared/file-pickers'
import type { FilePickerKind } from '@shared/types'

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
   * Each caller says what it is choosing, so the dialog leads with files it can
   * actually use: a modpack import has to offer `.mrpack` (Modrinth) and `.zip`
   * (CurseForge), a mod is a `.jar`, and everything the game reads from a folder
   * of packs is a `.zip`. `any` drops the filter, which is what choosing a Java
   * binary needs — a picker offering only archives cannot choose `java`.
   *
   * "Everything" stays in the list behind each filter, because a pack or a jar
   * that is named unusually is still the file someone means.
   */
  async pickFiles(
    event?: Electron.IpcMainInvokeEvent,
    input: { kind?: FilePickerKind } = {}
  ): Promise<{ paths: string[] }> {
    const window = this.windowFor(event)
    const kind = input.kind ?? 'any'
    const single = kind === 'any' || kind === 'modpack' || kind === 'world'
    const preset = kind === 'any' ? null : FILE_PICKERS[kind]
    const options: Electron.OpenDialogOptions = {
      title: preset ? preset.title : 'Choose a file',
      properties: single ? ['openFile'] : ['openFile', 'multiSelections'],
      filters: preset
        ? [
            { name: preset.filter, extensions: preset.extensions },
            { name: 'Everything', extensions: ['*'] }
          ]
        : []
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
