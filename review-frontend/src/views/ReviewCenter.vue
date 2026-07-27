<template>
  <div>
    <!-- 未开始复习：欢迎界面 -->
    <div v-if="!reviewing" style="text-align: center; padding-top: 60px">
      <el-card style="max-width: 600px; margin: 0 auto">
        <div style="font-size: 48px; margin-bottom: 20px">🔄</div>
        <h2 style="margin-bottom: 20px">智能复习中心</h2>
        <p style="color: #909399; margin-bottom: 30px">
          系统将根据你的复习记录智能选词，每批 <strong>{{ config.batchSize }}</strong> 个单词
        </p>
        <div style="display: flex; flex-direction: column; gap: 12px; align-items: center">
          <el-button type="primary" size="large" @click="startReview('both')" :loading="loading" style="font-size: 18px; padding: 12px 40px; width: 260px">
            🔄 完整复习（认读+拼写+默写）
          </el-button>
          <el-button type="warning" size="large" @click="startReview('recog')" :loading="loading" style="font-size: 18px; padding: 12px 40px; width: 260px">
            📖 仅认读复习
          </el-button>
          <el-button type="success" size="large" @click="startReview('spell')" :loading="loading" style="font-size: 18px; padding: 12px 40px; width: 260px">
            ✍️ 仅默写复习
          </el-button>
        </div>
        <div style="margin-top: 20px">
          <el-button link @click="showConfigDialog = true">
            ⚙️ 复习设置
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 复习进行中 -->
    <div v-else>
      <!-- 顶部进度栏 -->
      <el-card style="margin-bottom: 20px">
        <el-row align="middle" :gutter="20">
          <el-col :span="6">
            <el-tag :type="phaseTagType" size="large">
              {{ phaseLabel }}
            </el-tag>
          </el-col>
          <el-col :span="14">
            <el-progress
              :percentage="progressPercent"
              :format="() => progressText"
              :stroke-width="20"
              :color="phaseProgressColor"
            />
          </el-col>
          <el-col :span="4" style="text-align: right">
            <el-button size="small" @click="quitReview">退出</el-button>
          </el-col>
        </el-row>
      </el-card>

      <!-- 卡片区 -->
      <el-card style="min-height: 400px; display: flex; align-items: center; justify-content: center">
        <div style="text-align: center; width: 100%; max-width: 700px" tabindex="0" ref="cardAreaRef">

          <!-- 认读模式 -->
          <template v-if="phase === 'recog'">
            <div :class="['recog-card', recogFlashClass]">
              <div style="font-size: 42px; font-weight: bold; margin-bottom: 10px">
                {{ currentWord.word }}
                <el-button link size="small" @click="playWordAudio(currentWord.word)" style="vertical-align: middle; margin-left: 8px">
                  🔊
                </el-button>
              </div>
              <div style="font-size: 20px; color: #909399; margin-bottom: 30px">
                {{ currentWord.phonetic || '' }}
              </div>

              <!-- 点击认识/不认识后显示含义和例句，不自动隐藏 -->
              <transition name="fade">
                <div v-if="recogAnswered" style="margin-bottom: 30px; padding: 20px; background: #f5f7fa; border-radius: 8px">
                  <div style="font-size: 22px; margin-bottom: 10px">{{ currentWord.meaning }}</div>
                  <div style="font-size: 18px; color: #606266">{{ currentWord.exampleSentence }}</div>
                  <div style="font-size: 18px; color: #909399; margin-top: 4px">{{ currentWord.exampleMeaning }}</div>
                </div>
              </transition>

              <!-- 仅认读标记 -->
              <div style="margin-bottom: 10px">
                <el-switch
                  v-model="currentWord.recogOnly"
                  active-text="仅认读"
                  inactive-text=""
                  size="small"
                  @change="toggleRecogOnly(currentWord)"
                />
              </div>

              <!-- 未回答：认识/不认识 按钮 -->
              <div v-if="!recogAnswered" style="margin-top: 20px">
                <el-button
                  type="danger"
                  size="large"
                  style="font-size: 18px; padding: 14px 36px; margin-right: 30px"
                  @click="recogAnswer(false)"
                >
                  不认识
                </el-button>
                <el-button
                  type="success"
                  size="large"
                  style="font-size: 18px; padding: 14px 36px"
                  @click="recogAnswer(true)"
                >
                  认识
                </el-button>
              </div>
              <!-- 已回答：下一词按钮 -->
              <div v-else style="margin-top: 20px">
                <el-button type="primary" size="large" @click="recogNext">下一词 →</el-button>
              </div>
            </div>
          </template>

          <!-- 拼写练习模式（显示生词、音标、意思、例句、例句意思，然后一个拼写框 -->
          <template v-if="phase === 'practice'">
            <div style="margin-bottom: 20px; padding: 20px; background: #f5f7fa; border-radius: 8px">
              <div style="font-size: 36px; font-weight: bold; margin-bottom: 8px">
                {{ currentWord.word }}
                <el-button link size="small" @click="playWordAudio(currentWord.word)" style="vertical-align: middle; margin-left: 8px">
                  🔊
                </el-button>
              </div>
              <div style="font-size: 18px; color: #909399; margin-bottom: 12px">
                {{ currentWord.phonetic || '' }}
              </div>
              <div style="font-size: 22px; margin-bottom: 10px">{{ currentWord.meaning }}</div>
              <div style="font-size: 18px; color: #606266">{{ currentWord.exampleSentence }}</div>
              <div style="font-size: 18px; color: #909399; margin-top: 4px">{{ currentWord.exampleMeaning }}</div>
            </div>

            <!-- 仅认读标记 -->
            <div style="margin-bottom: 10px">
              <el-switch
                v-model="currentWord.recogOnly"
                active-text="仅认读"
                inactive-text=""
                size="small"
                @change="toggleRecogOnly(currentWord)"
              />
            </div>

            <div style="margin-top: 20px">
              <el-input
                ref="practiceInputRef"
                v-model="practiceInput"
                :class="{ 'spell-wrong': practiceShowWrong }"
                size="large"
                placeholder="请拼写上方单词..."
                style="font-size: 20px; max-width: 400px"
                @keyup.enter="checkPractice"
                @keydown.delete="onPracticeDelete"
              />
            </div>

            <!-- 拼写结果反馈 -->
            <transition name="fade">
              <div v-if="practiceFeedback" style="margin-top: 20px">
                <el-tag v-if="practiceFeedback === 'correct'" type="success" size="large" style="font-size: 16px">
                  ✅ 正确！
                </el-tag>
                <div v-if="practiceFeedback === 'wrong'">
                  <el-tag type="danger" size="large" style="font-size: 16px; margin-bottom: 10px">
                    ❌ 拼写有误，请重新输入
                  </el-tag>
                </div>
              </div>
            </transition>

            <div style="margin-top: 30px">
              <el-button
                type="primary"
                size="large"
                style="font-size: 18px; padding: 12px 30px"
                @click="checkPractice"
              >
                确认拼写
              </el-button>
            </div>
          </template>

          <!-- 默写模式 -->
          <template v-if="phase === 'spell'">
            <div style="margin-bottom: 30px">
              <div style="margin-bottom: 10px">
                <el-button link size="small" @click="playWordAudio(currentWord.word)">
                  🔊 播放发音
                </el-button>
              </div>
              <div style="font-size: 24px; margin-bottom: 10px">{{ currentWord.meaning }}</div>
              <div style="font-size: 18px; color: #909399">{{ currentWord.exampleMeaning }}</div>
            </div>

            <!-- 仅认读标记 -->
            <div style="margin-bottom: 10px">
              <el-switch
                v-model="currentWord.recogOnly"
                active-text="仅认读"
                inactive-text=""
                size="small"
                @change="toggleRecogOnly(currentWord)"
              />
            </div>

            <div style="margin-top: 30px">
              <el-input
                ref="spellInputRef"
                v-model="spellInput"
                :class="{ 'spell-wrong': spellShowWrong }"
                size="large"
                placeholder="请输入英文单词..."
                style="font-size: 20px; max-width: 400px"
                @keyup.enter="checkSpelling"
                @keydown.delete="onSpellDelete"
              />
            </div>

            <!-- 拼写结果反馈：不自动消失，用户按删除键时清空 -->
            <transition name="fade">
              <div v-if="spellFeedback" style="margin-top: 20px">
                <el-tag v-if="spellFeedback === 'correct'" type="success" size="large" style="font-size: 16px">
                  ✅ 正确！
                </el-tag>
                <div v-if="spellFeedback === 'wrong'">
                  <el-tag type="danger" size="large" style="font-size: 16px; margin-bottom: 10px">
                    ❌ 错误，请重新输入
                  </el-tag>
                  <div style="font-size: 20px; color: #E6A23C; margin-top: 10px">
                    正确答案：<strong>{{ currentWord.word }}</strong>
                  </div>
                </div>
              </div>
            </transition>

            <div style="margin-top: 30px">
              <el-button
                type="primary"
                size="large"
                style="font-size: 18px; padding: 12px 30px"
                @click="checkSpelling"
              >
                确认拼写
              </el-button>
            </div>
          </template>

        </div>
      </el-card>
    </div>

    <!-- 复习结果弹窗 -->
    <el-dialog v-model="showResult" title="🎉 本次复习完成" width="500px" :close-on-click-modal="false">
      <div style="text-align: center; padding: 20px">
        <el-row :gutter="30">
          <el-col :span="12">
            <el-statistic title="认读正确率" :value="recogAccuracy" suffix="%" />
          </el-col>
          <el-col :span="12">
            <el-statistic title="默写正确率" :value="spellAccuracy" suffix="%" />
          </el-col>
        </el-row>
        <div style="margin-top: 20px; color: #909399">
          共复习 {{ totalWordCount }} 个单词
          <span v-if="recogOnlyCount > 0">（其中 {{ recogOnlyCount }} 个仅认读）</span>
          <span v-if="reviewMode !== 'recog'">，默写循环 {{ spellRound }} 轮</span>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="resetReview">继续复习</el-button>
        <el-button @click="showResult = false; reviewing = false">返回</el-button>
      </template>
    </el-dialog>

    <!-- 设置弹窗 -->
    <el-dialog v-model="showConfigDialog" title="⚙️ 复习设置" width="400px">
      <el-form label-width="120px">
        <el-form-item label="每批词数">
          <el-input-number v-model="configForm.batchSize" :min="10" :max="200" :step="10" />
        </el-form-item>
        <el-form-item label="沉睡天数阈值">
          <el-input-number v-model="configForm.maxDormantDays" :min="5" :max="60" />
        </el-form-item>
        <el-form-item label="紧急因子权重">
          <el-slider v-model="configForm.emergencyWeight" :min="0.1" :max="0.9" :step="0.1" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showConfigDialog = false">取消</el-button>
        <el-button type="primary" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

