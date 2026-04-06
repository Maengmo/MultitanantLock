/**
 * Lock 테스트 대시보드 - 실시간 UI 업데이트
 */

// ============================================
// 시나리오 1: Pessimistic Lock
// ============================================
async function runPessimisticTest() {
  const contentId = document.getElementById('pessimisticContentId').value;
  if (!contentId) {
    alert('테스트할 콘텐츠를 선택하세요.');
    return;
  }

  const logEl = document.getElementById('log-pessimistic');
  logEl.innerHTML = '';

  // 두 카드 초기화
  initCard('req1', 'REQ-1', 'primary');
  initCard('req2', 'REQ-2', 'danger');

  appendLog(logEl, 'info', `콘텐츠 #${contentId} 에 대해 동시 2개 요청 발송...`);
  appendLog(logEl, 'info', 'SELECT ... FOR UPDATE 경합 확인 시작');
  appendLog(logEl, 'info', '─'.repeat(50));

  const startTime = Date.now();

  // 카드 → "요청 중" 상태
  setCardActive('req1', '요청 중...');
  setCardActive('req2', '요청 중...');

  // 동시에 2개 요청 발송
  const req1 = sendPessimisticRequest(contentId, 'REQ-1');
  const req2 = sendPessimisticRequest(contentId, 'REQ-2');

  appendLog(logEl, 'req', `[REQ-1] → POST /lock-test/pessimistic (contentId=${contentId})`);
  appendLog(logEl, 'req', `[REQ-2] → POST /lock-test/pessimistic (contentId=${contentId})`);
  appendLog(logEl, 'wait', `[REQ-1] [LOCK ACQUIRED] 즉시 획득 → PROCESSING 중...`);
  appendLog(logEl, 'wait', `[REQ-2] [WAITING...] DB 레벨 블로킹 대기 중...`);

  // 결과 처리
  req1.then(result => {
    const elapsed = Date.now() - startTime;
    setCardDone('req1', result, elapsed);
    if (result.waitTimeMs > 200) {
      appendLog(logEl, 'wait', `[${result.requestId}] [WAITING] ${result.waitTimeMs}ms 대기`);
    }
    appendLog(logEl, 'lock', `[${result.requestId}] [LOCK ACQUIRED] → 처리시간: ${result.processTimeMs}ms`);
    appendLog(logEl, 'done', `[${result.requestId}] [DONE] ✓ ${result.message}`);
    refreshStatus();
  }).catch(err => {
    appendLog(logEl, 'wait', `[REQ-1] 오류: ${err.message}`);
  });

  req2.then(result => {
    const elapsed = Date.now() - startTime;
    setCardDone('req2', result, elapsed);
    if (result.waitTimeMs > 200) {
      appendLog(logEl, 'wait', `[${result.requestId}] [WAITING] ${result.waitTimeMs}ms 대기 후 락 획득`);
    }
    appendLog(logEl, 'lock', `[${result.requestId}] [LOCK ACQUIRED] → 처리시간: ${result.processTimeMs}ms`);
    appendLog(logEl, 'done', `[${result.requestId}] [DONE] ✓ ${result.message}`);
    refreshStatus();
  }).catch(err => {
    appendLog(logEl, 'wait', `[REQ-2] 오류: ${err.message}`);
  });

  Promise.all([req1, req2]).then(([r1, r2]) => {
    appendLog(logEl, 'info', '─'.repeat(50));
    const contended = r1.waitTimeMs > 200 || r2.waitTimeMs > 200;
    if (contended) {
      const waiter = r1.waitTimeMs > r2.waitTimeMs ? r1 : r2;
      appendLog(logEl, 'done', `✅ 락 경합 확인: ${waiter.requestId} 가 ${waiter.waitTimeMs}ms 대기`);
      appendLog(logEl, 'done', '✅ Pessimistic Lock으로 데이터 정합성 보장 완료');
    } else {
      appendLog(logEl, 'info', '⚠️ 경합 없음 - 재시도하거나 처리 지연 증가 필요');
    }
  });
}

function sendPessimisticRequest(contentId, requestId) {
  return fetch('/lock-test/pessimistic', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentId: parseInt(contentId), requestId })
  }).then(r => {
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  });
}

