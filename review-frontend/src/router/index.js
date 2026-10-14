import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../components/Layout.vue'
import WordManage from '../views/WordManage.vue'
import GroupManage from '../views/GroupManage.vue'
import DataManage from '../views/DataManage.vue'
import ReviewCenter from '../views/ReviewCenter.vue'
import StatsDashboard from '../views/StatsDashboard.vue'
import AiSettings from '../views/AiSettings.vue'
import ProxySettings from '../views/ProxySettings.vue'
import DifficultSettings from '../views/DifficultSettings.vue'

const routes = [
  {
    path: '/',
    component: Layout,
    redirect: '/words',
    children: [
      { path: 'words', name: 'Words', component: WordManage },
      // 易错词库：复用词库管理页面，difficultMode 只做减法（固定查易错词组 + 换按钮）
      { path: 'difficult-words', name: 'DifficultWords', component: WordManage, props: { difficultMode: true } },
      { path: 'groups', name: 'Groups', component: GroupManage },
      { path: 'data', name: 'Data', component: DataManage },
      { path: 'review', name: 'Review', component: ReviewCenter },
      { path: 'stats', name: 'Stats', component: StatsDashboard },
      { path: 'ai', name: 'AI', component: AiSettings },
      { path: 'proxy', name: 'Proxy', component: ProxySettings },
      { path: 'difficult', name: 'Difficult', component: DifficultSettings }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
