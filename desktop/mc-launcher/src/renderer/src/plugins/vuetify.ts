import { createVuetify } from 'vuetify'
// Vuetify only registers what it is given; without these the templates' tags
// resolve to unknown elements and the UI renders empty.
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

export const vuetify = createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'dark'
  }
})
