import { createVuetify } from 'vuetify'
// Vuetify only registers what it is given; without these the templates' tags
// resolve to unknown elements and the UI renders empty.
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

/**
 * The launcher's look, in one place.
 *
 * Two things are decided here. The palettes are a little warmer and a little
 * lower-contrast than Vuetify's defaults, because a window that is open beside
 * a game should not be the brightest thing on the screen — the surfaces are
 * tinted towards the green rather than left neutral grey. And every control is
 * rounded by default rather than per component: the app is a desktop window,
 * not a web page, and a square corner in the middle of it reads as unfinished.
 *
 * The blur itself lives in `styles/app-motion.css`: it needs the surface to be
 * translucent, which is a colour decision, not a theme one.
 */
export const vuetify = createVuetify({
  components,
  directives,
  theme: {
    // The desktop decides until the launcher is told otherwise. Vuetify watches
    // the system preference, and `App.vue` re-applies it when the setting says
    // `system` and the desktop changes its mind while the app is open.
    defaultTheme: 'system',
    themes: {
      light: {
        colors: {
          primary: '#2e7d32',
          secondary: '#5d4037',
          background: '#eff3ea',
          surface: '#fbfdf8',
          'surface-variant': '#e3e9dd',
          'on-surface-variant': '#4a5344',
          outline: '#c3cbb9',
          success: '#2e7d32',
          info: '#0f6f7a',
          warning: '#c77700',
          error: '#c62828'
        }
      },
      dark: {
        colors: {
          primary: '#5abf60',
          secondary: '#bcaaa4',
          background: '#0e130f',
          surface: '#161c17',
          'surface-variant': '#232b24',
          'on-surface-variant': '#bcc7b6',
          outline: '#3b463b',
          success: '#5abf60',
          info: '#4dd0c4',
          warning: '#e0a33a',
          error: '#ef5350'
        }
      }
    }
  },
  defaults: {
    VCard: { rounded: 'lg' },
    VBtn: { rounded: 'lg' },
    VTextField: { rounded: 'lg' },
    VTextarea: { rounded: 'lg' },
    VSelect: { rounded: 'lg' },
    VAutocomplete: { rounded: 'lg' },
    VCombobox: { rounded: 'lg' },
    VChip: { rounded: 'lg' },
    VAlert: { rounded: 'lg' },
    VList: { rounded: 'lg' },
    VListItem: { rounded: 'lg' },
    VSnackbar: { rounded: 'lg' },
    VProgressLinear: { rounded: 'lg' },
    VFileInput: { rounded: 'lg' }
  }
})
