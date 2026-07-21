import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../components/Layout.vue'
import WordManage from '../views/WordManage.vue'
import DataManage from '../views/DataManage.vue'
import ReviewCenter from '../views/ReviewCenter.vue'
import StatsDashboard from '../views/StatsDashboard.vue'

const routes = [
  {
    path: '/',
    component: Layout,
    redirect: '/words',
    children: [
      { path: 'words', name: 'Words', component: WordManage },
      { path: 'data', name: 'Data', component: DataManage },
      { path: 'review', name: 'Review', component: ReviewCenter },
      { path: 'stats', name: 'Stats', component: StatsDashboard }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
