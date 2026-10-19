<template>
  <div>
    <!-- 搜索栏 - sticky 固定顶部 -->
    <el-card class="sticky-search">
      <!-- 第一行：搜索 + 词组筛选 -->
      <el-row :gutter="16" align="middle">
        <el-col :span="difficultMode ? 9 : 4">
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
        <el-col v-if="!difficultMode" :span="5">
          <el-tree-select
            v-model="filterGroupId"
            :data="groupOptions"
            clearable
            check-strictly
            default-expand-all
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="按词组筛选（含子词组）"
            style="width: 100%"
            @change="onFilterGroupChange"
          />
        </el-col>
        <!-- 同义标签筛选：放在「搜索」按钮左边。勾上后只显示已归组的词，
             下拉可进一步指定具体标签 -->
        <el-col :span="8">
          <el-checkbox
            v-model="onlySynonymWords"
            size="small"
            style="margin-right: 10px"
            @change="onSynonymFilterChange"
          >
            只看有同义标签的
          </el-checkbox>
          <el-select
            v-model="filterSynonym"
            placeholder="指定同义标签"
            clearable
            filterable
            :disabled="!onlySynonymWords"
            size="small"
            style="width: 190px"
            @change="onSynonymFilterChange"
          >
            <el-option v-for="s in synonymOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-col>
        <el-col :span="2">
          <el-button size="small" type="primary" @click="loadWords">搜索</el-button>
        </el-col>
        <el-col :span="5" style="text-align: right">
          <span style="color: #909399">共 {{ totalElements }} 个单词</span>
        </el-col>
      </el-row>

      <!-- 第二行：左边是操作按钮组，右边是复习时隐藏答案用的三个开关 -->
      <el-row :gutter="16" style="margin-top: 12px" align="middle">
        <el-col :span="18">
          <template v-if="!difficultMode">
            <el-button size="small" type="primary" plain @click="openAiDialog">🤖 批量AI解析添加</el-button>
            <el-button size="small" type="success" @click="openAddDialog">➕ 新增</el-button>
            <el-button
              size="small"
              :type="batchRunning ? 'warning' : 'success'"
              :loading="batchChecking"
              @click="handleAudioButton"
            >
              {{ batchRunning ? '音频生成中...' : selectedWords.length > 0 ? '🔊 重新生成音频' : '🔊 生成音频' }}
            </el-button>
            <el-tooltip
              content="删掉音频目录里已经找不到对应单词的 mp3（比如词库里已删除的词）。词库中的词一个都不会动，被删的音频下次复习到时会自动重新生成"
              placement="bottom"
            >
              <el-button
                size="small"
                type="info"
                plain
                :loading="cleaningAudio"
                @click="cleanupAudio"
              >
                {{ cleaningAudio ? '清理中...' : '🧹 清理音频' }}
              </el-button>
            </el-tooltip>
            <el-button
              size="small"
              type="warning"
              :disabled="selectedWords.length === 0"
              @click="openAdjustDialog"
            >
              🗂️ 调整词组
            </el-button>
            <el-tooltip
              content="把勾选的单词归到同一个同义标签下（如 attorney/barrister/solicitor 都标 lawyer）。之后按「同义标签」列排序，这些词就会排到一起"
              placement="bottom"
            >
              <el-button
                size="small"
                type="warning"
                plain
                :disabled="selectedWords.length === 0"
                @click="openSynonymDialog"
              >
                🏷 同义标签
              </el-button>
            </el-tooltip>
            <el-button
              size="small"
              type="danger"
              :disabled="selectedWords.length === 0"
              :loading="batchDeleting"
              @click="askBatchDelete"
            >
              🗑️ 批量删除 ({{ selectedWords.length }})
            </el-button>
          </template>
          <el-button
            v-else
            size="small"
            type="warning"
            :disabled="selectedWords.length === 0"
            :loading="removingFromDifficult"
            @click="removeFromDifficultGroup"
          >
            ➖ 移出易错词组 ({{ selectedWords.length }})
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
        <!-- 右栏：隐藏答案的三个开关 -->
        <el-col :span="6" style="text-align: right">
          <el-button :type="hideMeaning ? 'warning' : 'default'" size="small" @click="hideMeaning = !hideMeaning">
            {{ hideMeaning ? '显示含义' : '隐藏含义' }}
          </el-button>
          <el-button :type="hideExampleMeaning ? 'warning' : 'default'" size="small" @click="hideExampleMeaning = !hideExampleMeaning">
            {{ hideExampleMeaning ? '显示例句释义' : '隐藏例句释义' }}
          </el-button>
          <el-button :type="hideWord ? 'warning' : 'default'" size="small" @click="hideWord = !hideWord">
            {{ hideWord ? '显示单词' : '隐藏单词' }}
          </el-button>
        </el-col>
      </el-row>
    </el-card>

    <!-- 批量AI解析添加 -->
    <el-dialog v-model="showAiDialog" title="批量AI解析添加" width="640px" :close-on-click-modal="false">
      <div style="margin-bottom: 10px; font-size: 12px; color: #909399; line-height: 20px">
        每行一个单词，可直接把词表粘进来。重复行会自动去重，词库里已有的单词会自动跳过；
        AI 会把复数、过去式、-ing 等变形还原成<b>原形</b>后再入库。
      </div>
      <el-form label-width="80px" style="margin-bottom: 4px">
        <el-form-item label="所属词组">
          <el-tree-select
            v-model="aiGroupId"
            :data="groupOptions"
            check-strictly
            default-expand-all
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="选择目标词组"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <el-input
        v-model="aiInput"
        type="textarea"
        :rows="14"
        placeholder="running&#10;studies&#10;children&#10;went"
        style="font-size: 14px"
      />
      <div style="margin-top: 8px; font-size: 12px; color: #909399">
        共 {{ aiLineCount }} 行，去重后 {{ aiUniqueCount }} 个单词{{ aiUniqueCount ? '，将逐批交给 AI 解析，词多时请耐心等待' : '' }}
      </div>
      <template #footer>
        <el-button @click="showAiDialog = false">取消</el-button>
        <el-button type="primary" :loading="aiSubmitting" :disabled="aiUniqueCount === 0" @click="submitAiWords">
          添加
        </el-button>
      </template>
    </el-dialog>

    <!-- 词库表格 -->
    <el-card>
      <el-table
        ref="wordTableRef"
        :data="words"
        stripe
        border
        style="width: 100%"
        :default-sort="defaultSortProp"
        @selection-change="handleSelectionChange"
        @sort-change="handleSortChange"
      >
        <!-- fixed="left"：横向滚动时复选框列钉在最左侧，否则会随内容滚走 -->
        <el-table-column type="selection" width="50" align="center" fixed="left" />
        <el-table-column prop="id" label="ID" width="70" align="center" sortable="custom">
          <template #default="{ row }">
            <span class="id-link" @click="openViewDialog(row)">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="word" label="生词" width="150" sortable="custom">
          <template #default="{ row }">
            <span v-if="!hideWord">{{ row.word }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column prop="phonetic" label="音标" width="200">
          <template #default="{ row }">
            <template v-if="!hideWord">
              <el-button link size="small" @click="playWordAudio(row.word)" style="margin-right: 4px">
                🔊
              </el-button>
              {{ row.phonetic }}
            </template>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column prop="meaning" label="含义" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!hideMeaning">{{ row.meaning }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column prop="exampleSentence" label="例句" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!hideWord">{{ row.exampleSentence }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column prop="exampleMeaning" label="例句释义" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!hideExampleMeaning">{{ row.exampleMeaning }}</span>
            <span v-else style="color: #c0c4cc">***</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!difficultMode" label="词组" width="180">
          <template #default="{ row }">
            <el-tag v-for="g in row.groups" :key="g.id" size="small" style="margin: 1px 2px" :type="groupTagType(g)">
              {{ g.name }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="recentSpellWrong"
          label="最近拼错"
          width="110"
          align="center"
          sortable="custom"
        >
          <template #header>
            <el-tooltip
              :content="`最近 ${spellWindowSize} 次拼写（拼写练习/默写/听音默写）里拼错的次数；窗口次数可在「系统设置 → 易错词配置」调整`"
              placement="top"
            >
              <span>最近拼错 <el-icon style="vertical-align: -2px"><question-filled /></el-icon></span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-tag :type="row.recentSpellWrong > 0 ? 'danger' : 'info'" size="small">
              {{ row.recentSpellWrong ?? 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!difficultMode" prop="reviewTotal" label="复习次数" width="110" align="center" sortable="custom">
          <template #default="{ row }">{{ row.reviewTotal }}</template>
        </el-table-column>
        <!-- 同义标签列：替代原「状态」列。标签相同 = 意思相近，
             按本列排序即可把它们排到一起（勾选复习时自然连着过） -->
        <el-table-column prop="synonym" label="同义标签" width="120" align="center" sortable="custom">
          <template #default="{ row }">
            <el-tag v-if="row.synonym" type="warning" size="small">{{ row.synonym }}</el-tag>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!difficultMode" label="仅认读" width="80" align="center" fixed="right">
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
            <el-button type="danger" size="small" link @click="askDeleteWord(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="margin-top: 20px; display: flex; justify-content: center; align-items: center; gap: 12px">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="totalElements"
          :page-sizes="[20, 50, 100, 200, 500, 1000]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="onPageSizeChange"
          @current-change="loadWords"
        />
        <!-- el-pagination 的 sizes 只有下拉、手输不了，这里配一个输入框 -->
        <span style="color: #606266; font-size: 14px; white-space: nowrap">每页</span>
        <el-input-number
          v-model="pageSizeInput"
          :min="1"
          :max="1000"
          :step="10"
          size="small"
          controls-position="right"
          style="width: 110px"
          placeholder="1-1000"
          @change="applyPageSizeInput"
        />
        <span style="color: #606266; font-size: 14px; white-space: nowrap">条</span>
      </div>
    </el-card>

    <!-- 编辑弹窗 -->
    <!-- 同义标签弹窗 -->
    <el-dialog v-model="showSynonymDialog" title="🏷 同义标签" width="480px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="已选单词">
          <span style="color: #606266">
            {{ selectedWords.length }} 个：
            <span style="color: #909399; font-size: 12px">
              {{ selectedWords.slice(0, 8).map(w => w.word).join('、') }}{{ selectedWords.length > 8 ? ' 等' : '' }}
            </span>
          </span>
        </el-form-item>
        <el-form-item label="标签名">
          <el-select
            v-model="synonymInput"
            placeholder="输入或选择一个标签，如 lawyer"
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            style="width: 100%"
          >
            <el-option v-for="s in synonymOptions" :key="s" :label="s" :value="s" />
          </el-select>
          <div style="color: #909399; font-size: 12px; margin-top: 4px">
            标签相同的词就是一组近义词。想控制组与组之间的先后，在标签前加数字前缀即可
            （如 <code>1-lawyer</code> 排在 <code>2-colour</code> 前面）。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSynonymDialog = false">取消</el-button>
        <el-button type="warning" plain :disabled="!synonymInput" :loading="savingSynonym" @click="applySynonym">
          归组
        </el-button>
        <el-button type="info" plain :loading="savingSynonym" @click="clearSynonym">
          清除标签
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showEditDialog" title="编辑单词" width="580px" :close-on-click-modal="false">
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
        <el-form-item label="所属词组">
          <el-tree-select
            v-model="editForm.groupIds"
            :data="groupOptions"
            multiple
            show-checkbox
            check-strictly
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="选择所属词组（可多选）"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看单词（只读，与编辑弹窗同布局） -->
    <el-dialog v-model="showViewDialog" title="查看单词" width="580px" :close-on-click-modal="false">
      <el-form :model="viewForm" label-width="90px">
        <el-form-item label="ID">
          <el-input :model-value="viewForm.id" disabled />
        </el-form-item>
        <el-form-item label="生词">
          <el-input v-model="viewForm.word" disabled placeholder="英文单词" />
        </el-form-item>
        <el-form-item label="音标">
          <el-input v-model="viewForm.phonetic" disabled placeholder="/ˈeksəmpəl/" />
        </el-form-item>
        <el-form-item label="含义">
          <el-input v-model="viewForm.meaning" type="textarea" :rows="2" disabled placeholder="中文含义" />
        </el-form-item>
        <el-form-item label="例句">
          <el-input v-model="viewForm.exampleSentence" type="textarea" :rows="2" disabled placeholder="英文例句" />
        </el-form-item>
        <el-form-item label="例句释义">
          <el-input v-model="viewForm.exampleMeaning" type="textarea" :rows="2" disabled placeholder="例句的中文翻译" />
        </el-form-item>
        <el-form-item label="所属词组">
          <el-tree-select
            v-model="viewForm.groupIds"
            :data="groupOptions"
            multiple
            show-checkbox
            check-strictly
            disabled
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="所属词组"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="showViewDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增弹窗 -->
    <el-dialog v-model="showAddDialog" title="新增单词" width="580px" :close-on-click-modal="false">
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
        <el-form-item label="所属词组">
          <el-tree-select
            v-model="addForm.groupIds"
            :data="groupOptions"
            multiple
            show-checkbox
            check-strictly
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="选择所属词组（可多选）"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="addSaving" @click="saveAdd">添加</el-button>
      </template>
    </el-dialog>

    <!-- 调整词组弹窗 -->
    <el-dialog v-model="showAdjustDialog" title="🗂️ 调整词组" width="480px" :close-on-click-modal="false">
      <div style="padding: 6px 0">
        <p style="margin-bottom: 16px; color: #606266">
          已选择 <strong>{{ selectedWords.length }}</strong> 个单词，请设置其所属词组：
        </p>
        <el-form label-width="90px">
          <el-form-item label="操作方式">
            <el-radio-group v-model="adjustMode">
              <el-radio value="replace">覆盖（替换为所选词组）</el-radio>
              <el-radio value="add">追加（在原有基础上增加）</el-radio>
              <el-radio value="remove">移除（从所选词组中移除）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="所属词组">
            <el-tree-select
              v-model="adjustGroupIds"
              :data="groupOptions"
              multiple
              show-checkbox
              check-strictly
              node-key="value"
              :props="{ value: 'value', label: 'label', children: 'children' }"
              placeholder="选择词组（可多选）"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item v-if="adjustMode === 'replace'">
            <span style="color: #909399; font-size: 12px; line-height: 1.6">
              提示：覆盖后选中的单词将只保留所选词组。
            </span>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showAdjustDialog = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" @click="saveAdjustGroups">确定</el-button>
      </template>
    </el-dialog>

    <!-- 复习阶段选择弹窗 -->
    <el-dialog v-model="showReviewDialog" title="📖 选择复习阶段" width="440px" :close-on-click-modal="false">
      <div style="padding: 10px 0">
        <p style="margin-bottom: 16px; color: #606266">已选择 <strong>{{ selectedWords.length }}</strong> 个单词，请选择复习阶段：</p>
        <div style="display: flex; flex-direction: column; gap: 16px">
          <el-checkbox v-model="reviewPhases.recog" size="large" border style="padding: 12px 16px; width: 100%">
            <span style="font-size: 16px">📖 认读</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">英→中，看词识义</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.practice" size="large" border style="padding: 12px 16px; width: 100%">
            <span style="font-size: 16px">✏️ 拼写练习</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">显示信息+拼写</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.spell" size="large" border style="padding: 12px 16px; width: 100%">
            <span style="font-size: 16px">✍️ 默写</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">中→英，看义写词</span>
          </el-checkbox>
          <el-checkbox v-model="reviewPhases.dictation" size="large" border style="padding: 12px 16px; width: 100%">
            <span style="font-size: 16px">🎧 听音默写</span>
            <span style="color: #909399; margin-left: 8px; font-size: 13px">仅凭音频拼写</span>
          </el-checkbox>
        </div>
      </div>
      <!-- 分组复习 -->
      <el-divider style="margin: 16px 0 12px" />
      <div>
        <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 10px">
          <span style="color: #606266; white-space: nowrap">分组</span>
          <el-switch v-model="groupEnabled" />
          <span v-if="groupEnabled" style="color: #606266; font-size: 13px">分组复习</span>
        </div>
        <div v-if="groupEnabled" style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px">
          <span style="color: #606266; white-space: nowrap">每组</span>
          <el-input-number
            v-model="groupSize"
            :min="1"
            :max="9999"
            size="default"
            controls-position="right"
            style="width: 110px"
          />
          <span style="color: #606266; white-space: nowrap">个单词</span>
          <el-radio-group v-model="groupSize" size="small">
            <el-radio-button :value="10">10</el-radio-button>
            <el-radio-button :value="20">20</el-radio-button>
            <el-radio-button :value="50">50</el-radio-button>
          </el-radio-group>
        </div>
        <div style="color: #909399; font-size: 12px; line-height: 1.6">
          <template v-if="groupEnabled">
            共 <strong>{{ groupSize }}</strong> 个单词一组，划分为
            <strong>{{ groupCount }}</strong> 组；每组内先认读 → 拼写练习 → 默写 → 听音默写，
            一组全部阶段走完后再进入下一组。
          </template>
          <template v-else>
            不分组：先认读全部 {{ selectedWords.length }} 个单词，再拼写练习全部，再默写全部，再听音默写全部。
          </template>
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Search, QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import api from '../api'
import { loadGroupTree, toTreeOptions } from '../utils/group'

/**
 * 易错词库模式：整页复用词库管理，只做减法 ——
 * 固定查内置易错词组、隐藏词组筛选与新增/AI解析/生成音频/调整词组/批量删除，
 * 换成一个「移出易错词组」。编辑、删除、自定义复习仍然可用。
 */
const props = defineProps({
  difficultMode: { type: Boolean, default: false }
})

const router = useRouter()
const words = ref([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(50)
const totalElements = ref(0)
// 手动输入的每页条数：与下拉双向同步，但允许下拉里没有的任意值（1~1000）
const pageSizeInput = ref(50)
const PAGE_SIZE_MIN = 1
const PAGE_SIZE_MAX = 1000

/** 手动输入每页条数：clamp 到 1~1000 后跳回第 1 页重查 */
const applyPageSizeInput = (val) => {
  let n = Math.trunc(Number(val))
  if (!Number.isFinite(n) || val === null || val === undefined || val === '') {
    // 输入被清空时回落到当前生效值，不去改分页
    pageSizeInput.value = pageSize.value
    return
  }
  n = Math.min(PAGE_SIZE_MAX, Math.max(PAGE_SIZE_MIN, n))
  pageSizeInput.value = n
  if (n === pageSize.value) return
  pageSize.value = n
  currentPage.value = 1
  loadWords()
}
const hideMeaning = ref(false)
const hideExampleMeaning = ref(false)
// 隐藏生词、音标、例句（配合「复习」使用，避免直接看到答案）
const hideWord = ref(false)
const batchRunning = ref(false)
const batchChecking = ref(false)
const cleaningAudio = ref(false)

// ==================== 词组筛选 ====================
const groupTree = ref([])
const filterGroupId = ref(null)
// 同义标签筛选
const onlySynonymWords = ref(false)
const filterSynonym = ref(null)
const synonymOptions = ref([])
const showSynonymDialog = ref(false)
const synonymInput = ref('')
const savingSynonym = ref(false)
const groupOptions = computed(() => toTreeOptions(groupTree.value))
const defaultGroupId = ref(null)

const loadGroups = async () => {
  try {
    groupTree.value = await loadGroupTree()
    // 找到默认词组用于新增时默认勾选
    const findDefault = (nodes) => {
      for (const n of nodes) {
        if (n.isDefault) return n.id
        const inChild = findDefault(n.children || [])
        if (inChild) return inChild
      }
      return null
    }
    defaultGroupId.value = findDefault(groupTree.value)
  } catch (e) {
    ElMessage.error('加载词组失败')
  }
}

const onFilterGroupChange = () => {
  currentPage.value = 1
  loadWords()
}

// 易错词统计窗口长度（只影响表头提示），默认 30 与后端一致
const spellWindowSize = ref(30)
// 内置易错词组ID，易错词库模式下固定按它筛选
const difficultGroupId = ref(null)

const loadDifficultConfig = async () => {
  try {
    const res = await api.get('/difficult/config')
    if (res.windowSize) spellWindowSize.value = res.windowSize
    difficultGroupId.value = res.groupId ?? null
  } catch {
    // 拿不到就沿用默认值，不影响词库列表
  }
}

// ==================== 移出易错词组 ====================
const removingFromDifficult = ref(false)

/** 把勾选的单词移出易错词组（单词本身保留，若只剩它一个归属会自动落到默认词组） */
const removeFromDifficultGroup = async () => {
  if (selectedWords.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定把勾选的 ${selectedWords.value.length} 个单词移出易错词组吗？单词本身和其他词组归属都会保留。`,
      '移出易错词组',
      { type: 'warning', confirmButtonText: '移出', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  removingFromDifficult.value = true
  try {
    const res = await api.post('/words/batch-delete', {
      wordIds: selectedWords.value.map(w => w.id),
      mode: 'group',
      groupId: difficultGroupId.value
    })
    ElMessage.success(res.message || '已移出易错词组')
    wordTableRef.value?.clearSelection()
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '移出易错词组失败')
  } finally {
    removingFromDifficult.value = false
  }
}

/** 词组标签配色：易错词组用警告色，内置默认词组用危险色，其余灰色 */
const groupTagType = (g) => {
  if (g.isDifficult) return 'warning'
  if (g.isDefault) return 'danger'
  return 'info'
}

// 排序状态：易错词库默认按「最近拼错」倒序，把拼错最多的排最前；词库管理默认按 ID 正序
const sortField = ref(props.difficultMode ? 'recentSpellWrong' : 'id')
const sortOrder = ref(props.difficultMode ? 'desc' : 'asc')

/** 取消排序时回落到的默认排序 */
const defaultSort = () => ({
  field: props.difficultMode ? 'recentSpellWrong' : 'id',
  order: props.difficultMode ? 'desc' : 'asc'
})

// 表头排序箭头的初始状态，不设的话列表虽然排好了、箭头却不显示
const defaultSortProp = computed(() => ({
  prop: sortField.value,
  order: sortOrder.value === 'desc' ? 'descending' : 'ascending'
}))

const handleSortChange = ({ prop, order }) => {
  if (prop && order) {
    sortField.value = prop
    sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  } else {
    // 取消排序时恢复默认
    const d = defaultSort()
    sortField.value = d.field
    sortOrder.value = d.order
  }
  loadWords()
}

// 表格多选
const wordTableRef = ref(null)
const selectedWords = ref([])

const handleSelectionChange = (selection) => {
  selectedWords.value = selection
}

/** 清空表格勾选（批量操作后调用） */
const clearSelection = () => {
  wordTableRef.value?.clearSelection()
  selectedWords.value = []
}

// 复习弹窗
const showReviewDialog = ref(false)
const reviewPhases = ref({ recog: true, practice: true, spell: true, dictation: true })
// 分组复习：groupEnabled=false 即不分组（现有行为）
const groupEnabled = ref(false)
const groupSize = ref(50)

/** 实际每组数量：小于 1 时退回全部单词（即不分组） */
const effectiveGroupSize = computed(() =>
  groupEnabled.value && groupSize.value >= 1 ? groupSize.value : selectedWords.value.length
)

/** 分组数 */
const groupCount = computed(() =>
  Math.max(1, Math.ceil(selectedWords.value.length / effectiveGroupSize.value))
)

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
  // groupSize 传 0 表示不分组
  const gs = groupEnabled.value && groupSize.value >= 1 ? groupSize.value : 0
  showReviewDialog.value = false
  router.push({ path: '/review', query: { ids, phases: phases.join(','), groupSize: String(gs) } })
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

/** 字节数转人话 */
const formatSize = (bytes) => {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 清理无用音频：先 dry-run 扫一遍拿到数量，让用户看清楚再删。
 * 「无用」= 音频文件对应的单词已经不在词库里了，程序再也不会去读这个文件。
 */
const cleanupAudio = async () => {
  cleaningAudio.value = true
  let preview = null
  try {
    preview = await api.post('/audio/cleanup', null, { params: { dryRun: true } })
  } catch {
    cleaningAudio.value = false
    ElMessage.error('扫描无用音频失败')
    return
  }

  const orphan = preview?.orphanFiles ?? 0
  if (orphan === 0) {
    cleaningAudio.value = false
    ElMessage.success(`音频目录很干净，${preview?.totalFiles ?? 0} 个 mp3 全部对应词库里的单词`)
    return
  }

  const samples = (preview?.samples || []).slice(0, 8).map(f => `  ${f}`).join('\n')
  const more = orphan > 8 ? `\n  ……还有 ${orphan - 8} 个` : ''
  try {
    await ElMessageBox.confirm(
      `音频目录共 ${preview?.totalFiles ?? 0} 个 mp3，其中 ${orphan} 个已经找不到对应的单词` +
        `（词库用到 ${preview?.keptFiles ?? 0} 个），删除后可释放约 ${formatSize(preview?.freedBytes)}。\n\n` +
        `将删除的文件示例：\n${samples}${more}\n\n` +
        `词库里的词一个都不会动；万一删错了，对应单词下次复习到时会自动重新生成。`,
      '清理无用音频',
      { type: 'warning', confirmButtonText: `确认删除 ${orphan} 个`, cancelButtonText: '取消' }
    )
  } catch {
    cleaningAudio.value = false
    return   // 用户取消
  }

  try {
    const res = await api.post('/audio/cleanup')
    if (res?.success === false) {
      ElMessage.error(res.message || '清理失败')
    } else if (res?.failedFiles > 0) {
      ElMessage.warning(res.message || `删除失败 ${res.failedFiles} 个`)
    } else {
      ElMessage.success(`${res?.message || '清理完成'}，释放 ${formatSize(res?.freedBytes)}`)
    }
  } catch {
    ElMessage.error('清理无用音频失败')
  }
  cleaningAudio.value = false
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

/** 下拉切换每页条数：同步手动输入框的显示值 */
const onPageSizeChange = (val) => {
  pageSizeInput.value = val
  loadWords()
}

// ==================== 同义标签 ====================

/** 拉取已用过的全部标签，喂给筛选下拉和弹窗里的可选输入 */
const loadSynonymOptions = async () => {
  try {
    const res = await api.get('/words/synonyms')
    synonymOptions.value = res.synonyms || []
  } catch {
    // 拉不到就不给选项，不影响手动输入
  }
}

/** 筛选条件变化：回到第 1 页重查 */
const onSynonymFilterChange = () => {
  currentPage.value = 1
  loadWords()
}

/** 勾选未变时打开弹窗，预填「所有选中词的共同标签」（有则带上，方便追加同组） */
const openSynonymDialog = () => {
  const tags = new Set(selectedWords.value.map(w => w.synonym).filter(Boolean))
  synonymInput.value = tags.size === 1 ? [...tags][0] : ''
  showSynonymDialog.value = true
}

const doSetSynonym = async (value) => {
  const ids = selectedWords.value.map(w => w.id)
  if (ids.length === 0) return
  savingSynonym.value = true
  try {
    const res = await api.post('/words/batch-synonym', { wordIds: ids, synonym: value })
    if (res.success) {
      ElMessage.success(res.message)
      showSynonymDialog.value = false
      clearSelection()
      await loadSynonymOptions()
      loadWords()
    } else {
      ElMessage.error(res.message || '设置失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '设置同义标签失败')
  } finally {
    savingSynonym.value = false
  }
}

const applySynonym = () => {
  const tag = (synonymInput.value || '').trim()
  if (!tag) {
    ElMessage.warning('请输入标签名')
    return
  }
  doSetSynonym(tag)
}

const clearSynonym = () => {
  doSetSynonym('')
}

const loadWords = async () => {
  try {
    const res = await api.get('/words/page', {
      params: {
        page: currentPage.value,
        size: pageSize.value,
        keyword: keyword.value || undefined,
        sortField: sortField.value,
        sortOrder: sortOrder.value,
        // 易错词库模式固定查内置易错词组（含其子词组），忽略词组筛选下拉
        groupId: props.difficultMode ? difficultGroupId.value : (filterGroupId.value || undefined),
        // 同义标签筛选（易错词库页也照常可用）
        hasSynonym: onlySynonymWords.value ? true : undefined,
        synonym: filterSynonym.value || undefined
      }
    })
    words.value = res.content
    totalElements.value = res.totalElements
  } catch (e) {
    ElMessage.error('加载词库失败')
  }
}

// ==================== 删除单词 ====================
/**
 * 弹出「从本词组移除 / 从所有词组彻底删除」二选一。
 * - 从本词组移除：单词本身保留；若它仅属于该词组，后端会把它落到默认词组
 * - 彻底删除：单词及其所有词组关联、复习统计一并清除，不可恢复
 */
const askDeleteWord = async (row) => {
  const groupCount = (row.groups || []).length
  const inFilter = filterGroupId.value
  const scope = inFilter ? '当前筛选的词组' : '词库'

  let msg = `「${row.word}」当前属于 ${groupCount} 个词组，请选择删除方式。`
  if (inFilter) {
    msg = groupCount <= 1
      ? `「${row.word}」仅属于当前筛选的词组。从本词组移除后，它会被自动移入「默认词组」，单词本身保留。`
      : `「${row.word}」当前属于 ${groupCount} 个词组。确定要从当前筛选的词组移除它吗？`
  }

  try {
    const choice = await ElMessageBox.confirm(msg, `从${scope}删除`, {
      distinguishCancelAndClose: true,
      confirmButtonText: '从本词组移除',
      cancelButtonText: '彻底删除单词',
      type: 'warning',
      customClass: 'delete-word-box'
    })
    if (choice === 'confirm') {
      await removeFromGroup(row, inFilter)
    } else {
      await purgeWord(row)
    }
  } catch (e) {
    // 'cancel' = 选了彻底删除；'close'（点 X/遮罩）= 取消，不做任何事
    if (e === 'cancel') {
      await purgeWord(row)
    }
  }
}

/** 从当前词组移除，保留单词 */
const removeFromGroup = async (row, groupId) => {
  try {
    const res = groupId != null
      ? await api.delete(`/words/${row.id}/group/${groupId}`)
      : await api.delete(`/words/${row.id}`, { params: { mode: 'group' } })
    ElMessage.success(res?.message || '已从词组移除')
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

/** 从所有词组彻底删除单词 */
const purgeWord = async (row) => {
  try {
    await ElMessageBox.confirm(
      `将彻底删除「${row.word}」及其所有词组关联和复习统计，此操作不可恢复。`,
      '彻底删除确认',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return   // 用户在二次确认中取消
  }
  try {
    await api.delete(`/words/${row.id}`)
    ElMessage.success('已彻底删除')
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

// ==================== 批量删除 ====================
const batchDeleting = ref(false)

const askBatchDelete = async () => {
  const rows = selectedWords.value
  if (rows.length === 0) {
    ElMessage.warning('请先选择要删除的单词')
    return
  }
  const inFilter = filterGroupId.value
  const onlyInFilter = rows.filter(r => (r.groups || []).length <= 1).length
  const willFallback = inFilter != null ? onlyInFilter : rows.length

  let msg = `已选择 ${rows.length} 个单词，请选择删除方式。`
  if (inFilter) {
    msg = willFallback > 0
      ? `已选择 ${rows.length} 个单词，其中 ${willFallback} 个仅属于当前筛选的词组。` +
        `从本词组移除后，这些单词会被自动移入「默认词组」，单词本身保留。`
      : `已选择 ${rows.length} 个单词，确定要从当前筛选的词组移除它们吗？`
  }

  let choice
  try {
    choice = await ElMessageBox.confirm(msg, `批量删除（${rows.length} 个）`, {
      distinguishCancelAndClose: true,
      confirmButtonText: '从本词组移除',
      cancelButtonText: '彻底删除',
      type: 'warning'
    })
  } catch (e) {
    if (e !== 'cancel') return   // 'close' = 放弃操作
    choice = 'cancel'
  }
  if (choice === 'confirm') {
    await batchRemoveFromGroup(rows, inFilter)
  } else {
    await batchPurgeWords(rows)
  }
}

/** 批量从当前词组移除，保留单词 */
const batchRemoveFromGroup = async (rows, groupId) => {
  if (batchDeleting.value) return
  batchDeleting.value = true
  try {
    const res = await api.post('/words/batch-delete', {
      wordIds: rows.map(w => w.id),
      mode: 'group',
      groupId: groupId != null ? groupId : undefined
    })
    ElMessage.success(res?.message || '已从词组移除')
    clearSelection()
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '批量移出失败')
  } finally {
    batchDeleting.value = false
  }
}

/** 批量从所有词组彻底删除单词 */
const batchPurgeWords = async (rows) => {
  try {
    await ElMessageBox.confirm(
      `将彻底删除选中的 ${rows.length} 个单词及其所有词组关联和复习统计，此操作不可恢复。`,
      '彻底删除确认',
      { type: 'error', confirmButtonText: `确认删除 ${rows.length} 个`, cancelButtonText: '取消' }
    )
  } catch {
    return   // 二次确认取消
  }
  if (batchDeleting.value) return
  batchDeleting.value = true
  try {
    const res = await api.post('/words/batch-delete', {
      wordIds: rows.map(w => w.id),
      mode: 'word'
    })
    ElMessage.success(res?.message || '已彻底删除')
    clearSelection()
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '批量删除失败')
  } finally {
    batchDeleting.value = false
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
  exampleMeaning: '',
  groupIds: []
})

const openEditDialog = (row) => {
  editForm.value = {
    id: row.id,
    word: row.word,
    phonetic: row.phonetic || '',
    meaning: row.meaning || '',
    exampleSentence: row.exampleSentence || '',
    exampleMeaning: row.exampleMeaning || '',
    groupIds: (row.groups || []).map(g => g.id)
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
    // 更新词组归属
    await api.put(`/words/${editForm.value.id}/groups`, { groupIds: editForm.value.groupIds })
    ElMessage.success('保存成功')
    showEditDialog.value = false
    loadWords()
  } catch (e) {
    const msg = e?.response?.data?.message || '保存失败'
    ElMessage.error(msg)
  }
  editSaving.value = false
}

// ==================== 查看单词（只读） ====================
const showViewDialog = ref(false)
const viewForm = ref({
  id: null,
  word: '',
  phonetic: '',
  meaning: '',
  exampleSentence: '',
  exampleMeaning: '',
  groupIds: []
})

/** 点表格 ID 列打开：字段与编辑弹窗一致，全部禁用不可改 */
const openViewDialog = (row) => {
  viewForm.value = {
    id: row.id,
    word: row.word,
    phonetic: row.phonetic || '',
    meaning: row.meaning || '',
    exampleSentence: row.exampleSentence || '',
    exampleMeaning: row.exampleMeaning || '',
    groupIds: (row.groups || []).map(g => g.id)
  }
  showViewDialog.value = true
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
// ==================== 批量AI解析添加 ====================
const showAiDialog = ref(false)
const aiInput = ref('')
/** 解析结果要归入的词组（单选），打开弹窗时按当前筛选词组 / 默认词组初始化 */
const aiGroupId = ref(null)
const aiSubmitting = ref(false)

/** 弹窗里非空行数 */
const aiLineCount = computed(() =>
  aiInput.value.split(/[\r\n]+/).map(v => v.trim()).filter(Boolean).length
)

/** 去重后的单词数（大小写不敏感，保留首次出现的原样） */
const aiUniqueCount = computed(() => {
  const seen = new Set()
  for (const line of aiInput.value.split(/[\r\n]+/)) {
    const w = line.trim()
    if (w) seen.add(w.toLowerCase())
  }
  return seen.size
})

/** 打开弹窗：先检查 AI 配置，未配置则提示并返回；每次打开都清空文本框 */
const openAiDialog = async () => {
  let cfg = null
  try {
    cfg = await api.get('/ai/config')
  } catch (e) {
    ElMessage.error('检查 AI 配置失败：' + (e.response?.data?.message || e.message))
    return
  }
  if (!cfg || !cfg.configured) {
    ElMessageBox.confirm(
      '还没有配置 AI 服务商，批量解析前需要先填写服务商、API Key 和接口地址。',
      '未配置 AI',
      { confirmButtonText: '去配置', cancelButtonText: '取消', type: 'warning' }
    ).then(() => router.push('/ai')).catch(() => {})
    return
  }

  aiInput.value = ''
  // 默认带上当前筛选的词组，没筛选就落默认词组
  aiGroupId.value = filterGroupId.value || defaultGroupId.value
  showAiDialog.value = true
}

/** 提交：前端先去重，后端会再排重、排除词库已有词并调用 AI */
const submitAiWords = async () => {
  const seen = new Set()
  const words = []
  for (const line of aiInput.value.split(/[\r\n]+/)) {
    const w = line.trim()
    if (!w) continue
    const key = w.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    words.push(w)
  }
  if (words.length === 0) {
    ElMessage.warning('请至少输入一个单词')
    return
  }

  aiSubmitting.value = true
  try {
    const res = await api.post(
      '/words/batch-ai',
      { words, groupId: aiGroupId.value || defaultGroupId.value || null },
      { timeout: 300000 }
    )
    showAiDialog.value = false
    ElMessage({ type: 'success', message: res.message, duration: 6000, showClose: true })
    if (res.failed > 0 && Array.isArray(res.failedWords) && res.failedWords.length) {
      ElMessage({
        type: 'warning',
        message: '解析失败：' + res.failedWords.join('、'),
        duration: 8000,
        showClose: true
      })
    }
    loadWords()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '批量添加失败')
  } finally {
    aiSubmitting.value = false
  }
}

const showAddDialog = ref(false)
const addSaving = ref(false)
const addForm = ref({
  word: '',
  phonetic: '',
  meaning: '',
  exampleSentence: '',
  exampleMeaning: '',
  groupIds: []
})

const openAddDialog = () => {
  addForm.value = {
    word: '',
    phonetic: '',
    meaning: '',
    exampleSentence: '',
    exampleMeaning: '',
    groupIds: defaultGroupId.value ? [defaultGroupId.value] : []
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
      exampleMeaning: addForm.value.exampleMeaning,
      groupIds: addForm.value.groupIds
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

// ==================== 调整词组 ====================
const showAdjustDialog = ref(false)
const adjustSaving = ref(false)
const adjustMode = ref('replace')
const adjustGroupIds = ref([])

const openAdjustDialog = () => {
  if (selectedWords.value.length === 0) {
    ElMessage.warning('请先选择要调整的单词')
    return
  }
  adjustMode.value = 'replace'
  adjustGroupIds.value = []
  showAdjustDialog.value = true
}

const saveAdjustGroups = async () => {
  if (adjustGroupIds.value.length === 0) {
    ElMessage.warning('请至少选择一个词组')
    return
  }
  adjustSaving.value = true
  try {
    const res = await api.post('/words/batch-groups', {
      wordIds: selectedWords.value.map(w => w.id),
      groupIds: adjustGroupIds.value,
      mode: adjustMode.value
    })
    ElMessage.success(res.message || '词组已调整')
    showAdjustDialog.value = false
    loadWords()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '调整词组失败')
  }
  adjustSaving.value = false
}

onMounted(async () => {
  checkBatchStatus()
  // 易错词库模式必须先拿到易错词组ID再查，否则 groupId 为空会查成整个词库
  await loadDifficultConfig()
  await loadGroups()
  loadSynonymOptions()
  loadWords()
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

/* ID 列：可点击打开查看弹窗，带下划线提示可点 */
.id-link {
  color: #409eff;
  text-decoration: underline;
  cursor: pointer;
}

.id-link:hover {
  color: #66b1ff;
}
</style>