// ==================== 配置 ====================
const config = ref({ batchSize: 50, maxDormantDays: 15, emergencyWeight: 0.7 })
const configForm = ref({ batchSize: 50, maxDormantDays: 15, emergencyWeight: 0.7 })
const showConfigDialog = ref(false)

// ==================== 复习状态 ====================
const reviewing = ref(false)
const loading = ref(false)

// 复习模式: 'both' | 'recog' | 'spell'
const reviewMode = ref('both')

// 原始词表（从后端获取，不变）
const originalList = ref([])
// 当前正在操作的词表（默写阶段会变成错词子集）
const activeList = ref([])

const currentIndex = ref(0)
// phase: 'recog' | 'practice' | 'spell'
const phase = ref('recog')

// 认读阶段
const recogAnswered = ref(false)
const recogFlashClass = ref('')

// 拼写练习阶段
const practiceInput = ref('')
const practiceFeedback = ref('') // '' | 'correct' | 'wrong'
const practiceShowWrong = ref(false)
const practiceInputRef = ref(null)

// 默写阶段
const spellInput = ref('')
const spellFeedback = ref('') // '' | 'correct' | 'wrong'
const spellShowWrong = ref(false)
const spellInputRef = ref(null)

// 卡片区域 ref（用于键盘监听聚焦）
const cardAreaRef = ref(null)

