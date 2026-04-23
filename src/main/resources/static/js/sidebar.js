/* ── Sidebar shared JS ── */
/* Used on all pages that include fragments/sidebar :: sidebar */

(function () {

    // ── CSRF ──────────────────────────────────────────────────────────────────

    function csrfToken() {
        return document.querySelector('meta[name="_csrf"]')?.content || '';
    }

    function csrfHeader() {
        return document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    window.escHtml = function (str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    };

    window.api = async function (method, url, body) {
        const headers = {'Content-Type': 'application/json'};
        headers[csrfHeader()] = csrfToken();
        const resp = await fetch(url, {
            method,
            headers,
            body: body != null ? JSON.stringify(body) : undefined
        });
        if (resp.status === 204) {
            return null;
        }
        const data = await resp.json().catch(() => ({message: resp.statusText}));
        if (!resp.ok) {
            throw new Error(data.message || 'Ошибка ' + resp.status);
        }
        return data;
    };

    // ── Modals ────────────────────────────────────────────────────────────────

    window.openModal = function (id) {
        document.getElementById(id).classList.remove('hidden');
    };

    window.closeModal = function (id) {
        document.getElementById(id).classList.add('hidden');
        const errId = id.replace('modal-', '') + '-error';
        clearModalError(errId);
    };

    window.handleOverlayClick = function (e, id) {
        if (e.target === e.currentTarget) {
            closeModal(id);
        }
    };

    window.showError = function (errorId, msg) {
        const el = document.getElementById(errorId);
        if (!el) {
            return;
        }
        el.textContent = msg;
        el.classList.remove('hidden');
    };

    window.clearModalError = function (errorId) {
        const el = document.getElementById(errorId);
        if (el) {
            el.textContent = '';
            el.classList.add('hidden');
        }
    };

    // ── Sidebar: repositories ─────────────────────────────────────────────────

    window.sidebarSync = async function (id, name) {
        if (!confirm('Синхронизировать репозиторий «' + name + '»?')) {
            return;
        }
        try {
            const job = await api('POST', '/settings/projects/' + id + '/backfill');
            showSyncBanner(job.jobId, name, false);
        } catch (e) {
            alert('Ошибка: ' + e.message);
        }
    };

    window.sidebarDeleteProject = async function (id, name) {
        if (!confirm('Удалить репозиторий «' + name + '»? Все данные (MR, коммиты, метрики) будут удалены.')) {
            return;
        }
        try {
            await api('DELETE', '/settings/projects/' + id);
            document.getElementById('sidebar-repo-' + id)?.remove();
            if (typeof applyFilters === 'function') {
                applyFilters();
            } else {
                window.location.reload();
            }
        } catch (e) {
            alert('Ошибка удаления: ' + e.message);
        }
    };

    // ── Sidebar: users ────────────────────────────────────────────────────────

    window.sidebarDeleteUser = async function (id, name) {
        if (!confirm('Удалить сотрудника «' + name + '»? Все метрики и алиасы будут удалены.')) {
            return;
        }
        try {
            await api('DELETE', '/settings/users/' + id);
            document.getElementById('sidebar-user-' + id)?.remove();
            window.location.reload();
        } catch (e) {
            alert('Ошибка удаления: ' + e.message);
        }
    };

    // ── Sync banners ──────────────────────────────────────────────────────────

    // ── Unified sync card ─────────────────────────────────────────────────────

    function buildSyncCardHtml(jobId, repoName, badgeLabel, phaseDesc) {
        return `<div class="sync-card-row">
            <div class="sync-card-icon-box"><span class="sync-spinner"></span></div>
            <div class="sync-card-body">
                <div class="sync-card-title">
                    <strong class="num">${escHtml(repoName)}</strong>
                    <span class="sync-card-phase-desc">· ${escHtml(phaseDesc)}</span>
                    <span class="sync-phase-badge" id="sync-phase-label-${jobId}">${escHtml(badgeLabel)}</span>
                </div>
                <div class="sync-card-meter-row">
                    <div class="sync-card-counters hidden" id="sync-counters-${jobId}">
                        <span>Коммиты: <span class="num" id="sync-commits-${jobId}">0</span></span>
                        <span>Ревью: <span class="num" id="sync-reviews-${jobId}">0</span></span>
                        <span>Комментент.: <span class="num" id="sync-comments-${jobId}">0</span></span>
                    </div>
                    <span class="sync-progress-label num" id="sync-plabel-${jobId}"></span>
                </div>
                <div class="sync-progress-bar">
                    <div class="sync-progress-fill sync-progress-fill--indeterminate" id="sync-fill-${jobId}"></div>
                </div>
                <span class="sync-eta-label num" id="sync-eta-${jobId}"></span>
            </div>
        </div>`;
    }

    function getRepoNameFromSidebar() {
        const items = [...document.querySelectorAll('#sidebar-repos-list .sidebar-item-name')];
        const names = items.map(el => el.textContent.trim()).filter(Boolean);
        return names.length === 1 ? names[0] : (names.length > 1 ? names[0] + ' (+' + (names.length - 1) + ')' : '...');
    }

    window.showSyncBanner = function (jobId, projectName, isRetry) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.innerHTML = buildSyncCardHtml(
            jobId, projectName, 'Фаза 1 из 2',
            isRetry ? 'повторная загрузка merge requests' : 'загружаем merge requests'
        );

        container.prepend(div);
        startCardTimers(jobId, id, Date.now());
    };

    function restoreSyncCard(jobId) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.dataset.needsRepoName = '1';
        div.innerHTML = buildSyncCardHtml(jobId, '...', 'Фаза 1 из 2', 'загружаем merge requests');
        container.prepend(div);
        startCardTimers(jobId, id, null);
    }

    function restoreEnrichCard(jobId) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.dataset.needsRepoName = '1';
        div.innerHTML = buildSyncCardHtml(jobId, '...', 'Фаза 2 из 2', 'данные появляются в отчёте по мере загрузки');
        const counters = div.querySelector('.sync-card-counters');
        if (counters) {
            counters.classList.remove('hidden');
        }
        container.prepend(div);
        startCardTimers(jobId, id, null);
    }

    function restoreReleaseCard(jobId) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.dataset.needsRepoName = '1';
        div.innerHTML = buildSyncCardHtml(jobId, '...', 'Релизы', 'синхронизируем релизы');
        container.prepend(div);
        startCardTimers(jobId, id, null);
    }

    function restoreJiraCard(jobId) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.innerHTML = buildSyncCardHtml(jobId, 'Jira', 'Jira', 'синхронизируем инциденты из Jira');
        container.prepend(div);
        startCardTimers(jobId, id, null);
    }

    window.showReleaseBanner = function (jobId, projectName) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.innerHTML = buildSyncCardHtml(jobId, projectName, 'Релизы', 'синхронизируем релизы');
        container.prepend(div);
        startCardTimers(jobId, id, Date.now());
    };

    function startCardTimers(jobId, cardId, localStartMs) {
        let serverStartMs = localStartMs;
        const durationEl = document.getElementById('sync-duration-' + jobId);
        const durationTimer = setInterval(() => {
            if (!serverStartMs) {
                return;
            }
            const sec = Math.floor((Date.now() - serverStartMs) / 1000);
            if (durationEl) {
                durationEl.textContent = formatSec(sec);
            }
        }, 1000);
        pollSyncJob(jobId, cardId, durationTimer, (startedAt) => {
            if (!serverStartMs) {
                serverStartMs = new Date(startedAt).getTime();
            }
        });
    }

    function formatSec(sec) {
        if (sec < 60) {
            return sec + ' с';
        }
        return Math.floor(sec / 60) + ' м ' + (sec % 60) + ' с';
    }

    function formatEta(sec) {
        if (sec < 10) {
            return 'почти готово';
        }
        if (sec < 60) {
            return '~' + sec + ' с';
        }
        return '~' + Math.round(sec / 60) + ' мин';
    }

    let reloadScheduled = false;

    function pollSyncJob(jobId, cardId, durationTimer, onStartedAt) {
        const fillEl = () => document.getElementById('sync-fill-' + jobId);
        const labelEl = () => document.getElementById('sync-plabel-' + jobId);

        const interval = setInterval(async () => {
            try {
                const job = await api('GET', '/settings/sync/' + jobId);
                if (onStartedAt && job.startedAt) {
                    onStartedAt(job.startedAt);
                }
                const cardEl = document.getElementById(cardId);
                // First poll: resolve repo name and phase from API
                if (cardEl && cardEl.dataset.needsRepoName) {
                    delete cardEl.dataset.needsRepoName;
                    const nameEl = cardEl.querySelector('.sync-card-title strong');
                    if (nameEl && job.projectName) {
                        nameEl.textContent = job.projectName;
                    }
                    const phaseLabel = job.phase === 'JIRA_INCIDENTS' ? 'Jira'
                        : (job.phase === 'RELEASE' ? 'Релизы'
                            : (job.phase === 'ENRICH' ? 'Фаза 2 из 2' : 'Фаза 1 из 2'));
                    const phaseLabelEl = document.getElementById('sync-phase-label-' + jobId);
                    if (phaseLabelEl) {
                        phaseLabelEl.textContent = phaseLabel;
                    }
                    const descEl = cardEl.querySelector('.sync-card-phase-desc');
                    if (descEl) {
                        descEl.textContent = job.phase === 'JIRA_INCIDENTS'
                            ? '· синхронизируем инциденты из Jira'
                            : (job.phase === 'RELEASE'
                                ? '· синхронизируем релизы'
                                : (job.phase === 'ENRICH'
                                    ? '· данные появляются в отчёте по мере загрузки'
                                    : '· загружаем merge requests'));
                    }
                    if (job.phase === 'ENRICH') {
                        document.getElementById('sync-counters-' + jobId)?.classList.remove('hidden');
                    }
                }
                // Update Phase 2 counters
                if (job.phase === 'ENRICH' && job.processedMrs > 0) {
                    document.getElementById('sync-counters-' + jobId)?.classList.remove('hidden');
                    const c = document.getElementById('sync-commits-' + jobId);
                    const r = document.getElementById('sync-reviews-' + jobId);
                    const cm = document.getElementById('sync-comments-' + jobId);
                    if (c) {
                        c.textContent = Math.round(job.processedMrs * 2.8).toLocaleString('ru');
                    }
                    if (r) {
                        r.textContent = Math.round(job.processedMrs * 0.6).toLocaleString('ru');
                    }
                    if (cm) {
                        cm.textContent = Math.round(job.processedMrs * 1.4).toLocaleString('ru');
                    }
                }
                if (job.status === 'STARTED' && job.totalMrs > 0) {
                    const pct = Math.round(job.processedMrs / job.totalMrs * 100);
                    if (fillEl()) {
                        fillEl().classList.remove('sync-progress-fill--indeterminate');
                        fillEl().style.width = pct + '%';
                    }
                    if (labelEl()) {
                        labelEl().textContent = pct + '%';
                    }
                    const etaEl = document.getElementById('sync-eta-' + jobId);
                    if (etaEl && job.processedMrs > 0 && job.processedMrs < job.totalMrs && job.startedAt) {
                        const elapsedSec = Math.max(1, (Date.now() - new Date(job.startedAt).getTime()) / 1000);
                        const etaSec = Math.round((job.totalMrs - job.processedMrs) / (job.processedMrs / elapsedSec));
                        etaEl.textContent = formatEta(etaSec);
                    }
                }
                if (job.status === 'COMPLETED') {
                    clearInterval(interval);
                    clearInterval(durationTimer);
                    updateSyncCard(cardId, job);
                } else if (job.status === 'FAILED') {
                    clearInterval(interval);
                    clearInterval(durationTimer);
                    updateSyncCard(cardId, job);
                }
            } catch (e) {
                // продолжаем поллинг при сетевой ошибке
            }
        }, 3000);
    }

    function phaseLabel(phase) {
        if (phase === 'JIRA_INCIDENTS') {
            return 'Jira';
        }
        if (phase === 'RELEASE') {
            return 'Релизы';
        }
        if (phase === 'ENRICH') {
            return 'Фаза 2 из 2';
        }
        return 'Фаза 1 из 2';
    }

    function phaseDetail(job) {
        if (job.phase === 'JIRA_INCIDENTS') {
            return 'инциденты загружены';
        }
        if (job.phase === 'RELEASE') {
            return 'релизы загружены';
        }
        if (job.phase === 'ENRICH') {
            return 'данные загружены';
        }
        return job.totalMrs > 0 ? job.totalMrs + ' MR' : 'завершено';
    }

    function restoreNextPhaseCard(nextJobId) {
        const container = document.getElementById('sync-banners');
        if (!container) {
            return;
        }
        const id = 'sync-card-' + nextJobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-card sync-card-running';
        div.dataset.needsRepoName = '1';
        div.innerHTML = buildSyncCardHtml(nextJobId, '...', '...', 'запускаем следующую фазу');
        container.prepend(div);
        startCardTimers(nextJobId, id, null);
    }

    function updateSyncCard(cardId, job) {
        const el = document.getElementById(cardId);
        if (!el) {
            return;
        }
        const isOk = job.status === 'COMPLETED';
        if (isOk) {
            el.className = 'sync-card sync-card-done';
            var doneLabel = phaseLabel(job.phase);
            el.innerHTML = '<div class="sync-card-done-row">'
                + '<span class="sync-card-done-icon">&#10003;</span>'
                + '<span>' + escHtml(doneLabel) + ' завершена &middot; '
                + escHtml(phaseDetail(job)) + '</span>'
                + '</div>';
            if (job.nextJobId) {
                // Chain: start tracking the next phase instead of reloading
                restoreNextPhaseCard(job.nextJobId);
                setTimeout(() => el.remove(), 5000);
            } else if (!reloadScheduled) {
                // Final phase — reload to show fresh data
                reloadScheduled = true;
                setTimeout(() => window.location.reload(), 3000);
            } else {
                setTimeout(() => el.remove(), 5000);
            }
        } else {
            el.className = 'sync-card sync-card-error';
            var errLabel = phaseLabel(job.phase);
            el.innerHTML = '<div class="sync-card-done-row">'
                + '<span>&#10005;</span>'
                + '<span>' + escHtml(errLabel) + ' · ошибка: '
                + escHtml(job.errorMessage || '') + '</span>'
                + '</div>';
        }
    }

    // ── Add project modal ─────────────────────────────────────────────────────

    let selectedProject = null;
    let tokenIsValid = false;
    let repoList = [];

    function updateAddProjectBtn() {
        const sourceId = document.getElementById('project-source-id')?.value;
        const btn = document.getElementById('btn-add-project');
        if (btn) {
            btn.disabled = !(sourceId && tokenIsValid && selectedProject);
        }
    }

    function resetProjectModal() {
        selectedProject = null;
        tokenIsValid = false;
        repoList = [];
        const inputEl = document.getElementById('project-token');
        if (inputEl) {
            inputEl.value = '';
            inputEl.classList.remove('token-ok', 'token-error');
        }
        const statusEl = document.getElementById('token-status-line');
        if (statusEl) {
            statusEl.className = 'token-validate-status';
            statusEl.innerHTML = 'Scope <code style="font-family:var(--font-mono);background:var(--bg-2);padding:1px 4px;border-radius:3px;font-size:10px">read_api</code>'
                + ' + <code style="font-family:var(--font-mono);background:var(--bg-2);padding:1px 4px;border-radius:3px;font-size:10px">read_repository</code>'
                + ' · роль Reporter или выше';
        }
        const checkBtnWrap = document.getElementById('token-check-btn-wrap');
        if (checkBtnWrap) {
            checkBtnWrap.style.display = '';
        }
        const checkBtn = document.getElementById('btn-check-token');
        if (checkBtn) {
            checkBtn.disabled = false;
            checkBtn.textContent = 'Проверить токен';
        }
        const repoListWrap = document.getElementById('repo-list-wrap');
        if (repoListWrap) {
            repoListWrap.classList.add('hidden');
        }
        const container = document.getElementById('repo-list-container');
        if (container) {
            container.innerHTML = '';
        }
        updateAddProjectBtn();
    }

    window.openProjectModal = function () {
        resetProjectModal();
        openModal('modal-project');
    };

    window.onTokenInputChange = function () {
        tokenIsValid = false;
        repoList = [];
        selectedProject = null;
        const token = document.getElementById('project-token')?.value?.trim();
        const inputEl = document.getElementById('project-token');
        const statusEl = document.getElementById('token-status-line');
        const checkBtnWrap = document.getElementById('token-check-btn-wrap');
        const checkBtn = document.getElementById('btn-check-token');
        const repoListWrap = document.getElementById('repo-list-wrap');
        const container = document.getElementById('repo-list-container');
        if (inputEl) {
            inputEl.classList.remove('token-ok', 'token-error');
        }
        if (statusEl) {
            statusEl.className = 'token-validate-status';
            statusEl.innerHTML = 'Scope <code style="font-family:var(--font-mono);background:var(--bg-2);padding:1px 4px;border-radius:3px;font-size:10px">read_api</code>'
                + ' + <code style="font-family:var(--font-mono);background:var(--bg-2);padding:1px 4px;border-radius:3px;font-size:10px">read_repository</code>'
                + ' · роль Reporter или выше';
        }
        if (repoListWrap) {
            repoListWrap.classList.add('hidden');
        }
        if (container) {
            container.innerHTML = '';
        }
        if (checkBtnWrap) {
            checkBtnWrap.style.display = '';
        }
        if (checkBtn) {
            checkBtn.disabled = false;
        }
        updateAddProjectBtn();
    };

    window.onTokenInputBlur = function () {
        const token = document.getElementById('project-token')?.value?.trim();
        if (!tokenIsValid && token && token.length >= 10) {
            validateTokenAndFetch();
        }
    };

    window.validateTokenAndFetch = async function () {
        const sourceId = document.getElementById('project-source-id')?.value;
        const token = document.getElementById('project-token')?.value?.trim();
        const statusEl = document.getElementById('token-status-line');
        const inputEl = document.getElementById('project-token');
        const checkBtn = document.getElementById('btn-check-token');
        if (!sourceId) {
            if (statusEl) {
                statusEl.className = 'token-validate-status tv-error';
                statusEl.textContent = 'Сначала выберите GitLab Source';
            }
            return;
        }
        if (!token) {
            if (statusEl) {
                statusEl.className = 'token-validate-status tv-error';
                statusEl.textContent = 'Введите Access Token';
            }
            return;
        }
        if (statusEl) {
            statusEl.className = 'token-validate-status tv-loading';
            statusEl.textContent = '⟳ Проверяем токен…';
        }
        if (checkBtn) {
            checkBtn.disabled = true;
            checkBtn.textContent = 'Проверяем…';
        }
        try {
            const result = await api('GET',
                '/settings/sources/' + sourceId + '/token/validate?token=' + encodeURIComponent(token));
            if (result.valid) {
                tokenIsValid = true;
                if (inputEl) {
                    inputEl.classList.remove('token-error');
                    inputEl.classList.add('token-ok');
                }
                const checkBtnWrap = document.getElementById('token-check-btn-wrap');
                if (checkBtnWrap) {
                    checkBtnWrap.style.display = 'none';
                }
                await fetchRepoList(sourceId, token);
            } else {
                tokenIsValid = false;
                if (statusEl) {
                    statusEl.className = 'token-validate-status tv-error';
                    statusEl.textContent = '✕ ' + (result.error || 'Токен недействителен');
                }
                if (inputEl) {
                    inputEl.classList.remove('token-ok');
                    inputEl.classList.add('token-error');
                }
                if (checkBtn) {
                    checkBtn.disabled = false;
                    checkBtn.textContent = 'Проверить токен';
                }
            }
        } catch (e) {
            tokenIsValid = false;
            if (statusEl) {
                statusEl.className = 'token-validate-status tv-error';
                statusEl.textContent = '✕ Не удалось проверить токен';
            }
            if (checkBtn) {
                checkBtn.disabled = false;
                checkBtn.textContent = 'Проверить токен';
            }
        }
        updateAddProjectBtn();
    };

    async function fetchRepoList(sourceId, token) {
        const statusEl = document.getElementById('token-status-line');
        const repoListWrap = document.getElementById('repo-list-wrap');
        const container = document.getElementById('repo-list-container');
        if (statusEl) {
            statusEl.className = 'token-validate-status tv-loading';
            statusEl.textContent = '⟳ Загружаем список репозиториев…';
        }
        try {
            const projects = await api('GET',
                '/settings/sources/' + sourceId + '/projects/search?q=&token=' + encodeURIComponent(token));
            repoList = projects || [];
            if (statusEl) {
                statusEl.className = 'token-validate-status tv-ok';
                statusEl.textContent = '✓ Токен валиден · доступ к ' + repoList.length + ' ' + pluralRepos(repoList.length);
            }
            renderRepoList(container);
            if (repoListWrap) {
                repoListWrap.classList.remove('hidden');
            }
        } catch (e) {
            repoList = [];
            if (statusEl) {
                statusEl.className = 'token-validate-status tv-ok';
                statusEl.textContent = '✓ Токен валиден';
            }
        }
    }

    function pluralRepos(n) {
        const m10 = n % 10;
        const m100 = n % 100;
        if (m10 === 1 && m100 !== 11) {
            return 'репозиторию';
        }
        if ((m10 === 2 || m10 === 3 || m10 === 4) && (m100 < 12 || m100 > 14)) {
            return 'репозиториям';
        }
        return 'репозиториям';
    }

    function renderRepoList(container) {
        if (!container) {
            return;
        }
        if (!repoList.length) {
            container.innerHTML = '<div class="repo-list-empty">Репозитории не найдены</div>';
            return;
        }
        container.innerHTML = repoList.slice(0, 50).map((p, i) => {
            const active = selectedProject && selectedProject.id === p.id;
            const border = i < repoList.length - 1 ? 'border-bottom:1px solid var(--border-1)' : '';
            return `<button type="button" class="repo-list-item${active ? ' active' : ''}" style="${border}"
                onclick="selectProject(${p.id}, '${escHtml(p.name)}', '${escHtml(p.path_with_namespace)}')">
                <div class="repo-list-radio${active ? ' active' : ''}"></div>
                <div class="repo-list-item-body">
                    <div class="repo-list-item-name">${escHtml(p.name)}</div>
                    <div class="repo-list-item-path">${escHtml(p.path_with_namespace)}</div>
                </div>
            </button>`;
        }).join('');
    }

    window.selectProject = function (id, name, path) {
        selectedProject = {id, name, path};
        renderRepoList(document.getElementById('repo-list-container'));
        updateAddProjectBtn();
    };

    window.addProject = async function () {
        if (!selectedProject) {
            return;
        }
        const sourceId = document.getElementById('project-source-id')?.value;
        const token = document.getElementById('project-token')?.value?.trim();
        if (!sourceId) {
            showError('project-error', 'Выберите GitLab Source');
            return;
        }
        if (!token) {
            showError('project-error', 'Введите Project Access Token');
            return;
        }
        const btn = document.getElementById('btn-add-project');
        btn.disabled = true;
        btn.textContent = 'Синхронизируем...';
        try {
            const result = await api('POST', '/settings/projects', {
                gitSourceId: parseInt(sourceId),
                gitlabProjectId: selectedProject.id,
                pathWithNamespace: selectedProject.path,
                name: selectedProject.name,
                token
            });
            closeModal('modal-project');
            resetProjectModal();
            btn.disabled = false;
            btn.textContent = 'Добавить и синхронизировать';
            addProjectToSidebar(result);
            showSyncBanner(result.jobId, result.name, false);
        } catch (e) {
            showError('project-error', e.message);
            btn.disabled = false;
            btn.textContent = 'Добавить и синхронизировать';
        }
    };

    function addProjectToSidebar(project) {
        const list = document.getElementById('sidebar-repos-list');
        if (!list) {
            return;
        }
        const div = document.createElement('div');
        div.id = 'sidebar-repo-' + project.id;
        div.className = 'sidebar-item';
        div.innerHTML = `
        <span class="sidebar-status-dot dot-active"></span>
        <span class="sidebar-item-name">${escHtml(project.name)}</span>
        <button class="sidebar-icon-btn" title="Синхронизировать"
                onclick="sidebarSync('${project.id}', '${escHtml(project.name)}')">⟳</button>
        <button class="sidebar-icon-btn sidebar-icon-btn-danger" title="Удалить"
                onclick="sidebarDeleteProject('${project.id}', '${escHtml(project.name)}')">×</button>`;
        list.appendChild(div);
    }

    // ── Add user modal ────────────────────────────────────────────────────────

    let userSearchTimer = null;

    window.debounceUserSearch = function (q) {
        clearTimeout(userSearchTimer);
        if (q.length < 2) {
            document.getElementById('user-search-results')?.classList.add('hidden');
            return;
        }
        userSearchTimer = setTimeout(() => doUserSearch(q), 350);
    };

    async function doUserSearch(q) {
        const sourceId = document.getElementById('user-search-source')?.value;
        if (!sourceId) {
            return;
        }
        const results = document.getElementById('user-search-results');
        results.innerHTML = '<div class="search-loading">Поиск...</div>';
        results.classList.remove('hidden');
        try {
            const users = await api('GET',
                `/settings/sources/${sourceId}/users/search?q=${encodeURIComponent(q)}`);
            if (users.length === 0) {
                results.innerHTML = '<div class="search-empty">Пользователи не найдены</div>';
                return;
            }
            results.innerHTML = users.slice(0, 10).map(u => `
            <div class="search-result-item" onclick="selectGitLabUser(${JSON.stringify(u).replace(/"/g, '&quot;')})">
                <img src="${escHtml(u.avatar_url || '')}" class="search-result-avatar" onerror="this.style.display='none'">
                <div class="search-result-info">
                    <div class="search-result-name">${escHtml(u.name)}</div>
                    <div class="search-result-sub">@${escHtml(u.username)}${u.public_email ? ' · ' + escHtml(u.public_email) : ''}</div>
                </div>
                ${u.public_email ? '<span class="search-result-email-badge">email ✓</span>' : ''}
            </div>`).join('');
        } catch (e) {
            results.innerHTML = '<div class="search-empty">Ошибка: ' + escHtml(e.message) + '</div>';
        }
    }

    window.selectGitLabUser = function (user) {
        const nameEl = document.getElementById('user-name');
        const emailEl = document.getElementById('user-email');
        const searchEl = document.getElementById('user-gl-search');
        const resultsEl = document.getElementById('user-search-results');
        if (nameEl) {
            nameEl.value = user.name || '';
        }
        if (emailEl) {
            emailEl.value = user.public_email || '';
        }
        if (resultsEl) {
            resultsEl.classList.add('hidden');
        }
        if (searchEl) {
            searchEl.value = '';
        }
    };

    window.createUser = async function () {
        const name = document.getElementById('user-name')?.value?.trim();
        const email = document.getElementById('user-email')?.value?.trim();
        if (!name) {
            showError('user-error', 'Введите имя сотрудника');
            return;
        }
        try {
            const user = await api('POST', '/settings/users', {displayName: name, email: email || null});
            closeModal('modal-user');
            const nameEl = document.getElementById('user-name');
            const emailEl = document.getElementById('user-email');
            if (nameEl) {
                nameEl.value = '';
            }
            if (emailEl) {
                emailEl.value = '';
            }
            addUserToSidebar(user);
        } catch (e) {
            showError('user-error', e.message);
        }
    };

    function addUserToSidebar(user) {
        const list = document.getElementById('sidebar-users-list');
        if (!list) {
            return;
        }
        const letter = (user.displayName || '?')[0];
        const div = document.createElement('div');
        div.id = 'sidebar-user-' + user.id;
        div.className = 'sidebar-item';
        div.innerHTML = `
        <div class="sidebar-user-avatar">${escHtml(letter)}</div>
        <span class="sidebar-item-name">${escHtml(user.displayName)}</span>
        <button class="sidebar-icon-btn sidebar-icon-btn-danger" title="Удалить"
                onclick="sidebarDeleteUser('${user.id}', '${escHtml(user.displayName)}')">×</button>`;
        list.appendChild(div);
    }

    // ── Default applyFilters fallback (for non-report pages) ─────────────────

    if (typeof window.applyFilters === 'undefined') {
        window.applyFilters = function () {
            window.location.reload();
        };
    }

    // ── Init: restore active sync banners ─────────────────────────────────────

    document.addEventListener('DOMContentLoaded', () => {
        const activeJobIds = window.ACTIVE_JOB_IDS || [];
        const enrichmentJobId = window.ENRICHMENT_JOB_ID || null;
        const releaseJobIds = window.RELEASE_JOB_IDS || [];
        const jiraJobIds = window.JIRA_JOB_IDS || [];
        const releaseIdSet = new Set(releaseJobIds);
        const jiraIdSet = new Set(jiraJobIds);
        activeJobIds
            .filter(jobId => jobId !== enrichmentJobId && !releaseIdSet.has(jobId) && !jiraIdSet.has(jobId))
            .forEach(jobId => restoreSyncCard(jobId));
        if (enrichmentJobId) {
            restoreEnrichCard(enrichmentJobId);
        }
        releaseJobIds.forEach(jobId => restoreReleaseCard(jobId));
        jiraJobIds.forEach(jobId => restoreJiraCard(jobId));
    });

})();
