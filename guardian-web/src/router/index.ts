import { createRouter, createWebHistory } from 'vue-router'
import { getToken, isTokenExpired, clearAuth } from '@/utils/storage'
import SetupView from '@/views/SetupView.vue'
import DashboardView from '@/views/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/setup',
      component: SetupView,
    },
    {
      path: '/',
      component: DashboardView,
      meta: { requiresAuth: true },
    },
    // 兜底：未知路径重定向首页
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 导航守卫：未登录或 token 过期时强制跳到 /setup
router.beforeEach((to) => {
  if (to.meta.requiresAuth) {
    if (!getToken() || isTokenExpired()) {
      clearAuth()
      return '/setup'
    }
  }
})

export default router