// 记录每个词的默写结果（首次是否一次写对）: wordId -> boolean
const spellFirstResult = ref({})

// 追踪每轮默写中写错的词ID
const spellWrongInRound = ref([])

// 统计
const recogCorrectCount = ref(0)
const recogWrongCount = ref(0)
const spellRound = ref(1) // 默写轮次

const showResult = ref(false)

// ==================== 计算属性 ====================
const currentWord = computed(() => activeList.value[currentIndex.value] || {})

const totalWordCount = computed(() => originalList.value.length)

const recogOnlyCount = computed(() => originalList.value.filter(w => w.recogOnly).length)

const phaseLabel = computed(() => {
  if (phase.value === 'recog') return '📖 认读模式'
  if (phase.value === 'practice') return '✏️ 拼写练习'
  if (spellRound.value === 1) return '✍️ 默写模式'
  return `✍️ 默写第 ${spellRound.value} 轮`
})

const phaseTagType = computed(() => {
  if (phase.value === 'recog') return 'primary'
  if (phase.value === 'practice') return 'warning'
  return 'success'
})

const phaseProgressColor = computed(() => {
  if (phase.value === 'recog') return '#409EFF'
  if (phase.value === 'practice') return '#E6A23C'
  return '#67C23A'
})

const progressPercent = computed(() => {
  const total = activeList.value.length
  if (total === 0) return 0
  return Math.round((currentIndex.value + 1) / total * 100)
})