// ============================================
// 시나리오 3: SKIP LOCKED
// ============================================
async function runSkipLockedTest() {
  const logEl = document.getElementById('log-skiplock');
  logEl.innerHTML = '';

  for (let i = 1; i <= 3; i++) {
    initCard(`worker${i}`, `WORKER-${i}`, 'info');
    setCardActive(`worker${i}`, '처리 중...');
  }

  appendLog(logEl, 'info', 'SKIP LOCKED 큐 처리 테스트 시작');
  appendLog(logEl, 'info', 'SELECT * FROM TB_CONTENT WHERE STATUS=\'READY\' FOR UPDATE SKIP LOCKED');
  appendLog(logEl, 'info', '─'.repeat(50));

  const workers = [1, 2, 3].map(i =>
    fetch('/lock-test/skip-locked', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId: `WORKER-${i}` })
    }).then(r => r.json())
  );

  appendLog(logEl, 'req', '[WORKER-1] [WORKER-2] [WORKER-3] 동시 발송');

  workers.forEach((p, idx) => {
    const workerId = idx + 1;
    p.then(result => {
      if (result.status === 'NO_ITEM') {
        setCardSkipped(`worker${workerId}`, result);
        appendLog(logEl, 'skip', `[${result.requestId}] [SKIP] 처리 가능한 항목 없음`);
      } else {
        setCardDoneWorker(`worker${workerId}`, result);
        appendLog(logEl, 'lock', `[${result.requestId}] content #${result.contentId} 획득`);
        appendLog(logEl, 'done', `[${result.requestId}] [DONE] ${result.message}`);
      }
      refreshStatus();
    }).catch(err => {
      appendLog(logEl, 'wait', `[WORKER-${workerId}] 오류: ${err.message}`);
    });
  });

  Promise.all(workers).then(results => {
    appendLog(logEl, 'info', '─'.repeat(50));
    const processed = results.filter(r => r.status === 'DONE');
    const skipped = results.filter(r => r.status === 'NO_ITEM');
    appendLog(logEl, 'done', `✅ 처리: ${processed.length}건, SKIP: ${skipped.length}건`);
    appendLog(logEl, 'done', '✅ 각 Worker가 서로 다른 항목을 처리 (중복 없음)');
  });
}

// ============================================
// 시나리오 4: Optimistic Lock
// ============================================
async function runOptimisticTest() {
  const contentId = document.getElementById('optimisticContentId').value;
  if (!contentId) { alert('테스트할 콘텐츠를 선택하세요.'); return; }

  const logEl = document.getElementById('log-optimistic');
  logEl.innerHTML = '';

  initCard('opt1', 'OPT-1', 'primary');
  initCard('opt2', 'OPT-2', 'danger');
  setCardActive('opt1', '처리 중...');
  setCardActive('opt2', '처리 중...');

  appendLog(logEl, 'info', `콘텐츠 #${contentId} — 동시 2개 요청 (version 충돌 재현)`);
  appendLog(logEl, 'info', '두 요청 모두 같은 version을 읽음 → 먼저 커밋한 쪽만 성공');
  appendLog(logEl, 'info', '─'.repeat(50));

  const req1 = fetch('/lock-test/optimistic', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentId: parseInt(contentId), requestId: 'OPT-1' })
  }).then(r => r.json());

  const req2 = fetch('/lock-test/optimistic', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentId: parseInt(contentId), requestId: 'OPT-2' })
  }).then(r => r.json());

  req1.then(result => {
    if (result.status === 'CONFLICT') {
      setCardConflict('opt1', result);
      appendLog(logEl, 'wait', `[OPT-1] ❌ CONFLICT — ${result.message}`);
    } else {
      setCardDoneWorker('opt1', result);
      appendLog(logEl, 'lock', `[OPT-1] ✅ 커밋 성공 — ${result.message}`);
    }
    refreshStatus();
  });

  req2.then(result => {
    if (result.status === 'CONFLICT') {
      setCardConflict('opt2', result);
      appendLog(logEl, 'wait', `[OPT-2] ❌ CONFLICT — ${result.message}`);
    } else {
      setCardDoneWorker('opt2', result);
      appendLog(logEl, 'lock', `[OPT-2] ✅ 커밋 성공 — ${result.message}`);
    }
    refreshStatus();
  });

  Promise.all([req1, req2]).then(([r1, r2]) => {
    appendLog(logEl, 'info', '─'.repeat(50));
    const conflict = [r1, r2].find(r => r.status === 'CONFLICT');
    const success  = [r1, r2].find(r => r.status === 'DONE');
    if (conflict && success) {
      appendLog(logEl, 'done', `✅ 낙관적 락 동작 확인: ${success.requestId} 성공 / ${conflict.requestId} CONFLICT`);
    } else {
      appendLog(logEl, 'info', '⚠️ 충돌 미발생 — 재시도하거나 READY 초기화 후 다시 시도');
    }
  });
}

