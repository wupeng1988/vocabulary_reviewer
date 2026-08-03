import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器
api.interceptors.request.use(config => {
  return config
}, error => {
  return Promise.reject(error)
})

// 响应拦截器
api.interceptors.response.use(response => {
  return response.data
}, error => {
  console.error('API Error:', error)
  return Promise.reject(error)
})

/**
 * 导出全部数据为 JSON 文件（需要 blob 响应，绕过响应拦截器）
 */
export function exportData() {
  return axios.get('/api/words/export', {
    responseType: 'blob'
  })
}

/**
 * 导出全部单词为 Excel 文件（需要 blob 响应，绕过响应拦截器）
 */
export function exportExcel() {
  return axios.get('/api/words/export-excel', {
    responseType: 'blob'
  })
}

export default api