const progressText = computed(() => `${currentIndex.value + 1} / ${activeList.value.length}`)

const recogAccuracy = computed(() => {
  const total = recogCorrectCount.value + recogWrongCount.value
  return total > 0 ? Math.round(recogCorrectCount.value / total * 10000) / 100 : 0
})

const spellAccuracy = computed(() => {
  const results = Object.values(spellFirstResult.value)
  const total = results.length
  const correct = results.filter(r => r).length
  return total > 0 ? Math.round(correct / total * 10000) / 100 : 0
})

// ==================== 音频播放 ====================

/**
 * 单词归一化：转小写 + 将各种 Unicode 连字符统一为普通连字符 (U+002D)
 * 解决 germ‑free (U+2011 non-breaking hyphen) vs germ-free (U+002D) 的拼写校验问题
 */
const normalizeWord = (str) => {
  return str
    .toLowerCase()
    .replace(/[‐‑‒–—―﹘﹣－]/g, '-')
}

// 当前正在播放的音频对象，用于中断上一次播放
let currentAudio = null

/**
 * 播放单词发音（仅手动调用，不在默写阶段自动播放）。
 * 1. 直接尝试加载音频文件
 * 2. 如果返回 202（正在生成），轮询等待就绪后播放
 */
const playWordAudio = async (word) => {
  if (!word) return

  // 停止上一次播放
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }

  const audioUrl = `/api/audio/${word.trim().toLowerCase()}`

  try {
    // 先尝试直接播放
    const audio = new Audio(audioUrl)
    currentAudio = audio
    await audio.play()
    return
  } catch (e) {
    // 播放失败，可能是音频还没生成（202）或其他错误
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
    // 超时，静默忽略
  } catch (e) {
    // 轮询失败，静默忽略
  }
}

/**
 * 切换到新单词后自动播放音频（仅认读和拼写练习阶段，默写阶段不自动播放）。
 */
const autoPlayOnWordChange = (word) => {
  if (word && reviewing.value && phase.value !== 'spell') {
    playWordAudio(word)
  }
}

