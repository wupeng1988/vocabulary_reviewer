import api from '../api'

/** 加载词组树 */
export async function loadGroupTree() {
  const res = await api.get('/groups/tree')
  return res
}

/** 转成 el-tree-select 需要的选项结构 */
export function toTreeOptions(nodes) {
  return nodes.map(n => ({
    value: n.id,
    label: `${n.code} ${n.name}`,
    children: n.children && n.children.length ? toTreeOptions(n.children) : []
  }))
}

/** 在树中按 id 查找节点（含默认词组） */
export function findNode(nodes, id) {
  for (const n of nodes) {
    if (n.id === id) return n
    if (n.children && n.children.length) {
      const found = findNode(n.children, id)
      if (found) return found
    }
  }
  return null
}

/** 递归查找默认词组 */
export function findDefaultNode(nodes) {
  for (const n of nodes) {
    if (n.isDefault) return n
    if (n.children && n.children.length) {
      const found = findDefaultNode(n.children)
      if (found) return found
    }
  }
  return null
}

export default { loadGroupTree, toTreeOptions, findNode, findDefaultNode }
