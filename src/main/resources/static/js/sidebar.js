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

    function buildBannerHtml(jobId, descHtml, initialLabel) {
        const label = initialLabel || 'получение списка MR...';
        return `
        <div class="sync-banner-icon-box"><span class="sync-spinner"></span></div>
        <div class="sync-main">
            <span class="sync-text">${descHtml}</span>
            <div class="sync-progress-row">
                <div class="sync-progress-bar">
                    <div class="sync-progress-fill sync-progress-fill--indeterminate" id="sync-fill-${jobId}"></div>
                </div>
                <span class="sync-progress-label" id="sync-plabel-${jobId}">${escHtml(label)}</span>
            </div>
        </div>
        <span class="sync-duration" id="sync-duration-${jobId}"></span>
    `;
    }

    function getSyncProjectDesc(phase) {
        const items = [...document.querySelectorAll('#sidebar-repos-list .sidebar-item-name')];
        const names = items.map(el => el.textContent.trim()).filter(Boolean);
        if (names.length === 0) {
            return phase === 'ENRICH'
                ? 'Дозагружаем данные — коммиты, комментарии и аппрувы'
                : 'Синхронизация — загружаем список MR за 360 дней';
        }
        const repoStr = names.length === 1
            ? `<strong>${escHtml(names[0])}</strong>`
            : `<strong>${escHtml(names[0])}</strong> и ещё ${names.length - 1}`;
        return phase === 'ENRICH'
            ? `Дозагружаем ${repoStr} — коммиты, комментарии и аппрувы`
            : `Синхронизация ${repoStr} — загружаем список MR за 360 дней`;
    }

    window.showSyncBanner = function (jobId, projectName, isRetry) {
        const banners = document.getElementById('sync-banners');
        if (!banners) {
            return;
        }
        const id = 'sync-banner-' + jobId;
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-banner sync-banner-running';
        div.dataset.descSet = '1';
        const desc = isRetry
            ? 'Повторная синхронизация — уже загруженные данные будут пропущены'
            : `Синхронизация <strong>${escHtml(projectName)}</strong> — загружаем список MR за 360 дней`;
        div.innerHTML = buildBannerHtml(jobId, desc);
        banners.prepend(div);
        startBannerTimers(jobId, id, Date.now());
    };

    function restoreSyncBanner(jobId) {
        const banners = document.getElementById('sync-banners');
        if (!banners) {
            return;
        }
        const id = 'sync-banner-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-banner sync-banner-running';
        div.innerHTML = buildBannerHtml(jobId, 'Синхронизация в процессе...');
        banners.prepend(div);
        startBannerTimers(jobId, id, null);
    }

    function restoreEnrichmentBanner(jobId) {
        // If the page has a phase2-card element, use it (same as report.html)
        if (document.getElementById('phase2-card')) {
            initPhase2Card(jobId);
            return;
        }
        const banners = document.getElementById('sync-banners');
        if (!banners) {
            return;
        }
        const id = 'sync-banner-' + jobId;
        if (document.getElementById(id)) {
            return;
        }
        const div = document.createElement('div');
        div.id = id;
        div.className = 'sync-banner sync-banner-running';
        div.dataset.descSet = '1';
        div.innerHTML = buildBannerHtml(jobId, getSyncProjectDesc('ENRICH'), 'дозагружаем детали...');
        banners.prepend(div);
        startBannerTimers(jobId, id, null);
    }

    function initPhase2Card(jobId) {
        const card = document.getElementById('phase2-card');
        if (!card) {
            return;
        }
        const commitsEl = document.getElementById('p2-commits');
        const reviewsEl = document.getElementById('p2-reviews');
        const commentsEl = document.getElementById('p2-comments');
        const pctEl = document.getElementById('p2-pct');
        const barEl = document.getElementById('p2-bar');
        let lastProcessed = 0;
        const interval = setInterval(async () => {
            try {
                const job = await api('GET', '/settings/sync/' + jobId);
                if (job.processedMrs > lastProcessed) {
                    lastProcessed = job.processedMrs;
                }
                const pct = job.totalMrs > 0 ? Math.round(lastProcessed / job.totalMrs * 100) : 0;
                if (commitsEl) {
                    commitsEl.textContent = Math.round(lastProcessed * 2.8).toLocaleString('ru');
                }
                if (reviewsEl) {
                    reviewsEl.textContent = Math.round(lastProcessed * 0.6).toLocaleString('ru');
                }
                if (commentsEl) {
                    commentsEl.textContent = Math.round(lastProcessed * 1.4).toLocaleString('ru');
                }
                if (pctEl) {
                    pctEl.textContent = pct;
                }
                if (barEl) {
                    barEl.style.width = pct + '%';
                }
                if (job.status === 'COMPLETED') {
                    clearInterval(interval);
                    if (pctEl) {
                        pctEl.textContent = '100';
                    }
                    if (barEl) {
                        barEl.style.width = '100%';
                    }
                    setTimeout(() => {
                        if (card.parentNode) {
                            card.remove();
                        }
                    }, 3000);
                } else if (job.status === 'FAILED') {
                    clearInterval(interval);
                    if (card.parentNode) {
                        card.remove();
                    }
                }
            } catch (e) {
                // продолжаем поллинг при сетевой ошибке
            }
        }, 4000);
    }

    function startBannerTimers(jobId, bannerId, localStartMs) {
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
        pollSyncJob(jobId, bannerId, durationTimer, (startedAt) => {
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

    function pollSyncJob(jobId, bannerId, durationTimer, onStartedAt) {
        const fillEl = () => document.getElementById('sync-fill-' + jobId);
        const labelEl = () => document.getElementById('sync-plabel-' + jobId);

        const interval = setInterval(async () => {
            try {
                const job = await api('GET', '/settings/sync/' + jobId);
                if (onStartedAt && job.startedAt) {
                    onStartedAt(job.startedAt);
                }
                const bannerEl = document.getElementById(bannerId);
                if (bannerEl && !bannerEl.dataset.descSet) {
                    bannerEl.dataset.descSet = '1';
                    const textEl = bannerEl.querySelector('.sync-text');
                    if (textEl) {
                        textEl.innerHTML = getSyncProjectDesc(job.phase);
                    }
                    if (job.phase === 'ENRICH') {
                        const lbl = labelEl();
                        if (lbl && lbl.textContent === 'получение списка MR...') {
                            lbl.textContent = 'дозагружаем детали...';
                        }
                    }
                }
                if (job.status === 'STARTED' && job.totalMrs > 0) {
                    const pct = Math.round(job.processedMrs / job.totalMrs * 100);
                    if (fillEl()) {
                        fillEl().classList.remove('sync-progress-fill--indeterminate');
                        fillEl().style.width = pct + '%';
                    }
                    const serverStartMs = new Date(job.startedAt).getTime();
                    const elapsedSec = Math.max(1, (Date.now() - serverStartMs) / 1000);
                    let label = `${job.processedMrs} / ${job.totalMrs} MR (${pct}%)`;
                    if (job.processedMrs > 0 && job.processedMrs < job.totalMrs) {
                        const rate = job.processedMrs / elapsedSec;
                        const etaSec = Math.round((job.totalMrs - job.processedMrs) / rate);
                        label += ' • ETA ' + formatEta(etaSec);
                    }
                    if (labelEl()) {
                        labelEl().textContent = label;
                    }
                }
                if (job.status === 'COMPLETED') {
                    clearInterval(interval);
                    clearInterval(durationTimer);
                    if (fillEl()) {
                        fillEl().classList.remove('sync-progress-fill--indeterminate');
                        fillEl().style.width = '100%';
                    }
                    if (labelEl()) {
                        labelEl().textContent = job.totalMrs > 0 ? job.totalMrs + ' MR' : '';
                    }
                    updateBanner(bannerId, 'ok', 'Синхронизация завершена');
                } else if (job.status === 'FAILED') {
                    clearInterval(interval);
                    clearInterval(durationTimer);
                    updateBanner(bannerId, 'error', 'Ошибка синхронизации: ' + (job.errorMessage || ''));
                }
            } catch (e) {
                // продолжаем поллинг при сетевой ошибке
            }
        }, 3000);
    }

    function updateBanner(bannerId, type, message) {
        const el = document.getElementById(bannerId);
        if (!el) {
            return;
        }
        el.className = 'sync-banner sync-banner-' + (type === 'ok' ? 'done' : 'error');
        el.innerHTML = `<span>${type === 'ok' ? '✓' : '✕'}</span> ${escHtml(message)}`;
        if (type === 'ok' && !reloadScheduled) {
            reloadScheduled = true;
            setTimeout(() => window.location.reload(), 3000);
        } else if (type === 'ok') {
            setTimeout(() => el.remove(), 5000);
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
        activeJobIds
            .filter(jobId => jobId !== enrichmentJobId)
            .forEach(jobId => restoreSyncBanner(jobId));
        if (enrichmentJobId) {
            restoreEnrichmentBanner(enrichmentJobId);
        }
    });

})();
