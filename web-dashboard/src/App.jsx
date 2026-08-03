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

const formatPlaybackTimestamp = (startTime, offsetSeconds = 0) => {
  const startedAt = new Date(startTime).getTime()
  if (!Number.isFinite(startedAt)) return '--'
  const date = new Date(startedAt + Math.max(0, Number(offsetSeconds) || 0) * 1000)
  const pad = value => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.error || `Request failed (${response.status})`)
  }
  return response.status === 204 ? null : response.json()
}

const supportedMigrationPath = path => {
  const normalized = path.toLowerCase()
  return normalized === 'dashcam.db' || normalized === 'dashcam.db-wal' || normalized === 'dashcam.db-shm' ||
    normalized.startsWith('videos/') && normalized.endsWith('.mp4') ||
    normalized.startsWith('audio/') && normalized.endsWith('.m4a')
}

async function folderFingerprint(entries) {
  const manifest = entries.map(entry => `${entry.path}\t${entry.file.size}\t${entry.file.lastModified}`).join('\n')
  if (globalThis.crypto?.subtle) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(manifest))
    return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
  }
  return Array.from({ length: 8 }, (_, salt) => {
    let hash = (2166136261 ^ salt) >>> 0
    for (let index = 0; index < manifest.length; index++) hash = Math.imul(hash ^ manifest.charCodeAt(index), 16777619) >>> 0
    return hash.toString(16).padStart(8, '0')
  }).join('')
}

async function prepareMigrationFolder(fileList) {
  const files = [...fileList]
  const paths = files.map(file => (file.webkitRelativePath || file.name).replaceAll('\\', '/').replace(/^\/+/, ''))
  const databaseIndexes = paths.map((path, index) => path.toLowerCase().endsWith('/dashcam.db') || path.toLowerCase() === 'dashcam.db' ? index : -1).filter(index => index >= 0)
  if (databaseIndexes.length !== 1) throw new Error('Select one folder containing exactly one dashcam.db.')
  const databasePath = paths[databaseIndexes[0]]
  const prefix = databasePath.slice(0, -'dashcam.db'.length)
  const rootParts = prefix.split('/').filter(Boolean)
  const rootName = rootParts.at(-1) || 'Selected dashcam folder'
  const entries = files.map((file, index) => ({ file, path: paths[index].startsWith(prefix) ? paths[index].slice(prefix.length) : '' }))
    .filter(entry => supportedMigrationPath(entry.path))
    .sort((left, right) => left.path.localeCompare(right.path))
  if (!entries.some(entry => entry.path.toLowerCase() === 'dashcam.db')) throw new Error('dashcam.db must be inside the selected folder.')
  if (!entries.some(entry => entry.path.toLowerCase().startsWith('videos/') || entry.path.toLowerCase().startsWith('audio/')))
    throw new Error('The selected folder does not contain a videos or audio folder.')
  if (entries.some(entry => entry.file.size <= 0)) throw new Error('The selected folder contains an empty database or media file.')
  return {
    rootName,
    entries,
    totalBytes: entries.reduce((total, entry) => total + entry.file.size, 0),
    fingerprint: await folderFingerprint(entries),
  }
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
    camera: <><path d="M7 7 9 4h6l2 3"/><rect x="3" y="7" width="18" height="13" rx="2"/><circle cx="12" cy="13.5" r="3.5"/></>,
    stop: <rect x="7" y="7" width="10" height="10" rx="1"/>,
    fullscreen: <><path d="M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5"/></>,
    fullscreenExit: <><path d="M3 8h5V3M21 8h-5V3M3 16h5v5M21 16h-5v5"/></>,
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true">{icons[name]}</svg>
}

function RotatedVideo({ src, rotation, startTime }) {
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
        <span className="playback-timestamp"><small>Recorded time</small><strong>{formatPlaybackTimestamp(startTime, currentTime)}</strong></span>
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
  const [canvasSize, setCanvasSize] = useState({ width: 0, height: 0 })
  const waveformReferencePeak = useMemo(() => {
    const visiblePeaks = peaks
      .map(peak => Math.abs(Number(peak)))
      .filter(Number.isFinite)
      .sort((left, right) => left - right)
    return visiblePeaks[Math.floor((visiblePeaks.length - 1) * 0.95)] || 1
  }, [peaks])

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
      setCanvasSize(previous => previous.width === width && previous.height === height
        ? previous
        : { width, height })
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
    context.fillStyle = '#26313a'
    context.fillRect(0, Math.floor(center), width, Math.max(1, window.devicePixelRatio || 1))
    peaks.forEach((peak, index) => {
      const x = index / peaks.length * width
      const normalizedPeak = Math.min(1, Math.abs(Number(peak) || 0) / waveformReferencePeak)
      const amplitude = Math.max(1, normalizedPeak * height * 0.44)
      context.fillStyle = index / peaks.length <= progress ? '#a9ff5c' : '#62717e'
      context.fillRect(x, center - amplitude, barWidth, amplitude * 2)
    })
  }, [peaks, currentTime, duration, canvasSize, waveformReferencePeak])

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
    <div className="audio-playback-time">
      <span>Recorded time</span>
      <strong>{formatPlaybackTimestamp(recording.startTime, currentTime)}</strong>
    </div>
  </div>
}

