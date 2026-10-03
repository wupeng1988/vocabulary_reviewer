<template>
  <div>
    <!-- Excel 导入词库 -->
    <el-card style="margin-bottom: 20px">
      <template #header>
        <span style="font-size: 16px; font-weight: bold">📚 导入词库</span>
      </template>
      <!-- 先选择导入目标词组 -->
      <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
        <span style="color: #606266">导入到词组：</span>
        <el-tree-select
          v-model="importGroupId"
          :data="groupOptions"
          clearable
          check-strictly
          default-expand-all
          node-key="value"
          :props="{ value: 'value', label: 'label', children: 'children' }"
          placeholder="请先选择要导入的词组"
          style="width: 320px"
        />
        <span style="color: #909399; font-size: 12px">
          已存在的单词只新增与该词组的关联，不会被重复创建
        </span>
      </div>
      <el-upload
        ref="uploadRef"
        action="/api/words/import"
        name="file"
        :data="uploadData"
        :limit="1"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :before-upload="beforeUpload"
        :disabled="!importGroupId"
        accept=".xls,.xlsx"
        drag
      >
        <el-icon style="font-size: 40px; color: #409EFF"><upload-filled /></el-icon>
        <div style="margin-top: 10px">将 Excel 文件拖到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div style="color: #909399; font-size: 12px; margin-top: 8px">
            仅支持 .xls / .xlsx 格式，A-E 列依次对应：生词、音标、含义、例句、例句释义
          </div>
        </template>
      </el-upload>
      <div style="margin-top: 16px; padding-top: 16px; border-top: 1px solid #ebeef5">
        <el-button type="primary" @click="doExportExcel" :loading="excelExportLoading">
          <el-icon style="margin-right: 4px"><download /></el-icon>导出为 Excel
        </el-button>
        <span style="color: #909399; font-size: 12px; margin-left: 8px">
          按词组分 Sheet 导出（每个词组一个 Sheet，A-E 列依次对应：生词、音标、含义、例句、例句释义）；未归属词组的单词导出到「未分组」Sheet
        </span>
      </div>
    </el-card>

    <!-- 数据备份：导出 / 导入 -->
    <el-card style="margin-bottom: 20px">
      <template #header>
        <span style="font-size: 16px; font-weight: bold">💾 数据备份</span>
      </template>
      <el-row :gutter="40">
        <el-col :span="12">
          <el-button type="success" @click="exportData" :loading="exportLoading">
            <el-icon style="margin-right: 4px"><download /></el-icon>导出全部数据
          </el-button>
          <div style="color: #909399; font-size: 12px; margin-top: 8px">
            导出所有单词（含复习统计）、词组关系和复习配置为 JSON 文件，可用于迁移到其他设备
          </div>
        </el-col>
        <el-col :span="12">
          <el-upload
            ref="jsonUploadRef"
            action="/api/words/import-data"
            name="file"
            :limit="1"
            :on-success="handleJsonImportSuccess"
            :on-error="handleJsonImportError"
            :before-upload="beforeJsonUpload"
            accept=".json"
            :show-file-list="false"
          >
            <el-button type="warning">
              <el-icon style="margin-right: 4px"><upload /></el-icon>导入备份数据
            </el-button>
          </el-upload>
          <div style="color: #909399; font-size: 12px; margin-top: 8px">
            从 JSON 备份文件恢复所有单词、词组关系和复习配置（已有单词数据将被覆盖）；
            老版本备份（无词组关系）会自动导入到默认词组
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { UploadFilled, Download, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api, { exportData as apiExport, exportExcel as apiExportExcel } from '../api'
import { loadGroupTree, toTreeOptions } from '../utils/group'

// ========== 词组选择 ==========
const groupTree = ref([])
const importGroupId = ref(null)
const groupOptions = computed(() => toTreeOptions(groupTree.value))
const uploadData = computed(() => ({ groupId: importGroupId.value }))

onMounted(async () => {
  try {
    groupTree.value = await loadGroupTree()
  } catch (e) {
    ElMessage.error('加载词组失败')
  }
})

// ========== Excel 导入 ==========
const uploadRef = ref(null)
const excelExportLoading = ref(false)

const beforeUpload = (file) => {
  const name = file.name
  if (!name.endsWith('.xls') && !name.endsWith('.xlsx')) {
    ElMessage.error('仅支持 .xls 和 .xlsx 格式')
    return false
  }
  if (!importGroupId.value) {
    ElMessage.error('请先选择导入的词组')
    return false
  }
  return true
}

const handleUploadSuccess = (response) => {
  if (response.success) {
    ElMessage.success(response.message)
  } else {
    ElMessage.error(response.message)
  }
  uploadRef.value?.clearFiles()
}

const handleUploadError = () => {
  ElMessage.error('上传失败，请检查文件格式')
  uploadRef.value?.clearFiles()
}

// ========== 导出 Excel ==========
const doExportExcel = async () => {
  excelExportLoading.value = true
  try {
    const response = await apiExportExcel()
    const contentDisposition = response.headers['content-disposition'] || ''
    let filename = '词库.xlsx'
    const match = contentDisposition.match(/filename\*=UTF-8''(.+)/)
    if (match) {
      filename = decodeURIComponent(match[1])
    }
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', filename)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('Excel 导出成功')
  } catch (e) {
    ElMessage.error('导出失败')
  } finally {
    excelExportLoading.value = false
  }
}

// ========== 数据备份：导出 / 导入 ==========
const exportLoading = ref(false)
const jsonUploadRef = ref(null)

const exportData = async () => {
  exportLoading.value = true
  try {
    const response = await apiExport()
    // 从 Content-Disposition 提取文件名
    const contentDisposition = response.headers['content-disposition'] || ''
    let filename = '单词复习数据备份.json'
    const match = contentDisposition.match(/filename\*=UTF-8''(.+)/)
    if (match) {
      filename = decodeURIComponent(match[1])
    }
    // 触发下载
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', filename)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('数据导出成功')
  } catch (e) {
    ElMessage.error('导出失败')
  } finally {
    exportLoading.value = false
  }
}

const beforeJsonUpload = (file) => {
  if (!file.name.endsWith('.json')) {
    ElMessage.error('仅支持 .json 格式的备份文件')
    return false
  }
  return true
}

const handleJsonImportSuccess = (response) => {
  if (response.success) {
    ElMessage.success(response.message)
  } else {
    ElMessage.error(response.message)
  }
  jsonUploadRef.value?.clearFiles()
}

const handleJsonImportError = () => {
  ElMessage.error('导入失败，请检查文件格式')
  jsonUploadRef.value?.clearFiles()
}
</script>
