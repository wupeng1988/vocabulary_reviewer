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
        <el-menu-item index="/difficult-words">
          <span>🎯 易错词库</span>
        </el-menu-item>
        <el-sub-menu index="groups">
          <template #title>
            <span style="margin-left: 8px">🗂️ 词组管理</span>
          </template>
          <el-menu-item index="/groups" style="padding-left: 48px !important">
            <span>词组管理</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/data">
          <span>🔧 数据管理</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <span>🔄 复习中心</span>
        </el-menu-item>
        <el-menu-item index="/stats">
          <span>📊 统计看板</span>
        </el-menu-item>
        <el-sub-menu index="settings">
          <template #title>
            <span style="margin-left: 8px">⚙️ 系统设置</span>
          </template>
          <el-menu-item index="/ai" style="padding-left: 48px !important">
            <span>🤖 AI 设置</span>
          </el-menu-item>
          <el-menu-item index="/proxy" style="padding-left: 48px !important">
            <span>🌐 网络代理</span>
          </el-menu-item>
          <el-menu-item index="/difficult" style="padding-left: 48px !important">
            <span>🎯 易错词配置</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <!-- 右侧内容 -->
    <el-main class="main-scroll">
      <!--
        key 必须绑上：词库管理(/words) 和 易错词库(/difficult-words) 挂的是同一个
        WordManage 组件，Vue Router 在「同组件不同路由」之间跳转时会复用组件实例、
        不重新挂载，onMounted 不执行，words/keyword/filterGroupId 等状态原样留着——
        表现就是换个菜单表格还是旧数据、点完搜索再切回去整页空掉。
        绑 fullPath 后路由一变就重建组件，各页面回到干净初始状态。
      -->
      <router-view :key="route.fullPath" />
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
