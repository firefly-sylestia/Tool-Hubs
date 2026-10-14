import type { FilePickerKind } from './types'


export interface FilePickerPreset {
  title: string
  /** The filter's name, as the OS dialog shows it. */
  filter: string
  extensions: string[]
}

export const FILE_PICKERS: Record<Exclude<FilePickerKind, 'any'>, FilePickerPreset> = {
  // Both pack formats are offered: which one someone has depends on where they
  // got the pack, not on anything the file name says.
  modpack: {
    title: 'Choose a pack to import',
    filter: 'Modpacks (.mrpack, .zip)',
    extensions: ['mrpack', 'zip']
  },
  mod: { title: 'Choose mods to add', filter: 'Mods (.jar)', extensions: ['jar'] },
  resourcepack: {
    title: 'Choose resource packs to add',
    filter: 'Resource packs (.zip)',
    extensions: ['zip']
  },
  shaderpack: {
    title: 'Choose shader packs to add',
    filter: 'Shader packs (.zip)',
    extensions: ['zip']
  },
  world: { title: 'Choose a world to import', filter: 'World archives (.zip)', extensions: ['zip'] }
}

/** The extensions a picker of this kind offers, or none when it is unfiltered. */
export function pickerExtensions(kind: FilePickerKind): string[] {
  return kind === 'any' ? [] : FILE_PICKERS[kind].extensions
}
