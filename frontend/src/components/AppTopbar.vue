<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { avatarUrlOf } from '@/api/auth'
import { avatarInitial, avatarPresetOf } from '@/utils/avatar'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 顶栏导航项：名字即路由 name，active 态据此与当前路由比对。 */
const navItems = [
  { name: 'chat', label: '智能对话' },
  { name: 'advanced-search', label: '高级检索' },
  { name: 'kb', label: '知识库' },
  { name: 'dynamics', label: '动态' },
  { name: 'backend', label: '后台' },
  // 需求分析是管理端页面，但导航项不做隐藏：与「后台」同为管理员工具，
  // 真正的闸门在服务端（/api/gaps 对非管理员返回 403），页内会显示「仅管理员可访问」。
  { name: 'demand', label: '需求分析' },
  // 全站唯一无需登录的页面：未登录也能进，所以这条顶栏要能招待访客（见下面的 nav-actions）
  { name: 'feedback', label: '用户反馈' },
]

const activeName = computed(() => String(route.name ?? ''))
const displayName = computed(() => userStore.displayName || '用户')
const avatarUrl = computed(() => avatarUrlOf(userStore.user))
const avatarStyle = computed(() => {
  const p = avatarPresetOf(userStore.user?.avatar)
  return { background: `linear-gradient(150deg, ${p.from}, ${p.to})` }
})

function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout().then(() => {
      ElMessage.success('已退出登录')
      router.push({ name: 'home' })
    })
  } else if (cmd === 'home') {
    router.push({ name: 'home' })
  } else if (cmd === 'profile') {
    router.push({ name: 'profile' })
  }
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand" @click="router.push({ name: 'home' })">
        <span class="logo">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
        <span class="brand-name">多民族特色医学智能体</span>
        <span class="brand-sub">· 精准用药问答</span>
      </div>

      <div class="nav-right">
        <nav class="nav-links">
          <button
            v-for="n in navItems"
            :key="n.name"
            class="nav-btn"
            :class="{ active: activeName === n.name }"
            type="button"
            @click="router.push({ name: n.name })"
          >{{ n.label }}</button>
        </nav>

        <el-dropdown v-if="userStore.isLoggedIn" @command="handleCommand">
          <span class="user-chip">
            <span class="user-avatar" :style="avatarStyle">
              <img v-if="avatarUrl" :src="avatarUrl" :alt="displayName" />
              <span v-else>{{ avatarInitial(displayName) }}</span>
            </span>
            <span class="user-name">{{ displayName }}</span>
            <span class="caret">▾</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="home">返回首页</el-dropdown-item>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <!-- 未登录：这条顶栏是给公开页面（用户反馈）用的，那里必须能招待访客。
             导航项照旧全列——点需要登录的项会被路由守卫送去登录页，那是正常行为。 -->
        <div v-else class="nav-actions">
          <button class="btn btn-ghost" type="button" @click="router.push({ name: 'login' })">登录</button>
          <button class="btn btn-outline" type="button" @click="router.push({ name: 'register' })">注册</button>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 50;
  background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
}
.topbar-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}.logo {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(150deg, var(--sage), var(--sage-deep));
  display: grid;
  place-items: center;
  color: #fff;
}
.brand-name {
  font-family: var(--serif);
  font-weight: 700;
  font-size: 16px;
}
.brand-sub {
  color: var(--ink-3);
  font-size: 12.5px;
}
.nav-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

/* 未登录时的登录 / 注册：形制与首页顶栏的 .nav-actions 一致 */
.nav-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 导航：文字链接 + 当前页下划线（shadcn 式），不再是胶囊按钮 */
.nav-links {
  display: flex;
  align-items: center;
  gap: 4px;
}
.nav-btn {
  position: relative;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 14px;
  color: var(--ink-2);
  padding: 6px 12px;
  border-radius: var(--r-xs);
  transition: color 0.15s;
}
.nav-btn::after {
  content: "";
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 2px;
  height: 2px;
  border-radius: 2px;
  background: var(--clay);
  transform: scaleX(0);
  transform-origin: center;
  transition: transform 0.2s ease;
}
.nav-btn:hover { color: var(--clay-deep); }
.nav-btn:hover::after { transform: scaleX(1); }
.nav-btn.active { color: var(--clay-deep); font-weight: 600; }
.nav-btn.active::after { transform: scaleX(1); }

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 13.5px;
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 12px 4px 5px;
  outline: none;
  transition: border-color 0.15s, color 0.15s;
}
.user-chip:hover {
  border-color: var(--clay);
  color: var(--clay-deep);
}
.user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
}
.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.user-name {
  max-width: 8em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.caret {
  font-size: 11px;
  color: var(--ink-3);
}
</style>
