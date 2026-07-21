<template>
  <el-container style="height: 100vh">
    <!-- 左侧菜单 -->
    <el-aside width="200px" style="background-color: #304156">
      <div style="padding: 20px 0; text-align: center; color: #fff; font-size: 18px; font-weight: bold">
        📖 单词复习
      </div>
      <el-menu
        :default-active="currentRoute"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        router
      >
        <el-menu-item index="/words">
          <span>📚 词库管理</span>
        </el-menu-item>
        <el-menu-item index="/data">
          <span>🔧 数据管理</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <span>🔄 复习中心</span>
        </el-menu-item>
        <el-menu-item index="/stats">
          <span>📊 统计看板</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧内容 -->
    <el-main class="main-scroll">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const currentRoute = computed(() => route.path)
</script>

<style scoped>
.el-aside {
  overflow: hidden;
}
.el-menu-item {
  font-size: 15px;
}
.el-menu-item span {
  margin-left: 8px;
}
.main-scroll {
  background-color: #f0f2f5;
  padding: 20px;
  padding-top: 0px;
  overflow-y: auto;
}
</style>
