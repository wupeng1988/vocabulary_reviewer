<template>
  <div>
    <!-- 搜索栏 - sticky 固定顶部 -->
    <el-card class="sticky-search">
      <el-row :gutter="20" align="middle">
        <el-col :span="5">
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
        <el-col :span="7">
          <el-button size="small" type="primary" @click="loadWords">搜索</el-button>
          <el-button size="small" type="success" @click="openAddDialog">➕ 新增</el-button>
          <el-button
            size="small"
            :type="batchRunning ? 'warning' : 'success'"
            :loading="batchChecking"
            @click="handleAudioButton"
          >
            {{ batchRunning ? '音频生成中...' : selectedWords.length > 0 ? '🔊 重新生成音频' : '🔊 生成音频' }}
          </el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="selectedWords.length === 0"
            @click="openReviewDialog"
          >
            📖 复习 ({{ selectedWords.length }})
          </el-button>
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
      <el-table ref="wordTableRef" :data="words" stripe border style="width: 100%" @selection-change="handleSelectionChange" @sort-change="handleSortChange">
        <el-table-column type="selection" width="50" align="center" />
        <el-table-column prop="id" label="ID" width="70" align="center" sortable="custom" />
        <el-table-column prop="word" label="生词" width="150" />
        <el-table-column prop="phonetic" label="音标" width="200">
          <template #default="{ row }">
            <el-button link size="small" @click="playWordAudio(row.word)" style="margin-right: 4px">
              🔊
            </el-button>
            {{ row.phonetic }}
          </template>
        </el-table-column>
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
        <el-table-column prop="reviewTotal" label="复习次数" width="110" align="center" sortable="custom">
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
        <el-table-column label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="openEditDialog(row)">编辑</el-button>
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

    <!-- 编辑弹窗 -->
    <el-dialog v-model="showEditDialog" title="编辑单词" width="550px" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="生词" required>
          <el-input v-model="editForm.word" placeholder="英文单词" />
        </el-form-item>
        <el-form-item label="音标">
          <el-input v-model="editForm.phonetic" placeholder="/ˈeksəmpəl/" />
        </el-form-item>
        <el-form-item label="含义" required>
          <el-input v-model="editForm.meaning" type="textarea" :rows="2" placeholder="中文含义" />
        </el-form-item>
        <el-form-item label="例句">
          <el-input v-model="editForm.exampleSentence" type="textarea" :rows="2" placeholder="英文例句" />
        </el-form-item>
        <el-form-item label="例句释义">
          <el-input v-model="editForm.exampleMeaning" type="textarea" :rows="2" placeholder="例句的中文翻译" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增弹窗 -->
    <el-dialog v-model="showAddDialog" title="新增单词" width="550px" :close-on-click-modal="false">
      <el-form :model="addForm" label-width="90px">
        <el-form-item label="生词" required>
          <el-input v-model="addForm.word" placeholder="英文单词" />
        </el-form-item>
        <el-form-item label="音标">
          <el-input v-model="addForm.phonetic" placeholder="/ˈeksəmpəl/" />
        </el-form-item>
        <el-form-item label="含义" required>
          <el-input v-model="addForm.meaning" type="textarea" :rows="2" placeholder="中文含义" />
        </el-form-item>
        <el-form-item label="例句">
          <el-input v-model="addForm.exampleSentence" type="textarea" :rows="2" placeholder="英文例句" />
        </el-form-item>
        <el-form-item label="例句释义">
          <el-input v-model="addForm.exampleMeaning" type="textarea" :rows="2" placeholder="例句的中文翻译" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="addSaving" @click="saveAdd">添加</el-button>
      </template>
    </el-dialog>

    <!-- 复习阶段选择弹窗 -->
    <el-dialog v-model="showReviewDialog" title="📖 选择复习阶段" width="360px" :close-on-click-modal="false">
      <div style="padding: 10px 0">
        <p style="margin-bottom: 16px; color: #606266">已选择 <strong>{{ selectedWords.length }}</strong> 个单词，请选择复习阶段：</p>
        <div style="display: flex; flex-direction: column; gap: 16px">
          <el-checkbox v-model="reviewPhases.recog" size="large" border style="padding: 12px 16px; width: 298px">
            <span style="font-size: 16px">📖 认读</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">英→中，看词识义</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.practice" size="large" border style="padding: 12px 16px; width: 298px">
            <span style="font-size: 16px">✏️ 拼写练习</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">显示信息+拼写</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.spell" size="large" border style="padding: 12px 16px; width: 298px">
            <span style="font-size: 16px">✍️ 默写</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">中→英，看义写词</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.dictation" size="large" border style="padding: 12px 16px; width: 298px">
            <span style="font-size: 16px">🎧 听音默写</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">仅凭音频拼写</span>
          </el-checkbox>
        </div>
      </div>
      <template #footer>
        <el-button @click="showReviewDialog = false">取消</el-button>
        <el-button type="primary" @click="startSelectedReview">开始复习</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import api from '../api'

