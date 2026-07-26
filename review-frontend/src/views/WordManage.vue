<template>
  <div>
    <!-- 搜索栏 - sticky 固定顶部 -->
    <el-card class="sticky-search">
      <el-row :gutter="20" align="middle">
        <el-col :span="8">
          <el-input
            v-model="keyword"
            placeholder="搜索生词或含义..."
            clearable
            @keyup.enter="loadWords"
            @clear="loadWords"
          >
            <template #prefix>
              <el-icon><search /></el-icon>
            </template>
          </el-input>
        </el-col>
        <el-col :span="4">
          <el-button type="primary" @click="loadWords">搜索</el-button>
        </el-col>
        <el-col :span="12" style="text-align: right; display: flex; align-items: center; justify-content: flex-end; gap: 10px">
          <el-button :type="hideMeaning ? 'warning' : 'default'" size="small" @click="hideMeaning = !hideMeaning">
            {{ hideMeaning ? '显示含义' : '隐藏含义' }}
          </el-button>
          <el-button :type="hideExampleMeaning ? 'warning' : 'default'" size="small" @click="hideExampleMeaning = !hideExampleMeaning">
            {{ hideExampleMeaning ? '显示例句释义' : '隐藏例句释义' }}
          </el-button>
          <span style="color: #909399">共 {{ totalElements }} 个单词</span>
        </el-col>
      </el-row>
    </el-card>

    <!-- 词库表格 -->
    <el-card>
      <el-table :data="words" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="word" label="生词" width="150" />
        <el-table-column prop="phonetic" label="音标" width="180" />
        <el-table-column prop="meaning" label="含义" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!hideMeaning">{{ row.meaning }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column prop="exampleSentence" label="例句" min-width="250" show-overflow-tooltip />
        <el-table-column prop="exampleMeaning" label="例句释义" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!hideExampleMeaning">{{ row.exampleMeaning }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column label="复习次数" width="90" align="center">
          <template #default="{ row }">{{ row.reviewTotal }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isDormant ? 'info' : 'success'" size="small">
              {{ row.isDormant ? '休眠' : '活跃' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="仅认读" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.recogOnly"
              size="small"
              @change="toggleRecogOnly(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-popconfirm title="确定删除该单词？" @confirm="deleteWord(row.id)">
              <template #reference>
                <el-button type="danger" size="small" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="margin-top: 20px; display: flex; justify-content: center">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="totalElements"
          :page-sizes="[20, 50, 100, 200, 500, 1000]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadWords"
          @current-change="loadWords"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const words = ref([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)
const hideMeaning = ref(false)
const hideExampleMeaning = ref(false)

const loadWords = async () => {
  try {
    const res = await api.get('/words/page', {
      params: {
        page: currentPage.value,
        size: pageSize.value,
        keyword: keyword.value || undefined
      }
    })
    words.value = res.content
    totalElements.value = res.totalElements
  } catch (e) {
    ElMessage.error('加载词库失败')
  }
}

const deleteWord = async (id) => {
  try {
    await api.delete(`/words/${id}`)
    ElMessage.success('删除成功')
    loadWords()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const toggleRecogOnly = async (row) => {
  try {
    await api.put(`/words/${row.id}/recog-only`, { recogOnly: row.recogOnly })
    ElMessage.success(row.recogOnly ? '已设为仅认读' : '已取消仅认读')
  } catch (e) {
    // 回滚开关状态
    row.recogOnly = !row.recogOnly
    ElMessage.error('更新失败')
  }
}

onMounted(() => {
  loadWords()
})
</script>

<style scoped>
.sticky-search {
  position: sticky;
  top: 0;
  z-index: 10;
  margin-bottom: 20px;
}
</style>
