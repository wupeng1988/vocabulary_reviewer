<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span style="font-size: 16px; font-weight: bold">🤖 AI 设置</span>
          <el-tag :type="configured ? 'success' : 'warning'" size="large">
            {{ configured ? '✅ 已配置' : '⚠️ 未配置' }}
          </el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
        title="用于「词库管理 → 批量AI解析添加」"
        description="填好服务商、API Key 和接口地址后，即可把一批英文单词交给 AI 解析出原型、音标、全部释义和雅思例句并自动入库。API Key 只保存在本机数据库，不会被导出到备份文件，也不会上传到任何第三方。"
      />

      <el-form label-width="110px" style="max-width: 720px">
        <el-form-item label="服务商">
          <el-select v-model="form.provider" style="width: 260px" @change="onProviderChange">
            <el-option
              v-for="p in providerOptions"
              :key="p.value"
              :label="p.label"
              :value="p.value"
            />
          </el-select>
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            {{ currentProviderDesc }}
          </div>
        </el-form-item>

        <el-form-item label="API Key" required>
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            placeholder="粘贴你的 API Key"
            clearable
            style="max-width: 480px"
          />
        </el-form-item>

        <el-form-item label="接口地址" required>
          <el-input
            v-model="form.baseUrl"
            placeholder="https://api.deepseek.com"
            clearable
            style="max-width: 480px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            OpenAI 兼容地址即可，不用带 /chat/completions 后缀，也不用带 /v1。切换服务商时会自动填入默认值。
          </div>
        </el-form-item>

        <el-form-item label="模型" required>
          <el-input
            v-model="form.model"
            placeholder="deepseek-chat"
            clearable
            style="max-width: 480px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            当前常用值：DeepSeek 填 <code>deepseek-chat</code>；通义千问填 <code>qwen-plus</code> 或 <code>qwen-turbo</code>。
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="saving" @click="clear">清空配置</el-button>
          <el-button @click="load">重新加载</el-button>
          <span style="margin-left: 12px; font-size: 12px; color: #909399">
            保存后即可去「词库管理」使用「批量AI解析添加」
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

/** 各服务商的默认接口地址与模型，切换时自动填充 */
const PROVIDERS = {
  deepseek: {
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    desc: 'DeepSeek 官方开放平台：platform.deepseek.com 创建 API Key。'
  },
  qwen: {
    label: '通义千问',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen-plus',
    desc: '阿里云百炼（DashScope）OpenAI 兼容模式：bailian.console.aliyun.com 创建 API Key。'
  }
}

const providerOptions = Object.keys(PROVIDERS).map(value => ({ value, label: PROVIDERS[value].label }))

const form = ref({
  provider: 'deepseek',
  apiKey: '',
  baseUrl: '',
  model: ''
})
const configured = ref(false)
const saving = ref(false)

const currentProviderDesc = computed(() => PROVIDERS[form.value.provider]?.desc || '')

/**
 * 切换服务商时填充默认地址与模型。
 * 只在「当前值为空」或「当前值等于另一个服务商的默认值」时覆盖，
 * 避免用户自定义的地址被误清。
 */
const onProviderChange = (provider) => {
  const p = PROVIDERS[provider]
  if (!p) return
  const currentBase = (form.value.baseUrl || '').trim()
  const currentModel = (form.value.model || '').trim()
  const otherDefaults = Object.entries(PROVIDERS).filter(([k]) => k !== provider).map(([, v]) => v)

  if (!currentBase || otherDefaults.some(v => v.baseUrl === currentBase)) {
    form.value.baseUrl = p.baseUrl
  }
  if (!currentModel || otherDefaults.some(v => v.model === currentModel)) {
    form.value.model = p.model
  }
}

const load = async () => {
  try {
    const res = await api.get('/ai/config')
    form.value = {
      provider: res.provider || 'deepseek',
      apiKey: res.apiKey || '',
      baseUrl: res.baseUrl || '',
      model: res.model || ''
    }
    configured.value = !!res.configured
  } catch (e) {
    ElMessage.error('加载配置失败: ' + (e.response?.data?.message || e.message))
  }
}

const save = async () => {
  const apiKey = form.value.apiKey.trim()
  const baseUrl = form.value.baseUrl.trim()
  const model = form.value.model.trim()
  const allEmpty = !apiKey && !baseUrl && !model

  // 全空 = 清空配置（需确认）；只要填了任意一项，就必须三项齐全
  if (allEmpty) {
    try {
      await ElMessageBox.confirm(
        '清空后「批量AI解析添加」将不可用，直到重新填写。确定清空吗？',
        '清空 AI 配置',
        { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  } else {
    if (!apiKey) {
      ElMessage.warning('请填写 API Key（若想清空请点「清空配置」）')
      return
    }
    if (!baseUrl) {
      ElMessage.warning('请填写接口地址')
      return
    }
    if (!model) {
      ElMessage.warning('请填写模型名称')
      return
    }
  }

  saving.value = true
  try {
    const res = await api.put('/ai/config', {
      provider: form.value.provider,
      apiKey,
      baseUrl,
      model
    })
    configured.value = !!res.configured
    ElMessage.success(res.message || '保存成功')
    if (!configured.value) load()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 一键清空：把三项置空后走正常保存流程（会弹确认） */
const clear = () => {
  form.value.apiKey = ''
  form.value.baseUrl = ''
  form.value.model = ''
  save()
}

onMounted(load)
</script>
