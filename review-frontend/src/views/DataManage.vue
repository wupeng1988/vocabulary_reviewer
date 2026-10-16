<template>
  <div>
    <!-- Excel 导入词库 -->
    <el-card style="margin-bottom: 20px">
      <template #header>
        <span style="font-size: 16px; font-weight: bold">📚 导入词库</span>
      </template>
      <!-- 先选择导入目标词组 -->
      <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
        <span style="color: #606266">
          导入到词组：<span style="color: #F56C6C; font-weight: bold">*</span>
        </span>
        <el-tree-select
          v-model="importGroupId"
          :data="groupOptions"
          clearable
          check-strictly
          default-expand-all
          node-key="value"
          :props="{ value: 'value', label: 'label', children: 'children' }"
          placeholder="必选：请先选择要导入的词组"
          style="width: 320px"
        />
        <span v-if="!importGroupId" style="color: #F56C6C; font-size: 12px">
          必须先选好目标词组才能导入
        </span>
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
        :on-start="handleUploadStart"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :before-upload="beforeUpload"
        :disabled="importingExcel || importingJson || excelExportLoading || exportLoading"
        accept=".xls,.xlsx"
        drag
      >
        <el-icon v-if="importingExcel" class="is-loading" style="font-size: 40px; color: #409EFF"><loading /></el-icon>
        <el-icon v-else style="font-size: 40px; color: #409EFF"><upload-filled /></el-icon>
        <div style="margin-top: 10px">
          {{ importingExcel ? '正在导入 Excel，请稍候…' : '将 Excel 文件拖到此处，或 点击上传' }}
        </div>
        <template #tip>
          <div style="color: #909399; font-size: 12px; margin-top: 8px">
            仅支持 .xls / .xlsx 格式，A-E 列依次对应：生词、音标、含义、例句、例句释义
            <el-link
              type="primary"
              href="/api/words/export-template"
              :underline="false"
              style="font-size: 12px; margin-left: 8px; vertical-align: baseline"
            >下载导入模板</el-link>
          </div>
        </template>
      </el-upload>
      <div style="margin-top: 16px; padding-top: 16px; border-top: 1px solid #ebeef5">
        <el-button
          type="primary"
          @click="doExportExcel"
          :loading="excelExportLoading"
          :disabled="importingExcel || importingJson || exportLoading"
        >
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
          <el-button
            type="success"
            @click="exportData"
            :loading="exportLoading"
            :disabled="importingExcel || importingJson || excelExportLoading"
          >
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
            :disabled="importingExcel || excelExportLoading || exportLoading"
            accept=".json"
            :show-file-list="false"
          >
            <el-button
              type="warning"
              :loading="importingJson"
              :disabled="importingExcel || excelExportLoading || exportLoading"
            >
              <el-icon v-if="!importingJson" style="margin-right: 4px"><upload /></el-icon>
              {{ importingJson ? '正在导入…' : '导入备份数据' }}
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
import { UploadFilled, Download, Upload, Loading } from '@element-plus/icons-vue'
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
const importingExcel = ref(false)
const importingJson = ref(false)

const beforeUpload = (file) => {
  const name = file.name
  if (!name.endsWith('.xls') && !name.endsWith('.xlsx')) {
    ElMessage.error('仅支持 .xls 和 .xlsx 格式')
    return false
  }
  // 目标词组是必填项。这里不能靠 el-upload 的 disabled 挡——挡住了点击毫无反应，
  // 用户根本不知道为什么传不上去，所以放行到这里弹红字提醒。
  if (!importGroupId.value) {
    ElMessage.error('请先选择「导入到词组」，目标词组是必选项')
    return false
  }
  return true
}

const handleUploadStart = () => {
  importingExcel.value = true
}

const handleUploadSuccess = (response) => {
  if (response.success) {
    ElMessage.success(response.message)
  } else {
    ElMessage.error(response.message)
  }
  importingExcel.value = false
  uploadRef.value?.clearFiles()
}

const handleUploadError = () => {
  ElMessage.error('上传失败，请检查文件格式')
  importingExcel.value = false
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
  importingJson.value = true
  return true
}

const handleJsonImportSuccess = (response) => {
  if (response.success) {
    ElMessage.success(response.message)
  } else {
    ElMessage.error(response.message)
  }
  importingJson.value = false
  jsonUploadRef.value?.clearFiles()
}

const handleJsonImportError = () => {
  ElMessage.error('导入失败，请检查文件格式')
  importingJson.value = false
  jsonUploadRef.value?.clearFiles()
}
</script>