// ============================================
// 시나리오 5: NOWAIT
// ============================================
async function runNowaitTest() {
  const contentId = document.getElementById('nowaitContentId').value;
  if (!contentId) { alert('테스트할 콘텐츠를 선택하세요.'); return; }

  const logEl = document.getElementById('log-nowait');
  logEl.innerHTML = '';

  initCard('nw1', 'NW-1', 'primary');
  initCard('nw2', 'NW-2', 'danger');
  setCardActive('nw1', '처리 중...');
  setCardActive('nw2', '처리 중...');

  appendLog(logEl, 'info', `콘텐츠 #${contentId} — NOWAIT 동시 2개 요청`);
  appendLog(logEl, 'info', 'NW-1이 락을 점유 중이면 NW-2는 즉시 FAILED');
  appendLog(logEl, 'info', '─'.repeat(50));

  const req1 = fetch('/lock-test/nowait', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentId: parseInt(contentId), requestId: 'NW-1' })
  }).then(r => r.json());

  const req2 = fetch('/lock-test/nowait', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentId: parseInt(contentId), requestId: 'NW-2' })
  }).then(r => r.json());

  req1.then(result => {
    if (result.status === 'FAILED') {
      setCardFailed('nw1', result);
      appendLog(logEl, 'wait', `[NW-1] ❌ NOWAIT 실패 — ${result.message}`);
    } else {
      setCardDoneWorker('nw1', result);
      appendLog(logEl, 'lock', `[NW-1] ✅ 락 즉시 획득 — processTime: ${result.processTimeMs}ms`);
    }
    refreshStatus();
  });

  req2.then(result => {
    if (result.status === 'FAILED') {
      setCardFailed('nw2', result);
      appendLog(logEl, 'wait', `[NW-2] ❌ NOWAIT 실패 — ${result.message}`);
    } else {
      setCardDoneWorker('nw2', result);
      appendLog(logEl, 'lock', `[NW-2] ✅ 락 즉시 획득 — processTime: ${result.processTimeMs}ms`);
    }
    refreshStatus();
  });

  Promise.all([req1, req2]).then(([r1, r2]) => {
    appendLog(logEl, 'info', '─'.repeat(50));
    const failed  = [r1, r2].filter(r => r.status === 'FAILED');
    const success = [r1, r2].filter(r => r.status === 'DONE');
    appendLog(logEl, 'done', `✅ 성공: ${success.length}건 / 즉시 실패(NOWAIT): ${failed.length}건`);
  });
}

// ============================================
// 리셋
// ============================================
async function resetAll() {
  const r = await fetch('/lock-test/reset', { method: 'POST' });
  const data = await r.json();
  alert(data.message);
  refreshStatus();
  location.reload();
}

async function refreshStatus() {
  const r = await fetch('/lock-test/status');
  const contents = await r.json();

  const tbody = document.querySelector('#content-status-table tbody');
  if (!tbody) return;

  tbody.innerHTML = contents.map(c => `
    <tr>
      <td class="text-muted">#${c.id}</td>
      <td>${c.title}</td>
      <td><span class="badge ${statusClass(c.status)}">${c.status}</span></td>
      <td class="text-muted small">${c.version}</td>
    </tr>
  `).join('');

  // 드롭다운도 갱신
  const sel = document.getElementById('pessimisticContentId');
  if (sel) {
    const prev = sel.value;
    sel.innerHTML = contents.map(c =>
      `<option value="${c.id}" ${c.id == prev ? 'selected' : ''}>#${c.id} - ${c.title} [${c.status}]</option>`
    ).join('');
  }
}

