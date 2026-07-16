import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

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

const formatDate = (value) => new Intl.DateTimeFormat('en-GB', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(new Date(value))

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.error || `Request failed (${response.status})`)
  }
  return response.status === 204 ? null : response.json()
}

function Icon({ name }) {
  const icons = {
    play: <path d="m9 7 8 5-8 5V7Z" />,
    pause: <><path d="M9 7v10"/><path d="M15 7v10"/></>,
    lock: <><rect x="6" y="10" width="12" height="9" rx="2"/><path d="M9 10V7a3 3 0 0 1 6 0v3"/></>,
    unlock: <><rect x="6" y="10" width="12" height="9" rx="2"/><path d="M9 10V7a3 3 0 0 1 5.4-1.8"/></>,
    download: <><path d="M12 3v12m0 0 4-4m-4 4-4-4"/><path d="M5 20h14"/></>,
    trash: <><path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7"/><path d="M10 11v5m4-5v5"/></>,
    refresh: <><path d="M20 6v5h-5"/><path d="M18.5 16a8 8 0 1 1 .7-8.7L20 11"/></>,
    rotate: <><path d="M20 7v5h-5"/><path d="M19 12a7 7 0 1 1-2-5"/></>,
    fullscreen: <><path d="M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5"/></>,
    fullscreenExit: <><path d="M3 8h5V3M21 8h-5V3M3 16h5v5M21 16h-5v5"/></>,
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true">{icons[name]}</svg>
}

function RotatedVideo({ src, rotation }) {
  const playerRef = useRef(null)
  const stageRef = useRef(null)
  const videoRef = useRef(null)
  const [layout, setLayout] = useState({ width: 0, height: 0 })
  const [playing, setPlaying] = useState(true)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [fullscreen, setFullscreen] = useState(false)

  const updateLayout = useCallback(() => {
    const stage = stageRef.current
    const video = videoRef.current
    if (!stage || !video || !video.videoWidth || !video.videoHeight) return
    const quarterTurn = rotation === 90 || rotation === 270
    const displayVideoWidth = quarterTurn ? video.videoHeight : video.videoWidth
    const displayVideoHeight = quarterTurn ? video.videoWidth : video.videoHeight
    const scale = Math.min(stage.clientWidth / displayVideoWidth, stage.clientHeight / displayVideoHeight)
    const displayWidth = Math.max(1, Math.floor(displayVideoWidth * scale))
    const displayHeight = Math.max(1, Math.floor(displayVideoHeight * scale))
    setLayout({
      width: quarterTurn ? displayHeight : displayWidth,
      height: quarterTurn ? displayWidth : displayHeight,
    })
  }, [rotation])

  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return undefined
    const observer = new ResizeObserver(updateLayout)
    observer.observe(stage)
    updateLayout()
    return () => observer.disconnect()
  }, [updateLayout])

  useEffect(() => {
    const handleFullscreenChange = () => setFullscreen(document.fullscreenElement === playerRef.current)
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
  }, [])

  const togglePlayback = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) video.play().catch(() => setPlaying(false))
    else video.pause()
  }

  const seek = (event) => {
    const video = videoRef.current
    if (!video) return
    const nextTime = Number(event.target.value)
    video.currentTime = nextTime
    setCurrentTime(nextTime)
  }

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await playerRef.current?.requestFullscreen()
    } catch {
      setFullscreen(false)
    }
  }

  return <div className="video-player" ref={playerRef}>
    <div className="video-stage" ref={stageRef}>
      <video
        ref={videoRef}
        autoPlay
        playsInline
        src={src}
        onClick={togglePlayback}
        onLoadedMetadata={() => {
          updateLayout()
          setDuration(Number.isFinite(videoRef.current?.duration) ? videoRef.current.duration : 0)
        }}
        onTimeUpdate={event => setCurrentTime(event.currentTarget.currentTime)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        style={{
          width: layout.width ? `${layout.width}px` : 0,
          height: layout.height ? `${layout.height}px` : 0,
          transform: `translate(-50%, -50%) rotate(${rotation}deg)`,
        }}
      />
    </div>
    <div className="video-controls">
      <input
        type="range"
        min="0"
        max={duration || 0}
        step="0.1"
        value={Math.min(currentTime, duration || 0)}
        onChange={seek}
        aria-label="Video progress"
      />
      <div className="video-control-row">
        <button type="button" className="playback-control" onClick={togglePlayback} aria-label={playing ? 'Pause' : 'Play'} title={playing ? 'Pause' : 'Play'}>
          <Icon name={playing ? 'pause' : 'play'} />
        </button>
        <span>{formatDuration(Math.floor(currentTime))} / {formatDuration(Math.floor(duration))}</span>
        <button type="button" className="playback-control fullscreen-control" onClick={toggleFullscreen} aria-label={fullscreen ? 'Exit fullscreen' : 'Enter fullscreen'} title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
          <Icon name={fullscreen ? 'fullscreenExit' : 'fullscreen'} />
        </button>
      </div>
    </div>
  </div>
}

