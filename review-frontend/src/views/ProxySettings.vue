<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span style="font-size: 16px; font-weight: bold">🌐 网络代理设置</span>
          <el-tag :type="statusTag.type" size="large">{{ statusTag.text }}</el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
        title="用于后端调用微软 Edge TTS 生成单词发音"
        description="发音生成是后端直连 speech.platform.bing.com 的出站请求，网络不通时会一直生成失败。在这里填一个 HTTP 代理，该请求就改走代理出去。保存后立即生效，无需重启应用；已在跑的批量生成任务从下一个单词开始走新配置。代理地址只存在本机数据库，不会被导出到备份文件。"
      />

      <el-form label-width="110px" style="max-width: 720px">
        <el-form-item label="启用代理">
          <el-switch v-model="form.enabled" active-text="走代理" inactive-text="直连" />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            关闭时后端直连（并沿用 JVM 系统代理属性，如启动参数里的 -Dhttp.proxyHost）。
          </div>
        </el-form-item>

        <el-form-item label="服务器地址" required>
          <el-input
            v-model="form.host"
            placeholder="127.0.0.1 或 proxy.example.com"
            clearable
            style="max-width: 320px"
          />
          <span style="margin-left: 8px">:</span>
          <el-input-number
            v-model="form.port"
            :min="1"
            :max="65535"
            :controls="false"
            placeholder="7890"
            style="width: 120px; margin-left: 8px"
          />
          <div style="font-size: 12px; color: #909399; margin-top: 4px; line-height: 18px">
            常见本地代理端口：Clash 7890 / V2Ray 10809 / Shadowsocks 1080。只支持 <b>HTTP 代理</b>（含 HTTPS 的 CONNECT 隧道），不支持 SOCKS，也不支持需要账号密码的代理——后端用的是 JDK 的 HTTP 客户端，隧道握手阶段不发认证信息；如果代理要账号密码，请先用本地端口转发转成一个免认证代理再填。
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="saving" @click="clear">清空配置</el-button>
          <el-button :loading="testing === 'proxy'" @click="testProxy">测试代理</el-button>
          <el-button :loading="testing === 'direct'" @click="testDirect">测试直连</el-button>
          <el-button @click="load">重新加载</el-button>
        </el-form-item>

        <el-form-item v-if="testResult" label="测试结果">
          <el-alert
            :type="testResult.success ? 'success' : 'error'"
            :closable="false"
            show-icon
            :title="testResult.title"
            :description="testResult.message"
          />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const form = ref({
  enabled: false,
  host: '',
  port: null
})
const saving = ref(false)
/** '' | 'proxy' | 'direct'，用于给对应按钮显示 loading */
const testing = ref('')
const testResult = ref(null)

const statusTag = computed(() => {
  if (!form.value.enabled) return { type: 'info', text: '⏸️ 直连中' }
  if (!form.value.host || !form.value.port) return { type: 'warning', text: '⚠️ 已启用但未填地址' }
  return { type: 'success', text: '🔀 已启用代理' }
})

const load = async () => {
  try {
    const res = await api.get('/proxy/config')
    form.value = {
      enabled: !!res.enabled,
      host: res.host || '',
      port: res.port || null
    }
  } catch (e) {
    ElMessage.error('加载代理配置失败: ' + (e.response?.data?.message || e.message))
  }
}

const save = async () => {
  if (form.value.enabled && !form.value.host.trim()) {
    ElMessage.warning('启用代理时必须填写服务器地址')
    return
  }
  if (form.value.enabled && !form.value.port) {
    ElMessage.warning('启用代理时必须填写端口')
    return
  }
  saving.value = true
  try {
    const res = await api.put('/proxy/config', {
      enabled: form.value.enabled,
      host: form.value.host.trim(),
      port: form.value.port
    })
    ElMessage.success(res.message || '保存成功')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const runTest = async (path, body, tag) => {
  testing.value = tag
  testResult.value = null
  try {
    const res = await api.post(path, body)
    testResult.value = {
      success: !!res.success,
      title: (res.viaProxy ? '代理测试：' : '直连测试：') + (res.success ? '通过' : '失败'),
      message: res.message
    }
  } catch (e) {
    testResult.value = {
      success: false,
      title: '测试失败',
      message: e.response?.data?.message || e.message
    }
  } finally {
    testing.value = ''
  }
}

const testProxy = () => {
  if (!form.value.host.trim() || !form.value.port) {
    ElMessage.warning('请先填写代理服务器地址和端口')
    return
  }
  runTest('/proxy/test', {
    host: form.value.host.trim(),
    port: form.value.port
  }, 'proxy')
}

const testDirect = () => runTest('/proxy/test-direct', {}, 'direct')

/** 一键清空：关闭代理并抹掉地址，保存后即恢复直连 */
const clear = async () => {
  form.value = { enabled: false, host: '', port: null }
  await save()
}

onMounted(load)
</script>
