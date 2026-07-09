import { useCallback, useEffect, useMemo, useState } from 'react'

const API = import.meta.env.VITE_API_BASE_URL || ''

const formatBytes = (bytes = 0) => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}

const formatDuration = (seconds = 0) => {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

const formatDate = (value) => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(new Date(value))

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.error || `请求失败 (${response.status})`)
  }
  return response.status === 204 ? null : response.json()
}

function Icon({ name }) {
  const icons = {
    play: <path d="m9 7 8 5-8 5V7Z" />,
    lock: <><rect x="6" y="10" width="12" height="9" rx="2"/><path d="M9 10V7a3 3 0 0 1 6 0v3"/></>,
    unlock: <><rect x="6" y="10" width="12" height="9" rx="2"/><path d="M9 10V7a3 3 0 0 1 5.4-1.8"/></>,
    download: <><path d="M12 3v12m0 0 4-4m-4 4-4-4"/><path d="M5 20h14"/></>,
    trash: <><path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7"/><path d="M10 11v5m4-5v5"/></>,
    refresh: <><path d="M20 6v5h-5"/><path d="M18.5 16a8 8 0 1 1 .7-8.7L20 11"/></>,
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true">{icons[name]}</svg>
}

export default function App() {
  const [videos, setVideos] = useState([])
  const [storage, setStorage] = useState(null)
  const [online, setOnline] = useState(null)
  const [selected, setSelected] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [date, setDate] = useState('')
  const [lockFilter, setLockFilter] = useState('all')

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const params = new URLSearchParams({ page: '1', pageSize: '200' })
      if (date) params.set('date', date)
      if (lockFilter !== 'all') params.set('locked', lockFilter)
      const [health, list, status] = await Promise.all([
        api('/api/health'), api(`/api/videos?${params}`), api('/api/storage/status'),
      ])
      setOnline(health.status === 'ok')
      setVideos(list.items)
      setStorage(status)
    } catch (err) {
      setOnline(false)
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [date, lockFilter])

  useEffect(() => { refresh() }, [refresh])

  const storagePercent = useMemo(() => storage
    ? Math.min(100, storage.totalSizeBytes / storage.maxStorageBytes * 100) : 0, [storage])

  const toggleLock = async (video) => {
    try {
      const updated = await api(`/api/videos/${video.id}/lock`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ locked: !video.locked }),
      })
      setVideos(items => items.map(item => item.id === updated.id ? updated : item))
      if (selected?.id === updated.id) setSelected(updated)
    } catch (err) { setError(err.message) }
  }

  const remove = async (video) => {
    if (!window.confirm(`永久删除 ${video.originalFilename || video.filename}？`)) return
    try {
      await api(`/api/videos/${video.id}`, { method: 'DELETE' })
      if (selected?.id === video.id) setSelected(null)
      await refresh()
    } catch (err) { setError(err.message) }
  }

  return <div className="shell">
    <header>
      <div className="brand"><span className="brand-mark">DC</span><div><strong>Dashcam Archive</strong><small>本地行车记录仪视频库</small></div></div>
      <div className={`status ${online === true ? 'online' : online === false ? 'offline' : ''}`}>
        <span /> {online === true ? '服务器在线' : online === false ? '服务器离线' : '正在检测'}
      </div>
    </header>

    <main>
      <section className="hero">
        <div><p className="eyebrow">LOCAL STORAGE</p><h1>行车影像，一处归档。</h1><p>浏览、保护和管理旧手机自动上传的视频片段。</p></div>
        <button className="refresh" onClick={refresh} disabled={loading}><Icon name="refresh" />刷新</button>
      </section>

      <section className="metrics">
        <article><span>视频总数</span><strong>{storage?.totalVideoCount ?? '—'}</strong><small>已归档片段</small></article>
        <article><span>已用空间</span><strong>{storage ? formatBytes(storage.totalSizeBytes) : '—'}</strong><small>上限 {storage ? formatBytes(storage.maxStorageBytes) : '—'}</small></article>
        <article className="capacity"><span>存储容量</span><strong>{storagePercent.toFixed(1)}%</strong><div><i style={{ width: `${storagePercent}%` }} /></div><small>剩余 {storage ? formatBytes(storage.availableSpaceBytes) : '—'}</small></article>
      </section>

      {error && <div className="error">{error}</div>}

      <section className="archive">
        <div className="section-head"><div><p className="eyebrow">VIDEO ARCHIVE</p><h2>视频记录</h2></div><div className="filters">
          <input type="date" value={date} onChange={e => setDate(e.target.value)} aria-label="按日期筛选" />
          <select value={lockFilter} onChange={e => setLockFilter(e.target.value)} aria-label="按锁定状态筛选">
            <option value="all">全部状态</option><option value="true">已锁定</option><option value="false">未锁定</option>
          </select>
        </div></div>

        <div className="table-wrap"><table><thead><tr><th>录制时间</th><th>文件</th><th>时长</th><th>大小</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>{videos.map(video => <tr key={video.id}>
            <td>{formatDate(video.startTime)}</td>
            <td className="file"><span>{video.originalFilename || video.filename}</span><small>#{video.id}</small></td>
            <td>{formatDuration(video.durationSeconds)}</td><td>{formatBytes(video.fileSizeBytes)}</td>
            <td><span className={`pill ${video.locked ? 'locked' : ''}`}>{video.locked ? '已锁定' : '普通'}</span></td>
            <td><div className="actions">
              <button title="播放" onClick={() => setSelected(video)}><Icon name="play" /></button>
              <a title="下载" href={`${API}/api/videos/${video.id}/download`}><Icon name="download" /></a>
              <button title={video.locked ? '解锁' : '锁定'} onClick={() => toggleLock(video)}><Icon name={video.locked ? 'unlock' : 'lock'} /></button>
              <button className="danger" title="删除" onClick={() => remove(video)}><Icon name="trash" /></button>
            </div></td>
          </tr>)}</tbody></table>
          {!loading && videos.length === 0 && <div className="empty"><span>00:00</span><h3>还没有视频</h3><p>手机完成首次上传后，视频会出现在这里。</p></div>}
          {loading && <div className="empty"><div className="spinner" /><p>正在读取视频库…</p></div>}
        </div>
      </section>
    </main>

    {selected && <div className="modal" onMouseDown={() => setSelected(null)}><div className="player" onMouseDown={e => e.stopPropagation()}>
      <div><strong>{selected.originalFilename || selected.filename}</strong><button onClick={() => setSelected(null)}>×</button></div>
      <video controls autoPlay src={`${API}/api/videos/${selected.id}/stream`} />
      <p>{formatDate(selected.startTime)} · {formatDuration(selected.durationSeconds)} · {formatBytes(selected.fileSizeBytes)}</p>
    </div></div>}
  </div>
}