// ==================== 键盘事件 ====================
const handleKeydown = (e) => {
  if (!reviewing.value) return
  // 拼写练习和默写模式下输入框有焦点时不拦截
  if (phase.value === 'practice' || phase.value === 'spell') return

  if (phase.value === 'recog') {
    if (e.key === 'ArrowLeft') {
      // 上一个词
      e.preventDefault()
      recogPrev()
    } else if (e.key === 'ArrowRight') {
      // 下一个词（如果已回答则跳下一个，否则无操作）
      e.preventDefault()
      if (recogAnswered.value) {
        recogNext()
      }
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (!recogAnswered.value) {
        // 未回答时，回车 = 点击"认识"
        recogAnswer(true)
      } else {
        // 已回答时，回车 = 下一词
        recogNext()
      }
    }
  }
}

// ==================== 方法 ====================

const loadConfig = async () => {
  try {
    const res = await api.get('/review/config')
    config.value = res
    configForm.value = { ...res }
  } catch (e) {
    console.error('加载配置失败', e)
  }
}

const saveConfig = async () => {
  try {
    const res = await api.put('/review/config', configForm.value)
    config.value = res
    showConfigDialog.value = false
    ElMessage.success('设置已保存')
  } catch (e) {
    ElMessage.error('保存设置失败')
  }
}

// 开始复习，mode: 'both' | 'recog' | 'spell'
const startReview = async (mode) => {
  loading.value = true
  try {
    const res = await api.get('/review/generate')
    if (!res || res.length === 0) {
      ElMessage.info('暂无需要复习的单词，请先导入词库')
      loading.value = false
      return
    }
    originalList.value = res
    currentIndex.value = 0
    reviewMode.value = mode

    // 根据模式设置初始阶段
    if (mode === 'spell') {
      phase.value = 'spell'
      // 仅默写模式：过滤掉仅认读的词
      activeList.value = res.filter(w => !w.recogOnly)
      if (activeList.value.length === 0) {
        ElMessage.info('所有单词均为仅认读，无需默写')
        loading.value = false
        return
      }
    } else {
      phase.value = 'recog'
      activeList.value = [...res]
    }

    recogAnswered.value = false
    recogFlashClass.value = ''
    practiceInput.value = ''
    practiceFeedback.value = ''
    practiceShowWrong.value = false
    spellInput.value = ''
    spellFeedback.value = ''
    spellShowWrong.value = false
    spellFirstResult.value = {}
    spellWrongInRound.value = []
    recogCorrectCount.value = 0
    recogWrongCount.value = 0
    spellRound.value = 1
    reviewing.value = true

    // 自动播放第一个单词的音频
    autoPlayOnWordChange(res[0]?.word)

    // 默写模式自动聚焦输入框
    if (mode === 'spell') {
      nextTick(() => {
        spellInputRef.value?.focus()
      })
    }
  } catch (e) {
    ElMessage.error('生成复习列表失败')
  }
  loading.value = false
}

// ==================== 认读阶段 ====================

const recogAnswer = async (known) => {
  recogAnswered.value = true

  // 闪烁效果
  recogFlashClass.value = known ? 'flash-green' : 'flash-red'
  setTimeout(() => { recogFlashClass.value = '' }, 600)

  // 统计
  if (known) recogCorrectCount.value++
  else recogWrongCount.value++

  // 上报后端
  try {
    await api.post('/review/record', {
      wordId: currentWord.value.id,
      mode: 'RECOG',
      result: known
    })
  } catch (e) {
    console.error('记录认读结果失败', e)
  }
}

// 认读阶段：下一个词
const recogNext = () => {
  if (currentIndex.value < activeList.value.length - 1) {
    currentIndex.value++
    recogAnswered.value = false
    recogFlashClass.value = ''
    autoPlayOnWordChange(currentWord.value.word)
  } else {
    // 认读全部完成
    if (reviewMode.value === 'recog') {
      // 仅认读模式，直接结束
      showResult.value = true
    } else {
      // 完整模式，进入拼写练习阶段
      enterPracticePhase()
    }
  }
}

// 认读阶段：上一个词
const recogPrev = () => {
  if (currentIndex.value > 0) {
    currentIndex.value--
    recogAnswered.value = false
    recogFlashClass.value = ''
    autoPlayOnWordChange(currentWord.value.word)
  }
}

// ==================== 拼写练习阶段 ====================