function WaveformAudio({ recording }) {
  const audioRef = useRef(null)
  const canvasRef = useRef(null)
  const [peaks, setPeaks] = useState([])
  const [loadingWaveform, setLoadingWaveform] = useState(true)
  const [waveformError, setWaveformError] = useState('')
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(recording.durationSeconds || 0)
  const [canvasWidth, setCanvasWidth] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setPeaks([])
    setLoadingWaveform(true)
    setWaveformError('')
    fetch(`${API}/api/audio/${recording.id}/waveform?points=1200`, { signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error(`Waveform request failed (${response.status})`)
        return response.json()
      })
      .then(data => setPeaks(Array.isArray(data.peaks) ? data.peaks : []))
      .catch(error => {
        if (error.name !== 'AbortError') setWaveformError('Waveform unavailable')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoadingWaveform(false)
      })
    return () => controller.abort()
  }, [recording.id])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return undefined
    const resize = () => {
      const width = Math.max(1, Math.floor(canvas.clientWidth))
      const height = Math.max(1, Math.floor(canvas.clientHeight))
      const ratio = window.devicePixelRatio || 1
      canvas.width = Math.floor(width * ratio)
      canvas.height = Math.floor(height * ratio)
      setCanvasWidth(width)
    }
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)
    resize()
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !canvas.width || !canvas.height) return
    const context = canvas.getContext('2d')
    const width = canvas.width
    const height = canvas.height
    const center = height / 2
    const progress = duration > 0 ? currentTime / duration : 0
    context.clearRect(0, 0, width, height)
    if (!peaks.length) return
    const barWidth = Math.max(1, width / peaks.length * 0.65)
    peaks.forEach((peak, index) => {
      const x = index / peaks.length * width
      const amplitude = Math.max(1, peak * height * 0.46)
      context.fillStyle = index / peaks.length <= progress ? '#a9ff5c' : '#46515c'
      context.fillRect(x, center - amplitude, barWidth, amplitude * 2)
    })
  }, [peaks, currentTime, duration, canvasWidth])

  const seekWaveform = event => {
    const audio = audioRef.current
    if (!audio || !duration) return
    const bounds = event.currentTarget.getBoundingClientRect()
    const ratio = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width))
    audio.currentTime = ratio * duration
    setCurrentTime(audio.currentTime)
  }

  return <div className="audio-waveform-player">
    <div className="waveform" aria-label="Audio waveform" onClick={seekWaveform}>
      <canvas ref={canvasRef} />
      {loadingWaveform && <span>Generating waveform...</span>}
      {waveformError && <span>{waveformError}</span>}
    </div>
    <audio
      ref={audioRef}
      controls
      autoPlay
      src={`${API}/api/audio/${recording.id}/stream`}
      onLoadedMetadata={event => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : recording.durationSeconds)}
      onTimeUpdate={event => setCurrentTime(event.currentTarget.currentTime)}
    />
  </div>
}

