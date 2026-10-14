import { createApp } from 'vue'
import 'vuetify/styles'
import App from './App.vue'
import { initLauncher } from './composables/useLauncher'
import { vuetify } from './plugins/vuetify'
import { router } from './router'

await initLauncher()

createApp(App).use(router).use(vuetify).mount('#app')
