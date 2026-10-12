import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../components/Layout.vue'
import WordManage from '../views/WordManage.vue'
import GroupManage from '../views/GroupManage.vue'
import DataManage from '../views/DataManage.vue'
import ReviewCenter from '../views/ReviewCenter.vue'
import StatsDashboard from '../views/StatsDashboard.vue'
import AiSettings from '../views/AiSettings.vue'

const routes = [
  {
    path: '/',
    component: Layout,
    redirect: '/words',
    children: [
      { path: 'words', name: 'Words', component: WordManage },
      { path: 'groups', name: 'Groups', component: GroupManage },
      { path: 'data', name: 'Data', component: DataManage },
      { path: 'review', name: 'Review', component: ReviewCenter },
      { path: 'stats', name: 'Stats', component: StatsDashboard },
      { path: 'ai', name: 'AI', component: AiSettings }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