const enterPracticePhase = () => {
  phase.value = 'practice'
  // 过滤掉仅认读的词
  activeList.value = originalList.value.filter(w => !w.recogOnly)
  if (activeList.value.length === 0) {
    // 没有需要拼写练习的词，直接进入默写阶段
    enterSpellPhase()
    return
  }
  currentIndex.value = 0
  practiceInput.value = ''
  practiceFeedback.value = ''
  practiceShowWrong.value = false
  autoPlayOnWordChange(activeList.value[0]?.word)
  nextTick(() => {
    practiceInputRef.value?.focus()
  })
}

// 拼写练习输入框按删除键：如果当前显示着错误反馈，清空输入内容
const onPracticeDelete = (e) => {
  if (practiceFeedback.value === 'wrong') {
    e.preventDefault()
    practiceInput.value = ''
    practiceFeedback.value = ''
    practiceShowWrong.value = false
  }
}

const checkPractice = () => {
  // 防重入：正确后等待自动跳转期间，忽略后续回车
  if (practiceFeedback.value === 'correct') return
  if (!practiceInput.value.trim()) {
    ElMessage.warning('请输入单词')
    return
  }

  const input = normalizeWord(practiceInput.value.trim())
  const correct = normalizeWord(currentWord.value.word.trim())
  const isCorrect = input === correct

  if (isCorrect) {
    practiceFeedback.value = 'correct'
    practiceShowWrong.value = false
    // 正确后 0.8 秒自动跳下一个
    setTimeout(() => {
      practiceNext()
    }, 800)
  } else {
    // 错误：提示重新输入
    practiceFeedback.value = 'wrong'
    practiceShowWrong.value = true
    nextTick(() => {
      practiceInputRef.value?.focus()
    })
  }
}

// 拼写练习：正确后跳下一个
const practiceNext = () => {
  if (currentIndex.value < activeList.value.length - 1) {
    currentIndex.value++
    practiceInput.value = ''
    practiceFeedback.value = ''
    practiceShowWrong.value = false
    autoPlayOnWordChange(currentWord.value.word)
    nextTick(() => {
      practiceInputRef.value?.focus()
    })
  } else {
    // 拼写练习完成，进入默写阶段
    enterSpellPhase()
  }
}

// ==================== 默写阶段 ====================

const enterSpellPhase = () => {
  phase.value = 'spell'
  // 过滤掉仅认读的词
  activeList.value = originalList.value.filter(w => !w.recogOnly)
  if (activeList.value.length === 0) {
    // 没有需要默写的词，复习完成
    showResult.value = true
    return
  }
  currentIndex.value = 0
  spellInput.value = ''
  spellFeedback.value = ''
  spellShowWrong.value = false
  spellRound.value = 1
  spellWrongInRound.value = []
  nextTick(() => {
    spellInputRef.value?.focus()
  })
}

// 默写输入框按删除键：如果当前显示着错误反馈，清空输入内容并隐藏正确答案
const onSpellDelete = (e) => {
  if (spellFeedback.value === 'wrong') {
    // 阻止默认删除行为，直接清空整个输入框并隐藏反馈
    e.preventDefault()
    spellInput.value = ''
    spellFeedback.value = ''
    spellShowWrong.value = false
  }
}