const router = useRouter()
const words = ref([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(50)
const totalElements = ref(0)
const hideMeaning = ref(false)
const hideExampleMeaning = ref(false)
const batchRunning = ref(false)
const batchChecking = ref(false)

// 排序状态
const sortField = ref('id')
const sortOrder = ref('desc')

const handleSortChange = ({ prop, order }) => {
  if (prop && order) {
    sortField.value = prop
    sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  } else {
    // 取消排序时恢复默认
    sortField.value = 'id'
    sortOrder.value = 'desc'
  }
  loadWords()
}

// 表格多选
const wordTableRef = ref(null)
const selectedWords = ref([])

const handleSelectionChange = (selection) => {
  selectedWords.value = selection
}

// 复习弹窗
const showReviewDialog = ref(false)
const reviewPhases = ref({ recog: true, practice: true, spell: true, dictation: true })

const openReviewDialog = () => {
  if (selectedWords.value.length === 0) {
    ElMessage.warning('请先选择要复习的单词')
    return
  }
  // 重置为全部选中
  reviewPhases.value = { recog: true, practice: true, spell: true, dictation: true }
  showReviewDialog.value = true
}

const startSelectedReview = () => {
  const phases = []
  if (reviewPhases.value.recog) phases.push('recog')
  if (reviewPhases.value.practice) phases.push('practice')
  if (reviewPhases.value.spell) phases.push('spell')
  if (reviewPhases.value.dictation) phases.push('dictation')
  if (phases.length === 0) {
    ElMessage.warning('请至少选择一个复习阶段')
    return
  }
  const ids = selectedWords.value.map(w => w.id).join(',')
  showReviewDialog.value = false
  router.push({ path: '/review', query: { ids, phases: phases.join(',') } })
}

// 轮询批量生成状态
let batchPollTimer = null

const checkBatchStatus = async () => {
  try {
    const res = await api.get('/audio/batch/status')
    batchRunning.value = res.status === 'running'
    if (batchRunning.value && !batchPollTimer) {
      // 任务在运行，启动轮询
      batchPollTimer = setInterval(async () => {
        try {
          const r = await api.get('/audio/batch/status')
          batchRunning.value = r.status === 'running'
          if (!batchRunning.value) {
            clearInterval(batchPollTimer)
            batchPollTimer = null
            ElMessage.success('批量音频生成已完成')
          }
        } catch {
          clearInterval(batchPollTimer)
          batchPollTimer = null
          batchRunning.value = false
        }
      }, 5000)
    }
  } catch {
    batchRunning.value = false
  }
}

const triggerBatchGeneration = async () => {
  batchChecking.value = true
  try {
    const res = await api.post('/audio/batch')
    if (res.status === 'running') {
      ElMessage.info('批量生成任务正在执行中，请稍候')
    } else {
      ElMessage.success('批量音频生成任务已启动')
    }
    batchRunning.value = true
    // 启动轮询
    if (!batchPollTimer) {
      batchPollTimer = setInterval(async () => {
        try {
          const r = await api.get('/audio/batch/status')
          batchRunning.value = r.status === 'running'
          if (!batchRunning.value) {
            clearInterval(batchPollTimer)
            batchPollTimer = null
            ElMessage.success('批量音频生成已完成')
          }
        } catch {
          clearInterval(batchPollTimer)
          batchPollTimer = null
          batchRunning.value = false
        }
      }, 5000)
    }
  } catch {
    ElMessage.error('启动批量生成失败')
  }
  batchChecking.value = false
}

// 音频按钮：有选中词则重新生成，否则批量生成缺失音频
const handleAudioButton = () => {
  if (selectedWords.value.length > 0) {
    regenerateSelectedAudio()
  } else {
    triggerBatchGeneration()
  }
}

const regenerateSelectedAudio = async () => {
  const words = selectedWords.value.map(w => w.word)
  batchChecking.value = true
  try {
    const res = await api.post('/audio/regenerate', { words })
    if (res.success) {
      ElMessage.success(res.message)
    } else {
      ElMessage.error(res.message || '重新生成失败')
    }
  } catch (e) {
    ElMessage.error('重新生成音频失败')
  }
  batchChecking.value = false
}

const loadWords = async () => {
  try {
    const res = await api.get('/words/page', {
      params: {
        page: currentPage.value,
        size: pageSize.value,
        keyword: keyword.value || undefined,
        sortField: sortField.value,
        sortOrder: sortOrder.value
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

// ==================== 编辑 ====================
const showEditDialog = ref(false)
const editSaving = ref(false)
const editForm = ref({
  id: null,
  word: '',
  phonetic: '',
  meaning: '',
  exampleSentence: '',
  exampleMeaning: ''
})

const openEditDialog = (row) => {
  editForm.value = {
    id: row.id,
    word: row.word,
    phonetic: row.phonetic || '',
    meaning: row.meaning || '',
    exampleSentence: row.exampleSentence || '',
    exampleMeaning: row.exampleMeaning || ''
  }
  showEditDialog.value = true
}

const saveEdit = async () => {
  if (!editForm.value.word.trim()) {
    ElMessage.warning('生词不能为空')
    return
  }
  if (!editForm.value.meaning.trim()) {
    ElMessage.warning('含义不能为空')
    return
  }
  editSaving.value = true
  try {
    await api.put(`/words/${editForm.value.id}`, {
      word: editForm.value.word,
      phonetic: editForm.value.phonetic,
      meaning: editForm.value.meaning,
      exampleSentence: editForm.value.exampleSentence,
      exampleMeaning: editForm.value.exampleMeaning
    })
    ElMessage.success('保存成功')
    showEditDialog.value = false
    loadWords()
  } catch (e) {
    const msg = e?.response?.data?.message || '保存失败'
    ElMessage.error(msg)
  }
  editSaving.value = false
}

// ==================== 音频播放 ====================
let currentAudio = null

const playWordAudio = async (word) => {
  if (!word) return
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
  const audioUrl = `/api/audio/${word.trim().toLowerCase()}`
  try {
    const audio = new Audio(audioUrl)
    currentAudio = audio
    await audio.play()
    return
  } catch (e) {
    currentAudio = null
  }
  // 轮询等待音频就绪
  try {
    for (let i = 0; i < 20; i++) {
      await new Promise(r => setTimeout(r, 500))
      const res = await fetch(`${audioUrl}/status`)
      const data = await res.json()
      if (data.status === 'ready') {
        const audio = new Audio(audioUrl)
        currentAudio = audio
        await audio.play()
        return
      }
    }
  } catch (e) {
    // 静默忽略
  }
}

// ==================== 新增 ====================
const showAddDialog = ref(false)
const addSaving = ref(false)
const addForm = ref({
  word: '',
  phonetic: '',
  meaning: '',
  exampleSentence: '',
  exampleMeaning: ''
})

const openAddDialog = () => {
  addForm.value = {
    word: '',
    phonetic: '',
    meaning: '',
    exampleSentence: '',
    exampleMeaning: ''
  }
  showAddDialog.value = true
}

const saveAdd = async () => {
  if (!addForm.value.word.trim()) {
    ElMessage.warning('生词不能为空')
    return
  }
  if (!addForm.value.meaning.trim()) {
    ElMessage.warning('含义不能为空')
    return
  }
  addSaving.value = true
  try {
    await api.post('/words/add', {
      word: addForm.value.word,
      phonetic: addForm.value.phonetic,
      meaning: addForm.value.meaning,
      exampleSentence: addForm.value.exampleSentence,
      exampleMeaning: addForm.value.exampleMeaning
    })
    ElMessage.success('添加成功')
    showAddDialog.value = false
    loadWords()
  } catch (e) {
    const msg = e?.response?.data?.message || '添加失败'
    ElMessage.error(msg)
  }
  addSaving.value = false
}

onMounted(() => {
  loadWords()
  checkBatchStatus()
})

onUnmounted(() => {
  if (batchPollTimer) {
    clearInterval(batchPollTimer)
    batchPollTimer = null
  }
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
