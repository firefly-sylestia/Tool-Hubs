import { createRouter, createWebHashHistory } from 'vue-router'
import DownloadsView from './views/DownloadsView.vue'
import HomeView from './views/HomeView.vue'
import InstancesView from './views/InstancesView.vue'
import LogsView from './views/LogsView.vue'
import ServersView from './views/ServersView.vue'
import SettingsView from './views/SettingsView.vue'
import StoreView from './views/StoreView.vue'

export const routes = [
  { path: '/', name: 'home', component: HomeView, meta: { title: 'Play' } },
  { path: '/store', name: 'store', component: StoreView, meta: { title: 'Modrinth' } },
  { path: '/instances', name: 'instances', component: InstancesView, meta: { title: 'Instances' } },
  { path: '/servers', name: 'servers', component: ServersView, meta: { title: 'Servers' } },
  { path: '/settings', name: 'settings', component: SettingsView, meta: { title: 'Settings' } },
  { path: '/logs', name: 'logs', component: LogsView, meta: { title: 'Logs' } },
  // Last on purpose: the keyboard shortcuts are Ctrl plus the row's position,
  // so a new page goes at the end rather than moving everyone else's.
  { path: '/downloads', name: 'downloads', component: DownloadsView, meta: { title: 'Downloads' } }
]

export const router = createRouter({
  // A packaged app is loaded from file://, where path history does not work.
  history: createWebHashHistory(),
  routes
})
