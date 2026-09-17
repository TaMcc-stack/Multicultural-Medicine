import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { public: true, title: '多民族特色医学智能体' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录 · 多民族特色医学智能体' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '注册 · 多民族特色医学智能体' },
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/QaView.vue'),
      meta: { title: '智能对话 · 多民族特色医学智能体' },
    },
    {
      path: '/search',
      name: 'advanced-search',
      component: () => import('@/views/AdvancedSearchView.vue'),
      meta: { title: '高级检索 · 多民族特色医学智能体' },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { title: '个人中心 · 多民族特色医学智能体' },
    },
    {
      path: '/dynamics',
      name: 'dynamics',
      component: () => import('@/views/DynamicView.vue'),
      meta: { title: '动态 · 多民族特色医学智能体' },
    },
    {
      path: '/kb',
      name: 'kb',
      component: () => import('@/views/KnowledgeView.vue'),
      meta: { title: '知识库 · 多民族特色医学智能体' },
    },
    {
      path: '/backend',
      name: 'backend',
      component: () => import('@/views/BackendView.vue'),
      meta: { title: '后台 · 多民族特色医学智能体' },
    },
    {
      path: '/demand',
      name: 'demand',
      component: () => import('@/views/DemandView.vue'),
      meta: { title: '需求分析 · 多民族特色医学智能体' },
    },
    {
      // 全站唯一「公开 + 调业务接口」的页面：未登录也能浏览和发布（见 api/feedback.ts 的说明），
      // 服务端对应地把 /api/feedback 放进了可选登录白名单。点赞仍需登录。
      path: '/feedback',
      name: 'feedback',
      component: () => import('@/views/FeedbackView.vue'),
      meta: { public: true, title: '用户反馈 · 多民族特色医学智能体' },
    },
    {
      // 不挂在导航里：它是从「需求分析」点【去补充】进来的，带 ?gap=<缺口id>
      path: '/supply',
      name: 'supply',
      component: () => import('@/views/SupplyView.vue'),
      meta: { title: '文献补录 · 多民族特色医学智能体' },
    },
    // 兼容旧路径：/qa 重定向到 /chat
    { path: '/qa', redirect: { name: 'chat' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 路由守卫：
// - 未登录访问受保护页面（/chat /profile /dynamics /kb /backend 等）-> /login?redirect=原路径
// - 已登录访问登录 / 注册页 -> 首页
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!to.meta.public && !userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && userStore.isLoggedIn) {
    return { name: 'home' }
  }
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) || '多民族特色医学智能体'
})

export default router