const checkSpelling = async () => {
  // 防重入：正确后等待自动跳转期间，忽略后续回车
  if (spellFeedback.value === 'correct') return
  if (!spellInput.value.trim()) {
    ElMessage.warning('请输入单词')
    return
  }

  const input = normalizeWord(spellInput.value.trim())
  const correct = normalizeWord(currentWord.value.word.trim())
  const isCorrect = input === correct

  // 上报后端
  try {
    await api.post('/review/record', {
      wordId: currentWord.value.id,
      mode: 'SPELL',
      result: isCorrect
    })
  } catch (e) {
    console.error('记录默写结果失败', e)
  }

  if (isCorrect) {
    spellFeedback.value = 'correct'
    spellShowWrong.value = false
    // 只记录第一轮首次尝试结果，已有记录则不覆盖
    if (spellRound.value === 1 && !(currentWord.value.id in spellFirstResult.value)) {
      spellFirstResult.value[currentWord.value.id] = true
    }
    // 拼写完成后播放一次音频
    playWordAudio(currentWord.value.word)
    // 正确后 0.8 秒自动跳下一个
    setTimeout(() => {
      spellNext()
    }, 800)
  } else {
    // 错误：显示正确答案（不自动消失），清空输入框让用户重新输入
    spellFeedback.value = 'wrong'
    spellShowWrong.value = true
    if (spellRound.value === 1) {
      spellFirstResult.value[currentWord.value.id] = false
    }
    // 记录本轮错词
    if (!spellWrongInRound.value.includes(currentWord.value.id)) {
      spellWrongInRound.value.push(currentWord.value.id)
    }
    // 拼写完成后播放一次音频
    playWordAudio(currentWord.value.word)
    // 不清空输入框，保留用户输入内容供对比；用户按删除键时再清空
    nextTick(() => {
      spellInputRef.value?.focus()
    })
  }
}

// 默写阶段：正确后跳下一个
const spellNext = () => {
  if (currentIndex.value < activeList.value.length - 1) {
    currentIndex.value++
    spellInput.value = ''
    spellFeedback.value = ''
    spellShowWrong.value = false
    nextTick(() => {
      spellInputRef.value?.focus()
    })
  } else {
    // 本轮默写完成，检查是否有错词需要重默
    startNextSpellRound()
  }
}

// 本轮默写结束，筛选错词进入下一轮
const startNextSpellRound = () => {
  const wrongWords = spellWrongInRound.value
  if (wrongWords.length === 0) {
    // 全部正确，复习完成
    showResult.value = true
    return
  }

  // 有错词，进入下一轮
  spellRound.value++
  activeList.value = wrongWords.map(id => originalList.value.find(w => w.id === id)).filter(Boolean)
  currentIndex.value = 0
  spellInput.value = ''
  spellFeedback.value = ''
  spellShowWrong.value = false
  spellWrongInRound.value = []

  ElMessage.info(`还有 ${activeList.value.length} 个单词需要重默，开始第 ${spellRound.value} 轮`)
  nextTick(() => {
    spellInputRef.value?.focus()
  })
}

// 切换仅认读标记
const toggleRecogOnly = async (word) => {
  try {
    await api.put(`/words/${word.id}/recog-only`, { recogOnly: word.recogOnly })
    // 同步更新 originalList 中对应词的 recogOnly
    const orig = originalList.value.find(w => w.id === word.id)
    if (orig) orig.recogOnly = word.recogOnly
    ElMessage.success(word.recogOnly ? '已设为仅认读，后续不再默写' : '已取消仅认读')
  } catch (e) {
    // 回滚
    word.recogOnly = !word.recogOnly
    ElMessage.error('更新失败')
  }
}

// 退出复习
const quitReview = async () => {
  try {
    await ElMessageBox.confirm('确定退出本次复习？已记录的结果不会丢失。', '提示', {
      confirmButtonText: '确定退出',
      cancelButtonText: '继续复习',
      type: 'warning'
    })
    reviewing.value = false
  } catch {
    // 用户取消
  }
}

const resetReview = () => {
  showResult.value = false
  reviewing.value = false
  startReview(reviewMode.value)
}

onMounted(() => {
  loadConfig()
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.recog-card {
  transition: background-color 0.3s;
  padding: 20px;
  border-radius: 12px;
}

.flash-green {
  background-color: #f0f9eb !important;
  animation: flashGreen 0.6s;
}

.flash-red {
  background-color: #fef0f0 !important;
  animation: flashRed 0.6s;
}

@keyframes flashGreen {
  0% { background-color: #67C23A; }
  100% { background-color: #f0f9eb; }
}

@keyframes flashRed {
  0% { background-color: #F56C6C; }
  100% { background-color: #fef0f0; }
}

.spell-wrong :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #F56C6C inset !important;
}

.fade-enter-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from {
  opacity: 0;
}
</style>
