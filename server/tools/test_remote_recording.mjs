// Run against an isolated API with an empty test database, never the real archive.
// node server/tools/test_remote_recording.mjs http://127.0.0.1:15000
import assert from 'node:assert/strict'
import { once } from 'node:events'

const base = process.argv[2] || 'http://127.0.0.1:15000'
const deviceId = `remote-test-${Date.now()}`
const request = { action: 'start', quality: 'High', segmentMinutes: 3, startAlert: 'SoundOnly' }
const status = {
  deviceId, deviceName: 'Protocol test phone', manufacturer: 'Test', model: 'Test',
  androidVersion: 'test', appVersion: 'test', batteryLevel: 70, chargingSource: 'None',
  liveAccessEnabled: false, remoteControlEnabled: false,
  backgroundVideoQuality: 'Balanced', videoSegmentMinutes: 5, startAlert: 'Silent',
}
async function post(path, payload) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  })
  const text = await response.text()
  let body = null
  if (text.trim()) {
    try { body = JSON.parse(text) } catch { body = { raw: text } }
  }
  return { status: response.status, body }
}
const command = payload => post(`/api/devices/${deviceId}/recording`, payload)
let socket
try {
  assert.equal((await post('/api/devices/heartbeat', status)).status, 200)
  assert.equal((await command(request)).status, 403, 'Default-deny for control permission')
  status.remoteControlEnabled = true
  await post('/api/devices/heartbeat', status)
  assert.equal((await command(request)).status, 409, 'No offline command queue')
  assert.equal((await command({ ...request, segmentMinutes: -1 })).status, 400)
  assert.equal((await command({ ...request, segmentMinutes: 1.5 })).status, 400)
  assert.equal((await command({ ...request, quality: 'invalid' })).status, 400)
  assert.equal((await command({ ...request, action: 'grant_permission' })).status, 400)
  socket = new WebSocket(`${base.replace(/^http/, 'ws')}/api/devices/socket?deviceId=${deviceId}`)
  await once(socket, 'open')
  const nextCommand = () => new Promise(resolve => {
    const listener = event => {
      const message = JSON.parse(event.data)
      if (message.type !== 'recording_request') return
      socket.removeEventListener('message', listener)
      resolve(message)
    }
    socket.addEventListener('message', listener)
  })
  const incoming = nextCommand()
  const pending = command(request)
  const message = await incoming
  assert.equal(message.quality, 'High')
  assert.equal(message.segmentMinutes, 3)
  assert.equal(message.startAlert, 'SoundOnly')
  const receivedAt = Date.now()
  const expiresAt = Number(message.expiresAt)
  assert.ok(expiresAt > receivedAt && expiresAt <= receivedAt + 31_000,
    `Unexpected command expiry: expiresAt=${expiresAt}, receivedAt=${receivedAt}`)
  assert.equal((await command(request)).status, 409, 'Only one command per phone at a time')
  let completed = false
  pending.then(() => { completed = true })
  socket.send(JSON.stringify({ type: 'recording_response', requestId: 'wrong-id', success: true }))
  await new Promise(resolve => setTimeout(resolve, 100))
  assert.equal(completed, false, 'A stale confirmation must not complete another command')
  socket.send(JSON.stringify({ type: 'recording_response', requestId: message.requestId,
    success: true, backgroundRecordingActive: true, quality: 'High', segmentMinutes: 3, startAlert: 'SoundOnly' }))
  const result = await pending
  assert.equal(result.status, 200)
  assert.equal(result.body.success, true)
  assert.equal(result.body.backgroundRecordingActive, true)
  const incomingStop = nextCommand()
  const stopPending = command({ action: 'stop' })
  const stopMessage = await incomingStop
  socket.send(JSON.stringify({ type: 'recording_response', requestId: stopMessage.requestId,
    success: false, error: 'Control disabled on phone', backgroundRecordingActive: true }))
  assert.equal((await stopPending).body.success, false, 'Phone rejection must be reported, not treated as success')
  status.remoteControlEnabled = false
  await post('/api/devices/heartbeat', status)
  assert.equal((await command(request)).status, 403, 'Revoked permission blocks commands even while connected')
  status.remoteControlEnabled = true
  socket.send(JSON.stringify({ type: 'device_status', status }))
  await new Promise(resolve => setTimeout(resolve, 150))
  const devices = await (await fetch(`${base}/api/devices`)).json()
  const device = devices.items.find(item => item.deviceId === deviceId)
  assert.equal(device.onlineSource, 'websocket', 'Remote-only connection is online without Live Access')
  assert.equal(device.remoteControlEnabled, true)
  assert.equal(device.liveAccessEnabled, false)
  assert.equal(device.backgroundVideoQuality, 'Balanced')
  console.log('PASS: permission, validation, offline rejection, concurrency, request IDs, phone acknowledgement and remote-only status')
} finally {
  socket?.close()
}
