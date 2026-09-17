import { defineStore } from 'pinia'
import {
  login as loginApi,
  logout as logoutApi,
  me as meApi,
  register as registerApi,
  updateProfile as updateProfileApi,
  uploadAvatar as uploadAvatarApi,
} from '@/api/auth'
import { useAnalysisStore } from './analysis'
import { useAdvancedSearchStore } from './advancedSearch'
import type { UserInfo, UpdateProfilePayload } from '@/api/auth'

const TOKEN_KEY = 'multiethnic_token'
const USER_KEY = 'multiethnic_user'

/**
 * 用户登录态：token + 用户信息，持久化到 localStorage
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null') as UserInfo | null,
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    displayName: (state) => state.user?.nickname || state.user?.username || '',
  },

  actions: {
    persist() {
      if (this.token) {
        localStorage.setItem(TOKEN_KEY, this.token)
      } else {
        localStorage.removeItem(TOKEN_KEY)
      }
      if (this.user) {
        localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      } else {
        localStorage.removeItem(USER_KEY)
      }
    },

    /** 清空与账号绑定的前台工作状态（当前问题草稿 + 分析结果缓存），避免跨账号残留 */
    clearWorkingState() {
      try {
        localStorage.removeItem('mmx_question_v2')
      } catch {
        /* ignore */
      }
      useAnalysisStore().reset()
      // 高级检索页的状态同样是与账号绑定的（检索记录、词表都按登录用户拉），一并清掉
      useAdvancedSearchStore().reset()
    },

    async login(username: string, password: string) {
      const res = await loginApi({ username, password })
      this.clearWorkingState()
      this.token = res.token
      this.user = res.user
      this.persist()
    },

    async register(username: string, password: string, nickname?: string) {
      const res = await registerApi({ username, password, nickname })
      this.clearWorkingState()
      this.token = res.token
      this.user = res.user
      this.persist()
    },

    /** 应用启动时校验/刷新当前用户信息（token 失效会走 401 统一处理） */
    async fetchUser() {
      if (!this.token) return
      try {
        this.user = await meApi()
        this.persist()
      } catch {
        /* 401 已在拦截器统一处理 */
      }
    },

    /** 保存个人资料，并同步本地用户信息（顶栏显示名等会立即跟着变） */
    async saveProfile(data: UpdateProfilePayload) {
      const updated = await updateProfileApi(data)
      this.user = updated
      this.persist()
      return updated
    },

    /** 上传自定义头像，并同步本地用户信息 */
    async uploadAvatar(file: File) {
      const updated = await uploadAvatarApi(file)
      this.user = updated
      this.persist()
      return updated
    },

    async logout() {
      try {
        await logoutApi()
      } catch {
        /* 服务端注销失败也继续清理本地 */
      }
      this.clearLocal()
    },

    clearLocal() {
      this.clearWorkingState()
      this.token = ''
      this.user = null
      this.persist()
    },
  },
})