export default function App() {
  const [videos, setVideos] = useState([])
  const [audio, setAudio] = useState([])
  const [storage, setStorage] = useState(null)
  const [online, setOnline] = useState(null)
  const [selected, setSelected] = useState(null)
  const [selectedAudio, setSelectedAudio] = useState(null)
  const [archiveType, setArchiveType] = useState('video')
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
      const [health, list, audioList, status] = await Promise.all([
        api('/api/health'), api(`/api/videos?${params}`), api(`/api/audio?${params}`), api('/api/storage/status'),
      ])
      setOnline(health.status === 'ok')
      setVideos(list.items)
      setAudio(audioList.items)
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
  const audioStoragePercent = useMemo(() => storage
    ? Math.min(100, storage.totalAudioSizeBytes / storage.maxAudioStorageBytes * 100) : 0, [storage])

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
    if (!window.confirm(`Permanently delete ${video.originalFilename || video.filename}?`)) return
    try {
      await api(`/api/videos/${video.id}`, { method: 'DELETE' })
      if (selected?.id === video.id) setSelected(null)
      await refresh()
    } catch (err) { setError(err.message) }
  }

  const toggleAudioLock = async (recording) => {
    try {
      const updated = await api(`/api/audio/${recording.id}/lock`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ locked: !recording.locked }),
      })
      setAudio(items => items.map(item => item.id === updated.id ? updated : item))
      if (selectedAudio?.id === updated.id) setSelectedAudio(updated)
    } catch (err) { setError(err.message) }
  }

  const removeAudio = async (recording) => {
    if (!window.confirm(`Permanently delete ${recording.originalFilename || recording.filename}?`)) return
    try {
      await api(`/api/audio/${recording.id}`, { method: 'DELETE' })
      if (selectedAudio?.id === recording.id) setSelectedAudio(null)
      await refresh()
    } catch (err) { setError(err.message) }
  }

  const rotatePlayback = async (video) => {
    const playbackRotationDegrees = ((video.playbackRotationDegrees || 0) + 90) % 360
    try {
      const updated = await api(`/api/videos/${video.id}/rotation`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playbackRotationDegrees }),
      })
      setVideos(items => items.map(item => item.id === updated.id ? updated : item))
      if (selected?.id === updated.id) setSelected(updated)
    } catch (err) { setError(err.message) }
  }

  return <div className="shell">
    <header>
      <div className="brand"><span className="brand-mark">DC</span><div><strong>Dashcam Archive</strong><small>Local video and audio library</small></div></div>
      <div className={`status ${online === true ? 'online' : online === false ? 'offline' : ''}`}>
        <span /> {online === true ? 'Server online' : online === false ? 'Server offline' : 'Checking server'}
      </div>
    </header>

    <main>
      <section className="hero">
        <div><p className="eyebrow">LOCAL STORAGE</p><h1>Your dashcam archive.</h1><p>Browse, protect, and manage recordings uploaded from your phone.</p></div>
        <button className="refresh" onClick={refresh} disabled={loading}><Icon name="refresh" />Refresh</button>
      </section>

      <section className="metrics">
        <article><span>Total videos</span><strong>{storage?.totalVideoCount ?? '-'}</strong><small>Archived clips</small></article>
        <article><span>Video storage</span><strong>{storage ? formatBytes(storage.totalSizeBytes) : '-'}</strong><small>{storagePercent.toFixed(1)}% of {storage ? formatBytes(storage.maxStorageBytes) : '-'}</small></article>
        <article><span>Total audio</span><strong>{storage?.totalAudioCount ?? '-'}</strong><small>Archived recordings</small></article>
        <article className="capacity"><span>Audio storage</span><strong>{storage ? formatBytes(storage.totalAudioSizeBytes) : '-'}</strong><div><i style={{ width: `${audioStoragePercent}%` }} /></div><small>{audioStoragePercent.toFixed(1)}% of {storage ? formatBytes(storage.maxAudioStorageBytes) : '-'}</small></article>
      </section>

      {error && <div className="error">{error}</div>}

      <section className="archive">
        <div className="archive-tabs" role="tablist">
          <button className={archiveType === 'video' ? 'active' : ''} onClick={() => setArchiveType('video')}>Video</button>
          <button className={archiveType === 'audio' ? 'active' : ''} onClick={() => setArchiveType('audio')}>Audio</button>
        </div>
        <div className="section-head"><div><p className="eyebrow">{archiveType === 'video' ? 'VIDEO ARCHIVE' : 'AUDIO ARCHIVE'}</p><h2>{archiveType === 'video' ? 'Video recordings' : 'Audio recordings'}</h2></div><div className="filters">
          <input type="date" value={date} onChange={e => setDate(e.target.value)} aria-label="Filter by date" />
          <select value={lockFilter} onChange={e => setLockFilter(e.target.value)} aria-label="Filter by lock status">
            <option value="all">All statuses</option><option value="true">Locked</option><option value="false">Unlocked</option>
          </select>
        </div></div>

        {archiveType === 'video' ? <div className="table-wrap"><table><thead><tr><th>Recorded</th><th>File</th><th>Duration</th><th>Size</th><th>Rotation</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>{videos.map(video => <tr key={video.id}>
            <td>{formatDate(video.startTime)}</td>
            <td className="file"><span>{video.originalFilename || video.filename}</span><small>#{video.id}</small></td>
            <td>{formatDuration(video.durationSeconds)}</td><td>{formatBytes(video.fileSizeBytes)}</td>
            <td>{video.playbackRotationDegrees || 0} deg</td>
            <td><span className={`pill ${video.locked ? 'locked' : ''}`}>{video.locked ? 'Locked' : 'Unlocked'}</span></td>
            <td><div className="actions">
              <button title="Play" onClick={() => setSelected(video)}><Icon name="play" /></button>
              <a title="Download" href={`${API}/api/videos/${video.id}/download`}><Icon name="download" /></a>
              <button title={video.locked ? 'Unlock' : 'Lock'} onClick={() => toggleLock(video)}><Icon name={video.locked ? 'unlock' : 'lock'} /></button>
              <button className="danger" title="Delete" onClick={() => remove(video)}><Icon name="trash" /></button>
            </div></td>
          </tr>)}</tbody></table>
          {!loading && videos.length === 0 && <div className="empty"><span>00:00</span><h3>No videos yet</h3><p>Videos will appear here after the phone completes its first upload.</p></div>}
          {loading && <div className="empty"><div className="spinner" /><p>Loading video library...</p></div>}
        </div> : <div className="table-wrap"><table><thead><tr><th>Recorded</th><th>File</th><th>Duration</th><th>Size</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>{audio.map(recording => <tr key={recording.id}>
            <td>{formatDate(recording.startTime)}</td>
            <td className="file"><span>{recording.originalFilename || recording.filename}</span><small>#{recording.id}</small></td>
            <td>{formatDuration(recording.durationSeconds)}</td><td>{formatBytes(recording.fileSizeBytes)}</td>
            <td><span className={`pill ${recording.locked ? 'locked' : ''}`}>{recording.locked ? 'Locked' : 'Unlocked'}</span></td>
            <td><div className="actions">
              <button title="Play" onClick={() => setSelectedAudio(recording)}><Icon name="play" /></button>
              <a title="Download" href={`${API}/api/audio/${recording.id}/download`}><Icon name="download" /></a>
              <button title={recording.locked ? 'Unlock' : 'Lock'} onClick={() => toggleAudioLock(recording)}><Icon name={recording.locked ? 'unlock' : 'lock'} /></button>
              <button className="danger" title="Delete" onClick={() => removeAudio(recording)}><Icon name="trash" /></button>
            </div></td>
          </tr>)}</tbody></table>
          {!loading && audio.length === 0 && <div className="empty"><span>00:00</span><h3>No audio yet</h3><p>Audio recordings will appear here after the phone completes its first upload.</p></div>}
          {loading && <div className="empty"><div className="spinner" /><p>Loading audio library...</p></div>}
        </div>}
      </section>
    </main>

    {selected && <div className="modal" onMouseDown={() => setSelected(null)}><div className="player" onMouseDown={e => e.stopPropagation()}>
      <div><strong>{selected.originalFilename || selected.filename}</strong><span className="player-actions"><button className="rotate-control" title="Rotate playback clockwise by 90 degrees" onClick={() => rotatePlayback(selected)}><Icon name="rotate" /><span>Rotate 90 deg</span></button><button className="close-player" aria-label="Close player" onClick={() => setSelected(null)}>X</button></span></div>
      <RotatedVideo src={`${API}/api/videos/${selected.id}/stream`} rotation={selected.playbackRotationDegrees || 0} />
      <p>{formatDate(selected.startTime)} | {formatDuration(selected.durationSeconds)} | {formatBytes(selected.fileSizeBytes)} | Playback {selected.playbackRotationDegrees || 0} deg</p>
    </div></div>}
    {selectedAudio && <div className="modal" onMouseDown={() => setSelectedAudio(null)}><div className="player audio-player-modal" onMouseDown={e => e.stopPropagation()}>
      <div><strong>{selectedAudio.originalFilename || selectedAudio.filename}</strong><button className="close-player" aria-label="Close player" onClick={() => setSelectedAudio(null)}>X</button></div>
      <WaveformAudio recording={selectedAudio} />
      <p>{formatDate(selectedAudio.startTime)} | {formatDuration(selectedAudio.durationSeconds)} | {formatBytes(selectedAudio.fileSizeBytes)}</p>
    </div></div>}
  </div>
}
