<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/**
 * 登录 / 注册共用一个组件（对应 /login、/register 两个路由）：
 * - 模式由当前路由推断：/register 进入即注册模式，/login 进入为登录模式
 * - 切换 Tab 时同步路由（保留 redirect 等 query），实现登录页与注册页互相跳转
 */
const mode = ref<'login' | 'register'>(route.path === '/register' ? 'register' : 'login')
const loading = ref(false)
const formRef = ref<FormInstance>()

watch(mode, (m) => {
  const target = m === 'register' ? '/register' : '/login'
  if (route.path !== target) {
    router.replace({ path: target, query: route.query })
  }
})

const loginForm = reactive({
  username: '',
  password: '',
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 30, message: '用户名长度需在 3~30 个字符之间', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度需在 6~64 个字符之间', trigger: 'blur' },
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback) => {
        if (!value) return callback(new Error('请再次输入密码'))
        if (value !== registerForm.password) return callback(new Error('两次输入的密码不一致'))
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function submit() {
  const form = formRef.value
  if (!form) return
  await form.validate()
  loading.value = true
  try {
    if (mode.value === 'login') {
      await userStore.login(loginForm.username.trim(), loginForm.password)
      ElMessage.success('登录成功')
    } else {
      await userStore.register(
        registerForm.username.trim(),
        registerForm.password,
        registerForm.nickname.trim() || undefined,
      )
      ElMessage.success('注册成功，已自动登录')
    }
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch {
    /* 错误提示已在拦截器中统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <aside class="brand-panel">
      <div class="brand">
        <span class="logo">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
        <span class="brand-name">多民族特色医学智能体</span>
      </div>
      <h1 class="brand-title">让每一次回答，<br />都有<span class="accent">依据可循</span>。</h1>
      <p class="brand-desc">
        面向普通大众的多民族健康与精准用药知识问答平台。基于已整理的多民族研究资料，
        用通俗语言解释民族健康与用药知识，并追溯每一条结论背后的论文与原文证据。
      </p>
      <div class="brand-value">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 6L9 17l-5-5" />
        </svg>
        核心价值：回答 → 证据 → 论文来源，可追溯
      </div>
      <ul class="brand-points">
        <li>问得准 —— 理解民族、疾病与查询意图</li>
        <li>找得到 —— 结构化条件 + 语义检索</li>
        <li>说得清 —— 研究资料转化为通俗回答</li>
        <li>有依据 —— 关键结论关联证据片段</li>
        <li>知边界 —— 证据不足或超范围时明确提示</li>
      </ul>
    </aside>

    <!-- 右侧表单区 -->
    <main class="form-panel">
      <div class="card">
        <div class="card-head">
          <h2>{{ mode === 'login' ? '登录' : '注册新账号' }}</h2>
          <p class="sub">
            {{ mode === 'login' ? '登录后开始民族健康知识问答' : '注册成功后将自动登录' }}
          </p>
        </div>

        <el-tabs v-model="mode" class="mode-tabs">
          <el-tab-pane label="账号登录" name="login" />
          <el-tab-pane label="注册账号" name="register" />
        </el-tabs>

        <!-- 登录表单 -->
        <el-form
          v-if="mode === 'login'"
          ref="formRef"
          :model="loginForm"
          :rules="loginRules"
          label-position="top"
          size="large"
          @keyup.enter="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="loginForm.username" placeholder="请输入用户名" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
            />
          </el-form-item>
          <el-button class="submit" type="primary" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>

        <!-- 注册表单 -->
        <el-form
          v-else
          ref="formRef"
          :model="registerForm"
          :rules="registerRules"
          label-position="top"
          size="large"
          @keyup.enter="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="registerForm.username" placeholder="3~30 个字符" autocomplete="username" />
          </el-form-item>
          <el-form-item label="昵称（可选）" prop="nickname">
            <el-input v-model="registerForm.nickname" placeholder="不填则默认使用用户名" maxlength="30" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="registerForm.password"
              type="password"
              show-password
              placeholder="6~64 个字符"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="registerForm.confirmPassword"
              type="password"
              show-password
              placeholder="请再次输入密码"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-button class="submit" type="primary" :loading="loading" @click="submit">
            注册并登录
          </el-button>
        </el-form>

        <p class="disclaimer">
          本产品仅提供健康与用药知识科普，不构成诊断、治疗或用药建议；
          若有健康问题，请及时就医。
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr 1fr;
}

/* ---------- 左侧品牌区 ---------- */
.brand-panel {
  background: linear-gradient(160deg, var(--sage-deep), var(--sage) 55%, var(--sage));
  color: #fff;
  padding: 56px 64px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 40px;
}
.logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.14);
  display: grid;
  place-items: center;
}
.brand-name {
  font-family: var(--serif);
  font-weight: 700;
  font-size: 17px;
  letter-spacing: 0.02em;
}
.brand-title {
  font-family: var(--serif);
  font-weight: 700;
  font-size: clamp(30px, 3.4vw, 42px);
  line-height: 1.3;
}
.brand-title .accent {
  color: var(--amber-line);
}
.brand-desc {
  margin-top: 18px;
  font-size: 14.5px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.82);
  max-width: 460px;
}
.brand-value {
  margin-top: 22px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.22);
  padding: 10px 16px;
  border-radius: var(--r-md);
  font-size: 14px;
}
.brand-points {
  margin-top: 26px;
  list-style: none;
  display: grid;
  gap: 9px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.78);
}
.brand-points li::before {
  content: "·";
  margin-right: 9px;
  color: var(--amber-line);
  font-weight: 700;
}

/* ---------- 右侧表单区 ---------- */
.form-panel {
  display: grid;
  place-items: center;
  padding: 40px 24px;
  background: var(--bg);
}
.card {
  width: 100%;
  max-width: 420px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-lg);
  padding: 36px 38px 30px;
}
.card-head h2 {
  font-family: var(--serif);
  font-size: 25px;
  font-weight: 700;
}
.card-head .sub {
  margin-top: 5px;
  font-size: 13.5px;
  color: var(--ink-3);
}
.mode-tabs {
  margin-top: 18px;
}
.submit {
  width: 100%;
  height: 44px;
  font-size: 15.5px;
  letter-spacing: 0.35em;
  text-indent: 0.35em;
  margin-top: 4px;
}
.disclaimer {
  margin-top: 22px;
  border-top: 1px solid var(--line);
  padding-top: 14px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-3);
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    padding: 36px 28px;
  }
  .brand-points,
  .brand-value {
    display: none;
  }
}
</style>
