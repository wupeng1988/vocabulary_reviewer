<template>
  <el-container style="height: 100vh">
    <!-- 左侧菜单 -->
    <el-aside :width="collapsed ? '64px' : '200px'" class="sidebar" style="background-color: #304156">
      <!-- 顶部：标题 + 折叠图标按钮（收起后标题隐藏，只留按钮） -->
      <div class="sidebar-header">
        <span v-show="!collapsed" class="sidebar-title">📖 单词复习</span>
        <el-tooltip :content="collapsed ? '展开菜单' : '收起菜单'" placement="right">
          <el-button
            class="collapse-btn"
            link
            @click="collapsed = !collapsed"
          >
            <!-- 用组件对象而不是字符串名：字符串形式只能解析全局注册的组件，
                 而 Expand/Fold 是 import 进来的局部组件，用字符串会渲染成空
                 （折叠后按钮就变成一个看不见的空圆点） -->
            <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <el-menu
        :default-active="currentRoute"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        router
      >
        <el-menu-item index="/words">
          <el-icon><Reading /></el-icon>
          <template #title>词库管理</template>
        </el-menu-item>
        <el-menu-item index="/difficult-words">
          <el-icon><Aim /></el-icon>
          <template #title>易错词库</template>
        </el-menu-item>
        <el-sub-menu index="groups" :collapse-close-icon="Folder" :collapse-open-icon="Folder">
          <template #title>
            <el-icon><Folder /></el-icon>
            <span v-show="!collapsed">词组管理</span>
          </template>
          <el-menu-item index="/groups" style="padding-left: 48px !important">
            <span>词组管理</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/data">
          <el-icon><Tools /></el-icon>
          <template #title>数据管理</template>
        </el-menu-item>
        <el-menu-item index="/review">
          <el-icon><Refresh /></el-icon>
          <template #title>复习中心</template>
        </el-menu-item>
        <el-menu-item index="/stats">
          <el-icon><DataLine /></el-icon>
          <template #title>统计看板</template>
        </el-menu-item>
        <el-sub-menu index="settings" :collapse-close-icon="Setting" :collapse-open-icon="Setting">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span v-show="!collapsed">系统设置</span>
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
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Expand, Fold, Reading, Aim, Folder, Tools, Refresh, DataLine, Setting } from '@element-plus/icons-vue'

const route = useRoute()
const currentRoute = computed(() => route.path)

// 左侧菜单收起状态。表格列多（本项目 13 列以上），收起后右侧多出 136px，
// 少一列横向滚动
const collapsed = ref(false)
</script>

<style scoped>
.el-aside {
  overflow: hidden;
  /* 改成竖排 flex：菜单在上、折叠开关吸底，否则开关会被长菜单顶出可视区 */
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
}
.el-aside .el-menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}
/* 收起状态下 el-menu 加 .el-menu--collapse：Element Plus 自己会隐藏 slot 里的文字，
   只保留 <el-icon>。这里只调内边距让图标居中——
   千万别再用 .el-menu--collapse span { display:none } 这类通配规则，
   那会把图标一起藏掉（图标写在 el-icon 里，但 emoji 图标曾直接放在 span 中，会被误伤）。 */
.el-aside :deep(.el-menu--collapse .el-menu-item),
.el-aside :deep(.el-menu--collapse .el-sub-menu__title) {
  padding-left: 0 !important;
  padding-right: 0 !important;
  justify-content: center;
}
.el-aside :deep(.el-menu--collapse .el-sub-menu .el-menu) {
  padding-left: 0 !important;
}
.el-aside :deep(.el-menu--collapse .el-sub-menu__title .el-icon) {
  margin-right: 0;
}
.el-aside :deep(.el-menu--collapse .el-menu-item .el-icon),
.el-aside :deep(.el-menu--collapse .el-sub-menu__title > .el-icon) {
  margin-right: 0;
}

/* 顶部标题栏：标题 + 折叠按钮 */
.sidebar-header {
  flex-shrink: 0;
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  gap: 8px;
  border-bottom: 1px solid #3d4f63;
}
.sidebar-title {
  flex: 1;
  text-align: center;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  white-space: nowrap;
}
.collapse-btn {
  flex-shrink: 0;
  /* el-button 的 text/link 变体有自己的 --el-button-text-color 变量，优先级高于
     外部继承的 color，直接在按钮上写 color 会被盖掉、图标就看不见了。
     所以色值作用到内部的 .el-icon 上，并清掉按钮自身的文字色变量。 */
  --el-button-text-color: #bfcbd9;
  --el-button-hover-text-color: #fff;
  --el-button-hover-bg-color: #263445;
  font-size: 18px;
}
.collapse-btn :deep(.el-icon) {
  color: #bfcbd9;
  font-size: 18px;
}
.collapse-btn:hover :deep(.el-icon) {
  color: #fff;
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
