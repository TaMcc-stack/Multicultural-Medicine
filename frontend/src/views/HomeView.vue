<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { listHotQuestions } from '@/utils/hotQuestions'

const router = useRouter()
const userStore = useUserStore()

/**
 * 进入智能对话页的**唯一入口**（主按钮与热门问题都走这里）。
 *
 * 带 question 时把它拼进 query，对话页挂载后会读到并自动发起提问——
 * 这样用户点一个热门问题就能直接看到回答，不用自己再敲一遍。
 * 未登录时先登录，把同一个目标塞进 redirect，登录完回到「带着问题的对话页」。
 */
function goChat(question?: string) {
  if (!userStore.isLoggedIn) {
    const redirect = question ? `/chat?q=${encodeURIComponent(question)}` : '/chat'
    router.push({ name: 'login', query: { redirect } })
    return
  }
  router.push(question ? { name: 'chat', query: { q: question } } : { name: 'chat' })
}

/** 首页中央主按钮：开始对话 */
function startConversation() {
  goChat()
}

/** 点热门问题：带着这个问题进对话页，自动提问 */
function askHot(text: string) {
  goChat(text)
}

/** 动态 / 分享主页：未登录先登录，登录后进入 */
function goDynamics() {
  if (!userStore.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: '/dynamics' } })
    return
  }
  router.push({ name: 'dynamics' })
}

/** 热门问题（当前为 Mock，见 utils/hotQuestions.ts） */
const hotQuestions = ref<string[]>([])


function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout().then(() => {
      ElMessage.success('已退出登录')
    })
  } else if (cmd === 'profile') {
    router.push({ name: 'profile' })
  } else if (cmd === 'chat') {
    router.push({ name: 'chat' })
  } else if (cmd === 'dynamics') {
    router.push({ name: 'dynamics' })
  } else if (cmd === 'feedback') {
    router.push({ name: 'feedback' })
  }
}

onMounted(() => {
  userStore.fetchUser()
  listHotQuestions()
    .then((list) => {
      hotQuestions.value = list
    })
    .catch(() => {
      // 拿不到热门问题不影响首页其他部分，静默降级：整块不渲染
      hotQuestions.value = []
    })
})
</script>

<template>
  <div class="page">
    <!-- 顶栏：未登录显示登录/注册，已登录显示用户菜单 -->
    <header class="topbar">
      <div class="topbar-inner">
        <div class="brand">
          <span class="logo">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </span>
          <span class="brand-name">多民族特色医学智能体</span>
          <span class="brand-sub">· 多民族健康与精准用药知识问答</span>
        </div>

        <div v-if="!userStore.isLoggedIn" class="nav-actions">
          <button class="btn btn-ghost" type="button" @click="goDynamics">动态</button>
          <!-- 用户反馈是唯一无需登录的业务页面，访客也放行 -->
          <button class="btn btn-ghost" type="button" @click="router.push({ name: 'feedback' })">用户反馈</button>
          <button class="btn btn-ghost" type="button" @click="router.push({ name: 'login' })">登录</button>
          <!-- 注册比登录略强一档，但仍低于英雄区的「开始对话」——一屏只留一个实心主按钮 -->
          <button class="btn btn-outline" type="button" @click="router.push({ name: 'register' })">注册</button>
        </div>

        <el-dropdown v-else @command="handleCommand">
          <span class="user-chip">
            <el-icon class="avatar"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/></svg></el-icon>
            {{ userStore.displayName || '用户' }}
            <span class="caret">▾</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="chat">智能对话</el-dropdown-item>
              <el-dropdown-item command="dynamics">动态 / 分享</el-dropdown-item>
              <el-dropdown-item command="feedback">用户反馈</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 主体 -->
    <section class="hero">
      <span class="eyebrow">多民族精准用药 · 证据溯源</span>
      <h1 class="hero-title">让每一次回答，<br />都有<span class="accent">依据可循</span>。</h1>
      <p class="hero-pos">
        想知道你所在民族的高发疾病和用药注意事项？直接提问，每个回答都能追溯到权威文献。
      </p>

      <!-- 中央主按钮：开始对话 -->
      <div class="cta">
        <button class="btn btn-solid btn-lg btn-start" type="button" @click="startConversation">
          <span>开始对话</span>
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h14M13 6l6 6-6 6" />
          </svg>
        </button>
        <!-- 按钮下方刻意不写提示：CTA 本身已经说清了要做什么，
             再解释一遍「点了会进入对话页」是废话。未登录时点下去会走登录流程，
             那一步自己会说明，不需要提前打预防针。 -->
      </div>
    </section>

    <!-- 热门问题：把「下一步该干嘛」直接摆出来。
         点一条就带着这个问题进对话页并自动提问——比让用户自己组织语言门槛低得多。 -->
    <section v-if="hotQuestions.length" class="hot">
      <div class="section-head">
        <span class="section-headline">大家在问</span>
        <h2 class="section-title">热门问题</h2>
        <p class="section-desc">点一下直接开始提问</p>
      </div>

      <div class="hot-list">
        <button
          v-for="q in hotQuestions"
          :key="q"
          class="hot-item"
          type="button"
          @click="askHot(q)"
        >
          <span class="hot-text">{{ q }}</span>
          <svg class="hot-arrow" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h14M13 6l6 6-6 6" />
          </svg>
        </button>
      </div>
    </section>

    <!-- 页脚 -->
    <footer>
      <div class="foot-disclaimer">
        <b>医疗边界声明：</b>本产品仅提供健康与用药知识科普，不构成任何诊断、治疗或用药建议；
        不能替代执业医师的专业判断。若有健康问题，请及时就医。
      </div>
      <div class="foot-meta">
        <span class="brand">多民族特色医学智能体</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; flex-direction: column; }

