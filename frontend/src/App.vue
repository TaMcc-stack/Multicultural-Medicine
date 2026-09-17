<script setup lang="ts">
</script>

<template>
  <router-view v-slot="{ Component }">
    <!-- keep-alive 缓存智能对话页，跳转到后台/知识库再返回时不丢输入与回答状态 -->
    <keep-alive include="QaView">
      <component :is="Component" />
    </keep-alive>
  </router-view>
</template>
