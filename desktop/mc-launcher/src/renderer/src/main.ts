import { createApp } from 'vue'
import 'vuetify/styles'
// Vuetify's default icon set is Material Design Icons, and the font that draws
// it is not part of `vuetify/styles`: without this every `<v-icon>` and every
// `prepend-icon` renders its ligature as the literal word.
import '@mdi/font/css/materialdesignicons.css'
// After Vuetify's own sheet, so the motion here wins where the two overlap.
import './styles/app-motion.css'
import App from './App.vue'
import { initLauncher } from './composables/useLauncher'
import { vuetify } from './plugins/vuetify'
import { router } from './router'

await initLauncher()

createApp(App).use(router).use(vuetify).mount('#app')
