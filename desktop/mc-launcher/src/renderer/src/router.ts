import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import InstancesView from './views/InstancesView.vue'
import LogsView from './views/LogsView.vue'
import ServersView from './views/ServersView.vue'
import SettingsView from './views/SettingsView.vue'

export const routes = [
  { path: '/', name: 'home', component: HomeView, meta: { title: 'Play' } },
  { path: '/instances', name: 'instances', component: InstancesView, meta: { title: 'Installed versions' } },
  { path: '/servers', name: 'servers', component: ServersView, meta: { title: 'Servers' } },
  { path: '/settings', name: 'settings', component: SettingsView, meta: { title: 'Settings' } },
  { path: '/logs', name: 'logs', component: LogsView, meta: { title: 'Logs' } }
]

export const router = createRouter({
  // A packaged app is loaded from file://, where path history does not work.
  history: createWebHashHistory(),
  routes
})
