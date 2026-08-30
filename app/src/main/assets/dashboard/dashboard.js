/**
 * Camera Security Lab — local dashboard client script.
 *
 * This script only ever talks to the SAME host that served this page
 * (window.location.origin) — it never hardcodes a different address, never
 * calls out to a third-party domain, and never sends collected data
 * anywhere except back to that same local device via /status.
 *
 * There is no remote-control endpoint for camera on/off/switch here: those
 * actions are only available on-device via physical buttons. The ONLY
 * network-triggered actions this page can cause are the Capture Photo /
 * Capture Video buttons below, and those only work when the device owner
 * has explicitly turned on "Allow Remote Capture" on-device. If that's off,
 * the device responds 403 and this page shows a clear "blocked" result —
 * it never fakes a success.
 */
(function () {
  const origin = window.location.origin;
  const streamImg = document.getElementById('streamImg');
  const noStream = document.getElementById('noStream');
  const activeBadge = document.getElementById('streamingActiveBadge');
  const connDot = document.getElementById('connDot');
  const connStatus = document.getElementById('connStatus');
  const statConnection = document.getElementById('statConnection');
  const statCamera = document.getElementById('statCamera');
  const statStreaming = document.getElementById('statStreaming');
  const statAddress = document.getElementById('statAddress');
  const clientLog = document.getElementById('clientLog');
  const refreshBtn = document.getElementById('refreshBtn');

  const gateBadge = document.getElementById('remoteCaptureGate');
  const capturePhotoBtn = document.getElementById('capturePhotoBtn');
  const captureVideoBtn = document.getElementById('captureVideoBtn');
  const captureResult = document.getElementById('captureResult');
  const capturedPhoto = document.getElementById('capturedPhoto');
  const capturedVideo = document.getElementById('capturedVideo');
  const statReqTime = document.getElementById('statReqTime');
  const statRtt = document.getElementById('statRtt');
  const statBytes = document.getElementById('statBytes');
  const statRate = document.getElementById('statRate');

  statAddress.textContent = origin.replace(/^https?:\/\//, '');

  function logEvent(message) {
    const li = document.createElement('li');
    const time = new Date().toLocaleTimeString();
    li.innerHTML = `<span class="t">${time}</span>${message}`;
    clientLog.prepend(li);
    while (clientLog.children.length > 30) {
      clientLog.removeChild(clientLog.lastChild);
    }
  }

  function setConnected(connected) {
    connDot.className = 'dot ' + (connected ? 'connected' : 'disconnected');
    connStatus.textContent = connected ? 'Connected' : 'Disconnected';
    statConnection.textContent = connected ? 'Connected' : 'Disconnected';
  }

  function setRemoteCaptureGate(allowed) {
    if (allowed) {
      gateBadge.textContent = 'Allow Remote Capture: ON — buttons below will trigger real captures';
      gateBadge.className = 'gate-badge allowed';
    } else {
      gateBadge.textContent = 'Allow Remote Capture: OFF on device — buttons will be refused (403)';
      gateBadge.className = 'gate-badge blocked';
    }
    // Buttons stay enabled either way — pressing them while OFF is itself a
    // valid test of the refusal path, and the device logs that refusal too.
    capturePhotoBtn.disabled = false;
    captureVideoBtn.disabled = false;
  }

  async function pollStatus() {
    try {
      const res = await fetch(origin + '/status', { cache: 'no-store' });
      if (!res.ok) throw new Error('status ' + res.status);
      const data = await res.json();

      setConnected(true);
      statStreaming.textContent = data.streaming ? 'ACTIVE' : 'Stopped';
      activeBadge.classList.toggle('hidden', !data.streaming);
      setRemoteCaptureGate(!!data.remoteCaptureAllowed);

      if (data.streaming) {
        statCamera.textContent = 'Providing frames';
        ensureStreamAttached();
      } else {
        statCamera.textContent = 'Idle / not streaming';
        detachStream();
      }
    } catch (err) {
      setConnected(false);
      statCamera.textContent = '—';
      statStreaming.textContent = '—';
      detachStream();
    }
  }

  let streamAttached = false;
  function ensureStreamAttached() {
    if (streamAttached) return;
    streamAttached = true;
    streamImg.src = origin + '/stream?cachebust=' + Date.now();
    streamImg.classList.add('visible');
    noStream.style.display = 'none';
    logEvent('Stream connected');

    streamImg.onerror = () => {
      detachStream();
      logEvent('Stream ended or unreachable');
    };
  }

  function detachStream() {
    if (!streamAttached) return;
    streamAttached = false;
    streamImg.classList.remove('visible');
    streamImg.removeAttribute('src');
    noStream.style.display = 'block';
  }

  function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }

  /**
   * Performs the actual network round trip: sends a GET to /capture/photo
   * or /capture/video, times it with performance.now(), and measures the
   * real byte size of the response — this is the "does data really move
   * over the network" test.
   */
  async function runCapture(kind) {
    const path = kind === 'video' ? '/capture/video' : '/capture/photo';
    const requestedAt = new Date();
    const t0 = performance.now();

    logEvent(`Requesting ${kind} capture over the network…`);
    statReqTime.textContent = requestedAt.toLocaleTimeString();
    statRtt.textContent = 'In progress…';
    statBytes.textContent = '—';
    statRate.textContent = '—';
    captureResult.classList.remove('hidden');
    capturedPhoto.classList.add('hidden');
    capturedVideo.classList.add('hidden');

    try {
      const res = await fetch(origin + path, { method: 'GET', cache: 'no-store' });
      const t1 = performance.now();
      const rttMs = Math.round(t1 - t0);

      if (res.status === 403) {
        statRtt.textContent = rttMs + ' ms (BLOCKED — 403)';
        statBytes.textContent = '0 B';
        statRate.textContent = '—';
        logEvent(`${kind} capture BLOCKED by device — Allow Remote Capture is OFF (this is expected and correct).`);
        return;
      }

      if (!res.ok) {
        statRtt.textContent = rttMs + ' ms (failed — ' + res.status + ')';
        logEvent(`${kind} capture failed with HTTP ${res.status}.`);
        return;
      }

      const blob = await res.blob();
      const t2 = performance.now();
      const totalMs = Math.round(t2 - t0);
      const bytes = blob.size;
      const rateKBs = bytes > 0 ? (bytes / 1024) / (totalMs / 1000) : 0;

      statRtt.textContent = totalMs + ' ms';
      statBytes.textContent = formatBytes(bytes);
      statRate.textContent = rateKBs.toFixed(1) + ' KB/s';

      const url = URL.createObjectURL(blob);
      if (kind === 'video') {
        capturedVideo.src = url;
        capturedVideo.classList.remove('hidden');
      } else {
        capturedPhoto.src = url;
        capturedPhoto.classList.remove('hidden');
      }

      logEvent(`${kind} capture received — ${formatBytes(bytes)} transferred in ${totalMs} ms.`);
    } catch (err) {
      statRtt.textContent = 'Network error';
      logEvent(`${kind} capture request failed: ${err.message}`);
    }
  }

  capturePhotoBtn.addEventListener('click', () => runCapture('photo'));
  captureVideoBtn.addEventListener('click', () => runCapture('video'));

  refreshBtn.addEventListener('click', () => {
    logEvent('Manual refresh requested');
    pollStatus();
  });

  logEvent('Dashboard loaded — connecting to device at ' + origin);
  pollStatus();
  setInterval(pollStatus, 2500);
})();