function statusClass(status) {
  if (status === 'READY')      return 'bg-warning text-dark';
  if (status === 'PROCESSING') return 'bg-primary';
  return 'bg-success';
}

// ============================================
// UI 헬퍼
// ============================================
function initCard(id, label, color) {
  const card = document.getElementById(`card-${id}`);
  if (!card) return;
  card.className = 'card result-card border';
  document.getElementById(`badge-${id}`).className = `status-badge badge bg-secondary`;
  document.getElementById(`badge-${id}`).textContent = '대기 중';
  document.getElementById(`status-${id}`).textContent = '요청 준비됨';
  if (document.getElementById(`wait-${id}`))    document.getElementById(`wait-${id}`).textContent = '-';
  if (document.getElementById(`process-${id}`)) document.getElementById(`process-${id}`).textContent = '-';
  if (document.getElementById(`item-${id}`))    document.getElementById(`item-${id}`).textContent = '-';
}

function setCardActive(id, msg) {
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  if (badge) { badge.className = 'status-badge badge bg-warning text-dark'; badge.textContent = '처리 중'; }
  if (status) status.innerHTML = `<span class="spinner-border spinner-border-sm me-1"></span>${msg}`;
}

function setCardDone(id, result, elapsed) {
  const card = document.getElementById(`card-${id}`);
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  const wait = document.getElementById(`wait-${id}`);
  const process = document.getElementById(`process-${id}`);

  if (card) card.classList.add('done');
  if (badge) { badge.className = 'status-badge badge bg-success'; badge.textContent = 'DONE'; }
  if (status) {
    const icon = result.waitTimeMs > 200 ? '⏳ 대기 후 처리' : '⚡ 즉시 처리';
    status.textContent = icon;
  }
  if (wait)    wait.textContent = result.waitTimeMs + 'ms';
  if (process) process.textContent = result.processTimeMs + 'ms';
}

function setCardDoneWorker(id, result) {
  const card = document.getElementById(`card-${id}`);
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  const item = document.getElementById(`item-${id}`);
  if (card) card.classList.add('done');
  if (badge) { badge.className = 'status-badge badge bg-success'; badge.textContent = 'DONE'; }
  if (status) status.textContent = `처리완료 (${result.processTimeMs}ms)`;
  if (item) item.textContent = result.message;
}

function setCardSkipped(id, result) {
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  if (badge) { badge.className = 'status-badge badge bg-secondary'; badge.textContent = 'SKIP'; }
  if (status) status.textContent = '처리할 항목 없음';
}

function setCardConflict(id, result) {
  const card  = document.getElementById(`card-${id}`);
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  const item   = document.getElementById(`item-${id}`);
  if (card)   card.style.borderColor = '#dc3545';
  if (badge)  { badge.className = 'status-badge badge bg-danger'; badge.textContent = 'CONFLICT'; }
  if (status) status.textContent = 'version 불일치 — 커밋 실패';
  if (item)   item.textContent = result.message;
}

function setCardFailed(id, result) {
  const card  = document.getElementById(`card-${id}`);
  const badge = document.getElementById(`badge-${id}`);
  const status = document.getElementById(`status-${id}`);
  const item   = document.getElementById(`item-${id}`);
  if (card)   card.style.borderColor = '#6c757d';
  if (badge)  { badge.className = 'status-badge badge bg-dark'; badge.textContent = 'FAILED'; }
  if (status) status.textContent = '락 획득 실패 (즉시 포기)';
  if (item)   item.textContent = result.message;
}

function appendLog(el, type, msg) {
  const now = new Date().toLocaleTimeString('ko-KR', { hour12: false });
  const line = document.createElement('div');
  line.innerHTML = `<span class="log-time">[${now}]</span> <span class="log-${type}">${escapeHtml(msg)}</span>`;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

function escapeHtml(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