function LiveViewer({ device, onClose }) {
  const [frameUrl, setFrameUrl] = useState('')
  const [waiting, setWaiting] = useState(true)
  const [rotation, setRotation] = useState(0)
  const [layout, setLayout] = useState({ width: 0, height: 0 })
  const [fullscreen, setFullscreen] = useState(false)
  const objectUrlRef = useRef('')
  const playerRef = useRef(null)
  const stageRef = useRef(null)
  const imageRef = useRef(null)

  const updateLayout = useCallback(() => {
    const stage = stageRef.current
    const image = imageRef.current
    if (!stage || !image || !image.naturalWidth || !image.naturalHeight) return
    const quarterTurn = rotation === 90 || rotation === 270
    const displayImageWidth = quarterTurn ? image.naturalHeight : image.naturalWidth
    const displayImageHeight = quarterTurn ? image.naturalWidth : image.naturalHeight
    const scale = Math.min(stage.clientWidth / displayImageWidth, stage.clientHeight / displayImageHeight)
    const displayWidth = Math.max(1, Math.floor(displayImageWidth * scale))
    const displayHeight = Math.max(1, Math.floor(displayImageHeight * scale))
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

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await playerRef.current?.requestFullscreen()
    } catch {
      setFullscreen(false)
    }
  }

  useEffect(() => {
    let active = true
    let timer
    let sequence = -1
    const loadFrame = async () => {
      let retryDelay = 25
      try {
        const response = await fetch(
          `${API}/api/devices/${encodeURIComponent(device.deviceId)}/live/frame?after=${sequence}`,
          { cache: 'no-store' },
        )
        if (response.ok) {
          if (response.status === 204) return
          const nextSequence = Number(response.headers.get('X-Live-Sequence'))
          const nextUrl = URL.createObjectURL(await response.blob())
          if (!active) {
            URL.revokeObjectURL(nextUrl)
            return
          }
          if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current)
          objectUrlRef.current = nextUrl
          if (Number.isFinite(nextSequence)) sequence = nextSequence
          setFrameUrl(nextUrl)
          setWaiting(false)
        } else {
          retryDelay = 250
          setWaiting(true)
        }
      } catch {
        retryDelay = 500
        setWaiting(true)
      } finally {
        if (active) timer = window.setTimeout(loadFrame, retryDelay)
      }
    }
    loadFrame()
    return () => {
      active = false
      window.clearTimeout(timer)
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current)
    }
  }, [device.deviceId])

  return <div className="modal" onMouseDown={onClose}>
    <div className="player live-player" ref={playerRef} onMouseDown={event => event.stopPropagation()}>
      <div>
        <strong>{device.deviceName} - Live</strong>
        <span className="player-actions">
          <button
            type="button"
            className="playback-control"
            onClick={toggleFullscreen}
            aria-label={fullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
            title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
          >
            <Icon name={fullscreen ? 'fullscreenExit' : 'fullscreen'} />
          </button>
          <button
            type="button"
            className="rotate-control"
            title="Rotate live view clockwise by 90 degrees"
            onClick={() => setRotation(current => (current + 90) % 360)}
          >
            <Icon name="rotate" />
            <span>Rotate 90 deg</span>
          </button>
          <button className="close-player" aria-label="Stop and close live view" title="Stop live view" onClick={onClose}>X</button>
        </span>
      </div>
      <div className="live-stage" ref={stageRef}>
        {frameUrl && <img
          ref={imageRef}
          src={frameUrl}
          alt={`Live camera from ${device.deviceName}`}
          onLoad={updateLayout}
          style={{
            width: layout.width ? `${layout.width}px` : 0,
            height: layout.height ? `${layout.height}px` : 0,
            transform: `translate(-50%, -50%) rotate(${rotation}deg)`,
          }}
        />}
        {waiting && <div className="live-waiting"><div className="spinner" /><span>Waiting for phone camera...</span></div>}
      </div>
      <p>{device.liveError || (device.liveStreaming ? `Live camera connected - view rotation ${rotation} deg` : 'Starting live camera')}</p>
    </div>
  </div>
}

