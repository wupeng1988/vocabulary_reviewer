<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span style="font-size: 16px; font-weight: bold">🎯 易错词配置</span>
          <el-tag size="large" type="danger">
            连续错 {{ form.consecutiveWrongLimit }} 次 / 近 {{ form.windowSize }} 次错 {{ form.recentWrongLimit }} 次
          </el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
        title="拼写练习、默写、听音默写 都计入统计"
        description="每一次拼写提交（对或错）都会记入「最近 N 次拼写」。达到下面任一阈值，单词会被自动加进内置易错词组并弹出提示；也可以在复习时手动加入。易错词组是系统内置的，可以在「词组管理」里改名和排序，但不能删除。"
      />

      <el-form label-width="160px" style="max-width: 760px">
        <el-form-item label="统计窗口次数" required>
          <el-input-number
            v-model="form.windowSize"
            :min="1"
            :max="MAX_WINDOW"
            :controls="false"
            style="width: 160px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            「最近 {{ form.windowSize }} 次拼写」里的 {{ form.windowSize }}，词库管理那一列显示的就是这个数字。
            每次拼写提交都会进这个滑动窗口，超过窗口长度的旧记录会被丢掉。上限 {{ MAX_WINDOW }}。
          </div>
        </el-form-item>

        <el-form-item label="连续错误次数" required>
          <el-input-number
            v-model="form.consecutiveWrongLimit"
            :min="1"
            :max="form.windowSize"
            :controls="false"
            style="width: 160px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            连续拼错多少次自动加入易错词组；中途拼对一次就重新计数。当前 {{ form.consecutiveWrongLimit }} 次。
          </div>
        </el-form-item>

        <el-form-item label="窗口内累计错误次数" required>
          <el-input-number
            v-model="form.recentWrongLimit"
            :min="1"
            :max="form.windowSize"
            :controls="false"
            style="width: 160px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            最近 {{ form.windowSize }} 次拼写里累计拼错多少次自动加入易错词组，不要求连续。当前 {{ form.recentWrongLimit }} 次。
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="saving" @click="restoreDefaults">恢复默认值</el-button>
          <el-button :loading="saving" @click="load">重新加载</el-button>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">内置易错词组</el-divider>
      <el-descriptions :column="3" border size="small" style="max-width: 760px">
        <el-descriptions-item label="名称">
          {{ group.name || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="编号">
          {{ group.code || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="单词数">
          <el-tag size="small" type="info">{{ group.wordCount ?? 0 }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 12px; font-size: 12px; color: #909399">
        在「词组管理」里可以改名、调整排序；要清空易错词本，去「词库管理」筛选该词组后逐个移出即可。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

/** 与后端 DifficultConfig.MAX_WINDOW_SIZE 保持一致 */
const MAX_WINDOW = 100
const DEFAULTS = { windowSize: 30, consecutiveWrongLimit: 3, recentWrongLimit: 5 }

const form = ref({ ...DEFAULTS })
const group = ref({})
const saving = ref(false)

const load = async () => {
  try {
    const res = await api.get('/difficult/config')
    form.value = {
      windowSize: res.windowSize ?? DEFAULTS.windowSize,
      consecutiveWrongLimit: res.consecutiveWrongLimit ?? DEFAULTS.consecutiveWrongLimit,
      recentWrongLimit: res.recentWrongLimit ?? DEFAULTS.recentWrongLimit
    }
    group.value = {
      name: res.groupName,
      code: res.groupCode,
      id: res.groupId
    }
    await loadGroupCount()
  } catch (e) {
    ElMessage.error('加载配置失败: ' + (e.response?.data?.message || e.message))
  }
}

/** 内置易错词组当前有多少单词（词组树接口里带 wordCount） */
const loadGroupCount = async () => {
  if (!group.value.id) return
  try {
    const tree = await api.get('/groups/tree')
    const stack = [...(tree || [])]
    while (stack.length) {
      const node = stack.pop()
      if (node.id === group.value.id) {
        group.value.wordCount = node.wordCount || 0
        return
      }
      if (node.children?.length) stack.push(...node.children)
    }
  } catch {
    // 词组树拿不到就只影响那个数字，不影响配置页本身
  }
}

/** 窗口变小时，把两个阈值一并压到新窗口内，避免存出永远触发不了的配置 */
const clampLimits = () => {
  const max = form.value.windowSize || MAX_WINDOW
  if (form.value.consecutiveWrongLimit > max) form.value.consecutiveWrongLimit = max
  if (form.value.recentWrongLimit > max) form.value.recentWrongLimit = max
}

const save = async () => {
  clampLimits()
  saving.value = true
  try {
    const res = await api.put('/difficult/config', { ...form.value })
    ElMessage.success(res.message || '保存成功')
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const restoreDefaults = () => {
  form.value = { ...DEFAULTS }
  save()
}

onMounted(load)
</script>
