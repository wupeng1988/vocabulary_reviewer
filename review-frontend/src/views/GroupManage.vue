<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span style="font-size: 16px; font-weight: bold">🗂️ 词组管理</span>
          <div>
            <el-button size="small" @click="toggleExpandAll">全部{{ expandAll ? '收起' : '展开' }}</el-button>
            <el-button size="small" :disabled="sortSaving" @click="toggleSortEdit">
              {{ sortEditing ? '✓ 完成排序编辑' : '⚡ 排序行内编辑' }}
            </el-button>
            <el-button size="small" type="primary" @click="openAddDialog(null)">➕ 新增根词组</el-button>
          </div>
        </div>
      </template>

      <el-table
        :key="tableKey"
        :data="groupTree"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :default-expand-all="expandAll"
        border
        stripe
      >
        <el-table-column prop="code" label="编号" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" type="danger" size="small" style="margin-right: 6px">默认</el-tag>
            <span>{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="260" />
        <el-table-column prop="sortOrder" label="排序" width="140" align="center">
          <template #default="{ row }">
            <template v-if="sortEditing">
              <el-input-number
                v-model="row.sortOrder"
                size="small"
                :min="0"
                :max="9999"
                controls-position="right"
                style="width: 100px"
                @change="(v) => saveSort(row, v)"
              />
            </template>
            <template v-else>
              <el-tag v-if="row.sortOrder" size="small" type="warning">{{ row.sortOrder }}</el-tag>
              <span v-else style="color: #c0c4cc">0</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="wordCount" label="单词数" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row.wordCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" align="center">
          <template #default="{ row }">
            <el-button type="success" size="small" link @click="openAddDialog(row)">➕ 子词组</el-button>
            <el-button type="primary" size="small" link @click="openEditDialog(row)">编辑</el-button>
            <el-popconfirm title="确定删除该词组？" @confirm="deleteGroup(row)">
              <template #reference>
                <el-button type="danger" size="small" link :disabled="row.isDefault">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div style="color: #909399; font-size: 12px; margin-top: 14px">
        提示：默认词组自动包含所有未归属于任何词组的单词，且不可删除。删除词组前需先删除其下的子词组。<br />
        「排序」数值越小越靠前，词组管理列表、词组筛选、数据管理导入词组和按词组导出 Excel 均按此顺序排列。<br />
        点击「排序行内编辑」可直接在表格中修改排序值，改动即时保存。
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="showDialog" :title="isEdit ? '编辑词组' : '新增词组'" width="420px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px">
        <el-form-item label="编号" required>
          <el-input v-model="form.code" placeholder="留空自动生成，如 G001" :disabled="isEdit && form.isDefault">
            <template #append>唯一</template>
          </el-input>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="词组名称" />
        </el-form-item>
        <el-form-item label="排序" required>
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" :step="1" controls-position="right" style="width: 160px" />
          <div style="color: #909399; font-size: 12px">数值越小越靠前，相同则按编号排序</div>
        </el-form-item>
        <el-form-item label="上级词组">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            check-strictly
            clearable
            node-key="value"
            :props="{ value: 'value', label: 'label', children: 'children' }"
            placeholder="不选则为根词组"
            style="width: 100%"
          />
          <div v-if="isEdit" style="color: #909399; font-size: 12px">
            挪动上级时，该词组及其子孙会整体移动
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { loadGroupTree } from '../utils/group'

const groupTree = ref([])
const expandAll = ref(true)
const tableKey = ref(0)

const loadGroups = async () => {
  try {
    const tree = await loadGroupTree()
    // 记录已保存的排序值，供行内编辑判断是否真的改动
    const stamp = (nodes) => {
      ;(nodes || []).forEach(n => {
        n.__savedSort = n.sortOrder ?? 0
        stamp(n.children)
      })
    }
    stamp(tree)
    groupTree.value = tree
  } catch (e) {
    ElMessage.error('加载词组失败')
  }
}

const toggleExpandAll = () => {
  expandAll.value = !expandAll.value
  tableKey.value++  // 重挂载以应用展开/收起
}

// ==================== 排序字段行内编辑 ====================
const sortEditing = ref(false)
const sortSaving = ref(false)

const toggleSortEdit = () => {
  if (sortSaving.value) return
  sortEditing.value = !sortEditing.value
  if (sortEditing.value) {
    // 按当前排序值把同级兄弟节点排好，保证输入框顺序与排序值一致
    sortSiblings(groupTree.value)
  } else {
    loadGroups()
  }
}

/** 递归按 sortOrder → code 对每层子节点排序 */
const sortSiblings = (nodes) => {
  if (!nodes) return
  nodes.sort((a, b) =>
    (a.sortOrder ?? 0) - (b.sortOrder ?? 0) ||
    String(a.code).localeCompare(String(b.code))
  )
  nodes.forEach(n => sortSiblings(n.children))
}

const saveSort = async (row, value) => {
  const next = Number.isFinite(value) ? value : 0
  if (next === row.__savedSort) return   // 无变化不请求
  sortSaving.value = true
  try {
    await api.put(`/groups/${row.id}`, { parentId: row.parentId, sortOrder: next })
    row.__savedSort = next
    ElMessage.success('排序已更新')
    // 兄弟顺序可能变化，重新拉取以刷新整棵树
    await loadGroups()
  } catch (e) {
    row.sortOrder = row.__savedSort ?? 0   // 回滚
    ElMessage.error(e?.response?.data?.message || '排序更新失败')
  } finally {
    sortSaving.value = false
  }
}

// ==================== 新增 / 编辑 ====================
const showDialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = ref({ id: null, code: '', name: '', parentId: null, sortOrder: 0, isDefault: false })
const editingRow = ref(null)

// 上级词组下拉（编辑时排除自身及其子孙，避免成环）
const parentOptions = computed(() => {
  const selfDescendantIds = new Set()
  collectDescendantIds(editingRow.value, selfDescendantIds)
  const build = (nodes) => nodes
    .filter(n => n.id !== editingRow.value?.id)
    .map(n => ({
      value: n.id,
      label: `${n.code} ${n.name}`,
      disabled: n.id === editingRow.value?.id || selfDescendantIds.has(n.id),
      children: build(n.children || [])
    }))
  return build(groupTree.value)
})

const collectDescendantIds = (node, set) => {
  if (!node) return
  for (const c of node.children || []) {
    set.add(c.id)
    collectDescendantIds(c, set)
  }
}

const openAddDialog = (parent) => {
  isEdit.value = false
  editingRow.value = null
  form.value = { id: null, code: '', name: '', parentId: parent ? parent.id : null, sortOrder: 0, isDefault: false }
  showDialog.value = true
}

const openEditDialog = (row) => {
  isEdit.value = true
  editingRow.value = row
  form.value = { id: row.id, code: row.code, name: row.name, parentId: row.parentId, sortOrder: row.sortOrder ?? 0, isDefault: row.isDefault }
  showDialog.value = true
}

const saveGroup = async () => {
  if (!form.value.name.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await api.put(`/groups/${form.value.id}`, {
        code: form.value.code,
        name: form.value.name,
        parentId: form.value.parentId,
        sortOrder: form.value.sortOrder
      })
      ElMessage.success('更新成功')
    } else {
      await api.post('/groups', {
        code: form.value.code || undefined,
        name: form.value.name,
        parentId: form.value.parentId,
        sortOrder: form.value.sortOrder
      })
      ElMessage.success('新增成功')
    }
    showDialog.value = false
    loadGroups()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  }
  saving.value = false
}

const deleteGroup = async (row) => {
  try {
    await api.delete(`/groups/${row.id}`)
    ElMessage.success('删除成功')
    loadGroups()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

onMounted(loadGroups)
</script>