/* ---------- 顶栏 ---------- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 50;
  background: color-mix(in srgb, var(--bg) 86%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
}
.topbar-inner {
  max-width: 1060px;
  margin: 0 auto;
  padding: 0 24px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.brand { display: flex; align-items: center; gap: 11px; }
.logo {
  width: 34px; height: 34px; border-radius: 9px; flex: none;
  background: linear-gradient(150deg, var(--sage), var(--sage-deep));
  display: grid; place-items: center; color: #fff; box-shadow: var(--shadow-sm);
}
.brand-name { font-family: var(--serif); font-weight: 700; font-size: 16.5px; }
.brand-sub { color: var(--ink-3); font-size: 13px; }

/* 未登录：登录 / 注册按钮（基础样式来自 main.css 的 .btn 分级） */
.nav-actions { display: flex; align-items: center; gap: 10px; }

/* 已登录：用户菜单 */
.user-chip {
  display: inline-flex; align-items: center; gap: 8px;
  cursor: pointer; font-size: 14px; color: var(--ink-2);
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 999px; padding: 6px 14px; outline: none;
}
.user-chip:hover { border-color: var(--clay); color: var(--clay-deep); }
.avatar { font-size: 16px; color: var(--sage); }
.caret { font-size: 11px; color: var(--ink-3); }

/* ---------- Hero ---------- */
/* 标题收小一档、上下留白加大：这一屏的主角是「开始对话」这个动作，不是标题本身 */
.hero { text-align: center; padding: 80px 24px 72px; }
.eyebrow {
  display: inline-flex; align-items: center; gap: 8px;
  font-size: 12.5px; font-weight: 500; letter-spacing: 0.12em;
  color: var(--sage-deep); text-transform: uppercase;
  background: var(--sage-soft); border: 1px solid var(--sage-line);
  border-radius: 999px; padding: 5px 14px;
}
.eyebrow::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: var(--sage); }
.hero-title {
  font-family: var(--serif); font-weight: 700;
  font-size: clamp(28px, 4vw, 42px); line-height: 1.26;
  margin-top: 12px;
}
.hero-title .accent { color: var(--clay); }
.hero-pos {
  margin: 18px auto 0; max-width: 640px;
  color: var(--ink-2); font-size: 16px;
}

/* 开始对话主按钮：形制沿用 .btn .btn-solid .btn-lg，这里只加「更大一号」和离开纸面的阴影 */
.cta { margin-top: 30px; display: grid; justify-items: center; gap: 10px; }
.btn-start {
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  padding: 14px 34px;
  background: linear-gradient(135deg, var(--clay), var(--clay-deep));
  box-shadow: 0 14px 30px -16px rgba(70, 50, 30, 0.45);
}
.btn-start:hover:not(:disabled) {
  transform: translateY(-2px);
  background: linear-gradient(135deg, var(--clay-deep), var(--clay-deep));
  box-shadow: 0 18px 36px -16px rgba(70, 50, 30, 0.5);
}
.btn-start:active { transform: translateY(0); }

/* ---------- 热门问题 ---------- */
/* 用比 hero 深一档的底色分出段落，不用阴影——这个页面靠留白和描边分层 */
.hot {
  padding: 60px 24px 76px;
  background: var(--bg-deep);
  border-top: 1px solid var(--line);
}
/* 三段式：小标题 → 大标题 → 说明。层级靠字号和颜色拉开，不靠加粗堆叠 */
.section-head { max-width: 660px; margin: 0 auto 40px; text-align: center; }
.section-headline {
  display: inline-block;
  font-size: var(--fs-sm);
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--sage-deep);
  text-transform: uppercase;
}
.section-title {
  font-family: var(--serif);
  font-weight: 700;
  font-size: clamp(24px, 3.2vw, 32px);
  line-height: 1.3;
  color: var(--ink);
  margin-top: 10px;
}
.section-desc {
  margin-top: 14px;
  font-size: var(--fs-lg);
  color: var(--ink-2);
  line-height: 1.85;
}

/* 问题平铺：每张卡一整条可点，比一排 chip 更好点、也放得下完整问句 */
.hot-list {
  max-width: 880px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}
.hot-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  text-align: left;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 15px 18px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  font-family: var(--sans);
  transition: border-color 0.18s, box-shadow 0.18s, transform 0.18s;
}
.hot-item:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.hot-text { font-size: var(--fs-lg); color: var(--ink); line-height: 1.5; }
/* 箭头平时压暗，悬停时点亮并右移——暗示「点了会往前走一步」 */
.hot-arrow {
  flex: none;
  color: var(--ink-3);
  transition: color 0.18s, transform 0.18s;
}
.hot-item:hover .hot-arrow { color: var(--clay); transform: translateX(3px); }

/* ---------- 页脚 ---------- */
footer { margin-top: auto; border-top: 1px solid var(--line); padding: 32px 24px 44px; }
.foot-disclaimer {
  max-width: 1060px; margin: 0 auto; font-size: 13px; color: var(--ink-3); line-height: 1.85;
}
.foot-disclaimer b { color: var(--ink-2); }
.foot-meta {
  max-width: 1060px; margin: 18px auto 0;
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; flex-wrap: wrap; font-size: 12.5px; color: var(--ink-3);
}
.foot-meta .brand { font-family: var(--serif); font-weight: 700; font-size: 14px; color: var(--ink); }
</style>