function MigrationPanel({ migration, busy, folder, upload, onFolderSelected, onUpload, onStart, onCancelUpload, onCancelMigration }) {
  const inputRef = useRef(null)
  if (!migration) return null
  const uploading = upload?.phase === 'uploading' || upload?.phase === 'preparing'
  const migrationActive = migration.phase === 'scanning' || migration.phase === 'importing'
  const active = uploading || migrationActive
  const hasScan = ['ready', 'importing', 'completed'].includes(migration.phase)
  const noChanges = migration.phase === 'ready' && migration.importVideoCount + migration.importAudioCount === 0
  const shownPhase = uploading ? 'uploading' : migration.phase

  return <section className="migration-panel">
    <div className="section-head">
      <div><p className="eyebrow">DATA MIGRATION</p><h2>Merge another server</h2></div>
      <span className={`migration-phase ${shownPhase}`}>{shownPhase}</span>
    </div>
    <div className="migration-card">
      <div className="migration-intro">
        <div><strong>Old server folder</strong><code>{folder?.rootName || 'No folder selected'}</code></div>
        <p>Choose the folder containing <code>dashcam.db</code>, <code>videos</code>, and <code>audio</code>. Select the same folder again to resume an interrupted upload.</p>
      </div>
      <input ref={inputRef} className="migration-folder-input" type="file" webkitdirectory="" directory="" multiple
        onChange={event => { onFolderSelected(event.target.files); event.target.value = '' }} />

      {folder && !uploading && <div className="migration-selection">
        <span>{folder.entries.length} supported files</span><strong>{formatBytes(folder.totalBytes)}</strong>
      </div>}

      {uploading && <div className="migration-progress">
        <div><span>{upload.message}</span><strong>{upload.progressPercent}%</strong></div>
        <i><b style={{ width: `${upload.progressPercent}%` }} /></i>
        <small>{formatBytes(upload.uploadedBytes)} of {formatBytes(upload.totalBytes)}{upload.currentFile ? ` · ${upload.currentFile}` : ''}</small>
      </div>}

      {!uploading && migrationActive && <div className="migration-progress">
        <div><span>{migration.message}</span><strong>{migration.progressPercent}%</strong></div>
        <i><b style={{ width: `${migration.progressPercent}%` }} /></i>
        <small>{migration.processedItems} of {migration.totalItems || '?'} processed</small>
      </div>}

      {hasScan && <>
        <div className="migration-summary">
          <article><span>Old videos</span><strong>{migration.sourceVideoCount}</strong><small>{formatBytes(migration.sourceVideoBytes)}</small></article>
          <article><span>Old audio</span><strong>{migration.sourceAudioCount}</strong><small>{formatBytes(migration.sourceAudioBytes)}</small></article>
          <article><span>Will import</span><strong>{migration.importVideoCount + migration.importAudioCount}</strong><small>{formatBytes(migration.importBytes)}</small></article>
          <article><span>Duplicates</span><strong>{migration.duplicateVideos + migration.duplicateAudio}</strong><small>Skipped safely</small></article>
        </div>
        <div className="migration-projection">
          <span>After merge: video {formatBytes(migration.projectedVideoBytes)} / {formatBytes(migration.maxVideoBytes)}</span>
          <span>audio {formatBytes(migration.projectedAudioBytes)} / {formatBytes(migration.maxAudioBytes)}</span>
          <span>disk available {formatBytes(migration.availableDiskSpaceBytes)}</span>
        </div>
      </>}

      {migration.requiresCapacityConfirmation && migration.phase === 'ready' && <div className="migration-warning">
        This merge exceeds a configured archive limit. The next phone upload may delete the oldest unlocked recordings.
      </div>}
      {migration.missingFiles > 0 && <div className="migration-warning danger">
        {migration.missingFiles} referenced file(s) are missing or have the wrong size. Select the complete old server folder and upload it again.
        {migration.missingFileExamples?.length > 0 && <small>{migration.missingFileExamples.join(' | ')}</small>}
      </div>}
      {migration.error && <div className="migration-warning danger">{migration.error}</div>}
      {migration.phase === 'completed' && <div className="migration-success">{migration.message}{migration.backupPath && <small>Database backup: {migration.backupPath}</small>}</div>}
      {upload?.phase === 'paused' && <div className="migration-warning">{upload.message || 'Upload paused.'} Select the same folder to resume.</div>}

      <div className="migration-actions">
        {!active && <button onClick={() => inputRef.current?.click()} disabled={busy}>Choose folder</button>}
        {!active && folder && <button className="primary" onClick={onUpload} disabled={busy}>Upload and scan</button>}
        {migration.phase === 'ready' && <button className="primary" onClick={onStart} disabled={busy || migration.missingFiles > 0 || noChanges}>Start merge</button>}
        {uploading && <button className="danger" onClick={onCancelUpload}>Pause upload</button>}
        {!uploading && migrationActive && <button className="danger" onClick={onCancelMigration} disabled={busy}>Cancel</button>}
        {noChanges && <span>Everything in the selected folder is already in this archive.</span>}
      </div>
    </div>
  </section>
}

