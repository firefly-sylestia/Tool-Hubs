import { createApp } from 'vue'
import 'vuetify/styles'
// After Vuetify's own sheet, so the motion here wins where the two overlap.
import './styles/app-motion.css'
import App from './App.vue'
import { initLauncher } from './composables/useLauncher'
import { vuetify } from './plugins/vuetify'
import { router } from './router'

await initLauncher()

createApp(App).use(router).use(vuetify).mount('#app')