export default function App() {
  const [videos, setVideos] = useState([])
  const [audio, setAudio] = useState([])
  const [storage, setStorage] = useState(null)
  const [migration, setMigration] = useState(null)
  const [migrationFolder, setMigrationFolder] = useState(null)
  const [migrationUpload, setMigrationUpload] = useState(null)
  const [devices, setDevices] = useState([])
  const [online, setOnline] = useState(null)
  const [selected, setSelected] = useState(null)
  const [selectedAudio, setSelectedAudio] = useState(null)
  const [liveDeviceId, setLiveDeviceId] = useState(null)
  const [archiveType, setArchiveType] = useState('video')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [date, setDate] = useState('')
  const [lockFilter, setLockFilter] = useState('all')
  const [videoPage, setVideoPage] = useState(1)
  const [audioPage, setAudioPage] = useState(1)
  const [videoTotal, setVideoTotal] = useState(0)
  const [audioTotal, setAudioTotal] = useState(0)
  const [selectedVideoIds, setSelectedVideoIds] = useState(() => new Set())
  const [selectedAudioIds, setSelectedAudioIds] = useState(() => new Set())
  const [bulkRotation, setBulkRotation] = useState(90)
  const [bulkBusy, setBulkBusy] = useState(false)
  const [migrationBusy, setMigrationBusy] = useState(false)
  const migrationUploadAbort = useRef(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const videoParams = new URLSearchParams({ page: String(videoPage), pageSize: '200' })
      const audioParams = new URLSearchParams({ page: String(audioPage), pageSize: '200' })
      if (date) {
        videoParams.set('date', date)
        audioParams.set('date', date)
      }
      if (lockFilter !== 'all') {
        videoParams.set('locked', lockFilter)
        audioParams.set('locked', lockFilter)
      }
      const [health, list, audioList, status, deviceList, migrationStatus] = await Promise.all([
        api('/api/health'), api(`/api/videos?${videoParams}`), api(`/api/audio?${audioParams}`),
        api('/api/storage/status'), api('/api/devices'), api('/api/admin/migration/status'),
      ])
      setOnline(health.status === 'ok')
      setVideos(list.items)
      setAudio(audioList.items)
      setVideoTotal(list.totalCount)
      setAudioTotal(audioList.totalCount)
      setSelectedVideoIds(current => new Set([...current].filter(id => list.items.some(item => item.id === id))))
      setSelectedAudioIds(current => new Set([...current].filter(id => audioList.items.some(item => item.id === id))))
      setStorage(status)
      setDevices(deviceList.items)
      setMigration(migrationStatus)
    } catch (err) {
      setOnline(false)
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }, [date, lockFilter, videoPage, audioPage])

  useEffect(() => { refresh() }, [refresh])
  useEffect(() => {
    setVideoPage(page => Math.min(page, Math.max(1, Math.ceil(videoTotal / 200))))
  }, [videoTotal])
  useEffect(() => {
    setAudioPage(page => Math.min(page, Math.max(1, Math.ceil(audioTotal / 200))))
  }, [audioTotal])
  useEffect(() => {
    const interval = window.setInterval(() => {
      api('/api/devices').then(result => setDevices(result.items)).catch(() => {})
    }, 15_000)
    return () => window.clearInterval(interval)
  }, [])
  useEffect(() => {
    if (!migration || !['scanning', 'importing'].includes(migration.phase)) return undefined
    const interval = window.setInterval(() => {
      api('/api/admin/migration/status').then(setMigration).catch(() => {})
    }, 1_000)
    return () => window.clearInterval(interval)
  }, [migration?.phase])

  const storagePercent = useMemo(() => storage
    ? Math.min(100, storage.totalSizeBytes / storage.maxStorageBytes * 100) : 0, [storage])
  const audioStoragePercent = useMemo(() => storage
    ? Math.min(100, storage.totalAudioSizeBytes / storage.maxAudioStorageBytes * 100) : 0, [storage])
  const liveDevice = devices.find(device => device.deviceId === liveDeviceId)

  const startLive = async (device) => {
    setError('')
    try {
      const updated = await api(`/api/devices/${encodeURIComponent(device.deviceId)}/live`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: true }),
      })
      setDevices(items => items.map(item => item.deviceId === updated.deviceId ? updated : item))
      setLiveDeviceId(device.deviceId)
    } catch (err) { setError(err.message) }
  }

  const stopLive = async (deviceId) => {
    setLiveDeviceId(null)
    try {
      const updated = await api(`/api/devices/${encodeURIComponent(deviceId)}/live`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: false }),
      })
      setDevices(items => items.map(item => item.deviceId === updated.deviceId ? updated : item))
    } catch (err) { setError(err.message) }
  }

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

  const toggleSelection = (setIds, id) => setIds(current => {
    const next = new Set(current)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  const toggleAll = (items, selectedIds, setIds) => setIds(current => {
    const next = new Set(current)
    const allSelected = items.length > 0 && items.every(item => selectedIds.has(item.id))
    items.forEach(item => allSelected ? next.delete(item.id) : next.add(item.id))
    return next
  })

  const bulkLock = async (type, locked) => {
    const ids = [...(type === 'video' ? selectedVideoIds : selectedAudioIds)]
    if (!ids.length) return
    setBulkBusy(true)
    setError('')
    try {
      const result = await api(`/api/${type === 'video' ? 'videos' : 'audio'}/bulk/lock`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids, locked }),
      })
      const updates = new Map(result.items.map(item => [item.id, item]))
      if (type === 'video') {
        setVideos(items => items.map(item => updates.get(item.id) || item))
        setSelected(current => current && (updates.get(current.id) || current))
        setSelectedVideoIds(new Set())
      } else {
        setAudio(items => items.map(item => updates.get(item.id) || item))
        setSelectedAudio(current => current && (updates.get(current.id) || current))
        setSelectedAudioIds(new Set())
      }
      await refresh()
      if (result.notFoundIds.length) setError(`${result.notFoundIds.length} selected item(s) no longer exist.`)
    } catch (err) { setError(err.message) }
    finally { setBulkBusy(false) }
  }

  const bulkRotate = async () => {
    const ids = [...selectedVideoIds]
    if (!ids.length) return
    setBulkBusy(true)
    setError('')
    try {
      const result = await api('/api/videos/bulk/rotation', {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids, playbackRotationDegrees: bulkRotation }),
      })
      const updates = new Map(result.items.map(item => [item.id, item]))
      setVideos(items => items.map(item => updates.get(item.id) || item))
      setSelected(current => current && (updates.get(current.id) || current))
      setSelectedVideoIds(new Set())
      await refresh()
      if (result.notFoundIds.length) setError(`${result.notFoundIds.length} selected video(s) no longer exist.`)
    } catch (err) { setError(err.message) }
    finally { setBulkBusy(false) }
  }

  const bulkRemove = async (type) => {
    const selectedIds = type === 'video' ? selectedVideoIds : selectedAudioIds
    const ids = [...selectedIds]
    if (!ids.length || !window.confirm(`Permanently delete ${ids.length} selected ${type === 'video' ? 'video' : 'audio'} recording(s)?`)) return
    setBulkBusy(true)
    setError('')
    try {
      const result = await api(`/api/${type === 'video' ? 'videos' : 'audio'}/bulk`, {
        method: 'DELETE', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids }),
      })
      const removed = new Set([...result.deletedIds, ...result.notFoundIds])
      if (type === 'video') {
        if (selected && removed.has(selected.id)) setSelected(null)
        setSelectedVideoIds(new Set(result.failedIds))
      } else {
        if (selectedAudio && removed.has(selectedAudio.id)) setSelectedAudio(null)
        setSelectedAudioIds(new Set(result.failedIds))
      }
      await refresh()
      if (result.failedIds.length) setError(`${result.failedIds.length} file(s) could not be deleted and were kept.`)
    } catch (err) { setError(err.message) }
    finally { setBulkBusy(false) }
  }

  const selectMigrationFolder = async (fileList) => {
    if (!fileList?.length) return
    setMigrationBusy(true)
    setError('')
    try {
      const selectedFolder = await prepareMigrationFolder(fileList)
      setMigrationFolder(selectedFolder)
      setMigrationUpload(null)
    } catch (err) { setError(err.message) }
    finally { setMigrationBusy(false) }
  }

  const uploadMigrationFolder = async () => {
    if (!migrationFolder) return
    const controller = new AbortController()
    migrationUploadAbort.current = controller
    setMigrationBusy(true)
    setError('')
    try {
      setMigrationUpload({ phase: 'preparing', message: 'Preparing resumable upload...', progressPercent: 0, uploadedBytes: 0, totalBytes: migrationFolder.totalBytes })
      const session = await api('/api/admin/migration/upload/session', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({
          fingerprint: migrationFolder.fingerprint,
          rootName: migrationFolder.rootName,
          files: migrationFolder.entries.map(entry => ({ path: entry.path, size: entry.file.size, lastModified: entry.file.lastModified })),
        }),
      })
      const serverFiles = new Map(session.files.map(file => [file.path.toLowerCase(), file]))
      let uploadedBytes = session.uploadedBytes
      setMigrationUpload({
        phase: 'uploading', message: 'Uploading selected folder...',
        progressPercent: Math.round(uploadedBytes / session.totalBytes * 100),
        uploadedBytes, totalBytes: session.totalBytes,
      })

      for (const entry of migrationFolder.entries) {
        const serverFile = serverFiles.get(entry.path.toLowerCase())
        let offset = serverFile?.uploadedBytes || 0
        while (offset < entry.file.size) {
          const end = Math.min(offset + session.chunkSizeBytes, entry.file.size)
          const result = await api(`/api/admin/migration/upload/${session.sessionId}/chunk?path=${encodeURIComponent(entry.path)}&offset=${offset}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' },
            body: entry.file.slice(offset, end), signal: controller.signal,
          })
          uploadedBytes += result.uploadedBytes - offset
          offset = result.uploadedBytes
          setMigrationUpload({
            phase: 'uploading', message: 'Uploading selected folder...',
            progressPercent: Math.min(100, Math.round(uploadedBytes / session.totalBytes * 100)),
            uploadedBytes, totalBytes: session.totalBytes, currentFile: entry.path,
          })
        }
      }

      const migrationStatus = await api(`/api/admin/migration/upload/${session.sessionId}/complete`, {
        method: 'POST', signal: controller.signal,
      })
      setMigration(migrationStatus)
      setMigrationUpload({
        phase: 'complete', message: 'Upload complete. Scanning data...', progressPercent: 100,
        uploadedBytes: session.totalBytes, totalBytes: session.totalBytes,
      })
    } catch (err) {
      if (err.name === 'AbortError') {
        setMigrationUpload(current => ({ ...current, phase: 'paused', message: 'Upload paused.' }))
      } else {
        setMigrationUpload(current => current ? { ...current, phase: 'paused', message: err.message } : null)
        setError(err.message)
      }
    } finally {
      if (migrationUploadAbort.current === controller) migrationUploadAbort.current = null
      setMigrationBusy(false)
    }
  }

  const cancelMigrationUpload = () => migrationUploadAbort.current?.abort()

  const startMigration = async () => {
    const overCapacity = migration?.requiresCapacityConfirmation
    const message = overCapacity
      ? 'This merge exceeds an archive limit. The next upload may delete the oldest unlocked recordings. Continue?'
      : `Merge ${migration?.importVideoCount || 0} video(s) and ${migration?.importAudioCount || 0} audio recording(s)?`
    if (!window.confirm(message)) return
    setMigrationBusy(true)
    setError('')
    try {
      setMigration(await api('/api/admin/migration/start', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ allowOverCapacity: Boolean(overCapacity) }),
      }))
    } catch (err) { setError(err.message) }
    finally { setMigrationBusy(false) }
  }

  const cancelMigration = async () => {
    if (!window.confirm('Cancel the current migration job?')) return
    setMigrationBusy(true)
    try {
      setMigration(await api('/api/admin/migration/cancel', { method: 'POST' }))
    } catch (err) { setError(err.message) }
    finally { setMigrationBusy(false) }
  }

  const selectedIds = archiveType === 'video' ? selectedVideoIds : selectedAudioIds
  const setSelectedIds = archiveType === 'video' ? setSelectedVideoIds : setSelectedAudioIds
  const visibleItems = archiveType === 'video' ? videos : audio
  const allVisibleSelected = visibleItems.length > 0 && visibleItems.every(item => selectedIds.has(item.id))
  const pageSize = 200
  const currentPage = archiveType === 'video' ? videoPage : audioPage
  const setCurrentPage = archiveType === 'video' ? setVideoPage : setAudioPage
  const currentTotal = archiveType === 'video' ? videoTotal : audioTotal
  const totalPages = Math.max(1, Math.ceil(currentTotal / pageSize))
  const rangeStart = currentTotal === 0 ? 0 : (currentPage - 1) * pageSize + 1
  const rangeEnd = Math.min(currentPage * pageSize, currentTotal)

  const changeDate = (value) => {
    setVideoPage(1)
    setAudioPage(1)
    setDate(value)
  }

  const changeLockFilter = (value) => {
    setVideoPage(1)
    setAudioPage(1)
    setLockFilter(value)
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
        <article className="capacity"><span>Video storage</span><strong>{storage ? formatBytes(storage.totalSizeBytes) : '-'}</strong><div><i style={{ width: `${storagePercent}%` }} /></div><small>{storagePercent.toFixed(1)}% of {storage ? formatBytes(storage.maxStorageBytes) : '-'}</small></article>
        <article><span>Total audio</span><strong>{storage?.totalAudioCount ?? '-'}</strong><small>Archived recordings</small></article>
        <article className="capacity"><span>Audio storage</span><strong>{storage ? formatBytes(storage.totalAudioSizeBytes) : '-'}</strong><div><i style={{ width: `${audioStoragePercent}%` }} /></div><small>{audioStoragePercent.toFixed(1)}% of {storage ? formatBytes(storage.maxAudioStorageBytes) : '-'}</small></article>
      </section>

      {error && <div className="error">{error}</div>}

      <MigrationPanel migration={migration} busy={migrationBusy} folder={migrationFolder} upload={migrationUpload}
        onFolderSelected={selectMigrationFolder} onUpload={uploadMigrationFolder} onStart={startMigration}
        onCancelUpload={cancelMigrationUpload} onCancelMigration={cancelMigration} />

      <section className="devices">
        <div className="section-head">
          <div><p className="eyebrow">CONNECTED DEVICES</p><h2>Dashcam phones</h2></div>
          <span className="device-count">{devices.filter(device => device.online).length} online / {devices.length} known</span>
        </div>
        <div className="device-table-wrap">
          <table className="device-table">
            <thead><tr><th>Device</th><th>Status</th><th>Battery</th><th>Power</th><th>Activity</th><th>Software</th><th>Last seen</th><th>Live</th></tr></thead>
            <tbody>{devices.map(device => <tr key={device.deviceId}>
              <td className="device-name"><strong>{device.deviceName}</strong><small>{device.manufacturer} {device.model}</small></td>
              <td><span className={`device-status ${device.online ? 'online' : 'offline'}`}><i />{device.online ? 'Online' : 'Offline'}</span></td>
              <td>
                <div className="battery-value"><span>{device.batteryLevel}%</span><div><i style={{ width: `${device.batteryLevel}%` }} /></div></div>
              </td>
              <td><span>{device.isCharging ? `${device.chargingSource} charging` : 'On battery'}</span><small className={device.powerSaveMode ? 'power-save active' : 'power-save'}>{device.powerSaveMode ? 'Power saving' : 'Normal power'}</small></td>
              <td>{device.liveStreaming ? 'Live streaming' : device.videoRecordingActive ? 'Video recording' : device.audioRecordingActive ? 'Audio recording' : 'Idle'}</td>
              <td><span>Android {device.androidVersion}</span><small className="software-version">App {device.appVersion}</small></td>
              <td>{formatDate(device.lastSeenAt)}</td>
              <td><button
                className={`device-live-button ${device.liveRequested ? 'stop' : ''}`}
                disabled={!device.liveRequested && (!device.online || !device.liveAccessEnabled || device.videoRecordingActive || device.audioRecordingActive)}
                title={!device.liveAccessEnabled ? 'Enable Live Access on the phone' : device.liveRequested ? 'Stop live view' : 'Start live view'}
                onClick={() => device.liveRequested ? stopLive(device.deviceId) : startLive(device)}
              ><Icon name={device.liveRequested ? 'stop' : 'camera'} />{device.liveRequested ? 'Stop' : 'View'}</button></td>
            </tr>)}</tbody>
          </table>
          {!loading && devices.length === 0 && <div className="device-empty">No phones have reported to this server yet.</div>}
        </div>
      </section>

      <section className="archive">
        <div className="archive-tabs" role="tablist">
          <button className={archiveType === 'video' ? 'active' : ''} onClick={() => setArchiveType('video')}>Video</button>
          <button className={archiveType === 'audio' ? 'active' : ''} onClick={() => setArchiveType('audio')}>Audio</button>
        </div>
        <div className="section-head"><div><p className="eyebrow">{archiveType === 'video' ? 'VIDEO ARCHIVE' : 'AUDIO ARCHIVE'}</p><h2>{archiveType === 'video' ? 'Video recordings' : 'Audio recordings'}</h2></div><div className="filters">
          <input type="date" value={date} onChange={e => changeDate(e.target.value)} aria-label="Filter by date" />
          <select value={lockFilter} onChange={e => changeLockFilter(e.target.value)} aria-label="Filter by lock status">
            <option value="all">All statuses</option><option value="true">Locked</option><option value="false">Unlocked</option>
          </select>
        </div></div>

        {selectedIds.size > 0 && <div className="bulk-toolbar" role="toolbar" aria-label="Bulk actions">
          <strong>{selectedIds.size} selected</strong>
          {archiveType === 'video' && <>
            <select className="bulk-rotation-select" value={bulkRotation} onChange={event => setBulkRotation(Number(event.target.value))} disabled={bulkBusy} aria-label="Playback rotation">
              <option value={0}>0 deg</option><option value={90}>90 deg</option><option value={180}>180 deg</option><option value={270}>270 deg</option>
            </select>
            <button onClick={bulkRotate} disabled={bulkBusy}><Icon name="rotate" />Set rotation</button>
          </>}
          <button onClick={() => bulkLock(archiveType, true)} disabled={bulkBusy}><Icon name="lock" />Lock</button>
          <button onClick={() => bulkLock(archiveType, false)} disabled={bulkBusy}><Icon name="unlock" />Unlock</button>
          <button className="danger" onClick={() => bulkRemove(archiveType)} disabled={bulkBusy}><Icon name="trash" />Delete</button>
          <button className="clear-selection" onClick={() => setSelectedIds(new Set())} disabled={bulkBusy}>Clear</button>
        </div>}

        {archiveType === 'video' ? <div className="table-wrap"><table><thead><tr><th className="select-cell"><input type="checkbox" checked={allVisibleSelected} onChange={() => toggleAll(videos, selectedVideoIds, setSelectedVideoIds)} aria-label="Select all visible videos" /></th><th>Recorded</th><th>File</th><th>Duration</th><th>Size</th><th>Rotation</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>{videos.map(video => <tr key={video.id}>
            <td className="select-cell"><input type="checkbox" checked={selectedVideoIds.has(video.id)} onChange={() => toggleSelection(setSelectedVideoIds, video.id)} aria-label={`Select ${video.originalFilename || video.filename}`} /></td>
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
        </div> : <div className="table-wrap"><table><thead><tr><th className="select-cell"><input type="checkbox" checked={allVisibleSelected} onChange={() => toggleAll(audio, selectedAudioIds, setSelectedAudioIds)} aria-label="Select all visible audio recordings" /></th><th>Recorded</th><th>File</th><th>Duration</th><th>Size</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>{audio.map(recording => <tr key={recording.id}>
            <td className="select-cell"><input type="checkbox" checked={selectedAudioIds.has(recording.id)} onChange={() => toggleSelection(setSelectedAudioIds, recording.id)} aria-label={`Select ${recording.originalFilename || recording.filename}`} /></td>
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
        {!loading && currentTotal > 0 && <nav className="pagination" aria-label={`${archiveType} archive pages`}>
          <span>Showing {rangeStart}-{rangeEnd} of {currentTotal}</span>
          <div>
            <button onClick={() => setCurrentPage(page => Math.max(1, page - 1))} disabled={currentPage <= 1}>Previous</button>
            <strong>Page {currentPage} of {totalPages}</strong>
            <button onClick={() => setCurrentPage(page => Math.min(totalPages, page + 1))} disabled={currentPage >= totalPages}>Next</button>
          </div>
        </nav>}
      </section>
    </main>

    {selected && <div className="modal" onMouseDown={() => setSelected(null)}><div className="player" onMouseDown={e => e.stopPropagation()}>
      <div><strong>{selected.originalFilename || selected.filename}</strong><span className="player-actions"><button className="rotate-control" title="Rotate playback clockwise by 90 degrees" onClick={() => rotatePlayback(selected)}><Icon name="rotate" /><span>Rotate 90 deg</span></button><button className="close-player" aria-label="Close player" onClick={() => setSelected(null)}>X</button></span></div>
      <RotatedVideo key={selected.id} src={`${API}/api/videos/${selected.id}/stream`} rotation={selected.playbackRotationDegrees || 0} startTime={selected.startTime} />
      <p>{formatDate(selected.startTime)} | {formatDuration(selected.durationSeconds)} | {formatBytes(selected.fileSizeBytes)} | Playback {selected.playbackRotationDegrees || 0} deg</p>
    </div></div>}
    {selectedAudio && <div className="modal" onMouseDown={() => setSelectedAudio(null)}><div className="player audio-player-modal" onMouseDown={e => e.stopPropagation()}>
      <div><strong>{selectedAudio.originalFilename || selectedAudio.filename}</strong><button className="close-player" aria-label="Close player" onClick={() => setSelectedAudio(null)}>X</button></div>
      <WaveformAudio key={selectedAudio.id} recording={selectedAudio} />
      <p>{formatDate(selectedAudio.startTime)} | {formatDuration(selectedAudio.durationSeconds)} | {formatBytes(selectedAudio.fileSizeBytes)}</p>
    </div></div>}
    {liveDevice && <LiveViewer device={liveDevice} onClose={() => stopLive(liveDevice.deviceId)} />}
  </div>
}
