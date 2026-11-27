(() => {
  'use strict';
  const auth = window.mdsIdentity;
  const entry = '/data-space/index.html';
  const cover = '/data-space/login.html';
  const pages = {
    dashboard: operations => ['data.catalog.read', 'data.view.export', 'dataset.upload',
      'dataset.create', 'dataset.index.build', 'platform.runtime.manage'].some(code => operations.has(code)),
    provider: operations => [...operations].some(code => code.startsWith('dataset.')),
    market: operations => operations.has('data.catalog.read') || operations.has('application.submit'),
    retrieval: operations => operations.has('data.search'),
    qa: operations => operations.has('data.qa'),
    contracts: operations => [...operations].some(code => /^(application|contract|delivery)\./.test(code)),
  };
  const notices = {
    unavailable: '暂时无法验证身份，请重试。',
    expired: '会话已失效，请重新登录。',
    switched: '此浏览器中的账号已切换，请重新登录。',
    logoutUnconfirmed: '本机已退出。服务器撤销会话未能确认，请在连接恢复后重新登录并退出。',
    passwordChanged: '密码已更新，请使用新密码登录。',
    accountClosed: '账号已注销，业务记录保留。',
  };
  const params = new URLSearchParams(location.search);
  const byId = id => document.getElementById(id);
  function allowedRoute(route) {
    return Object.hasOwn(pages, route) || route === 'identity' || route === 'identityManagement';
  }

  function targetRoute() {
    const entries = params.getAll('returnTo');
    const value = entries.length === 1 ? entries[0] : '';
    const match = /^(?:\/|\/index\.html|\/data-space\/index\.html)#([A-Za-z]+)$/.exec(value);
    return match && allowedRoute(match[1]) ? match[1] : '';
  }

  function goToCover(notice = '') {
    const query = new URLSearchParams();
    const route = location.hash.slice(1);
    if (allowedRoute(route)) query.set('returnTo', entry + '#' + route);
    if (notice) query.set('notice', notice);
    const search = query.toString();
    location.replace(cover + (search ? '?' + search : ''));
  }

  function signIn() {
    const form = byId('signInForm');
    const button = form.querySelector('button[type=submit]');
    const status = byId('signInStatus');
    const retry = byId('signInRetry');
    const dialog = byId('identityActivationDialog');
    const activationForm = byId('identityActivationForm');
    const activationButton = activationForm.querySelector('button[type=submit]');
    const activationStatus = byId('identityActivationStatus');
    const note = params.get('notice');
    let busy = false;
    const proceed = () => {
      const route = targetRoute();
      location.replace(entry + (route ? '#' + route : ''));
    };
    const showForm = () => { form.hidden = false; form.inert = false; };
    const showStatus = message => { status.textContent = message; };
    window.addEventListener('dataspace-identity', ({detail}) => {
      if (detail.reason === 'page-hidden') {
        form.elements.password.value = '';
        activationForm.reset();
        if (dialog.open) dialog.close();
        return;
      }
      if (detail.activationRequired) {
        byId('identityActivationUser').textContent = '待激活账号：' + detail.activationUsername;
        if (!dialog.open) dialog.showModal();
        return;
      }
      if (dialog.open) dialog.close();
      if (detail.authenticated && !busy) { proceed(); return; }
      if (detail.reason === 'login-start') return;
      retry.hidden = detail.reason !== 'authority-unavailable';
      if (!detail.authenticated) {
        showForm();
        if (detail.reason === 'authority-unavailable') showStatus(notices.unavailable);
        else if (detail.reason === 'session-invalid') showStatus(notices.expired);
        else if (detail.reason === 'another-tab-changed-identity') showStatus(notices.switched);
        else if (detail.reason === 'signed-out' || detail.reason === 'backend-changed') {
          showStatus(Object.hasOwn(notices, note) ? notices[note] : '');
          retry.hidden = note !== 'unavailable';
        }
      }
    });
    retry.onclick = async () => {
      if (busy) return;
      retry.disabled = true;
      showStatus('正在重新验证身份…');
      try { await auth.check({force: true}); }
      finally { retry.disabled = false; }
    };
    form.onsubmit = async event => {
      event.preventDefault();
      if (busy) return;
      busy = true;
      button.disabled = true;
      retry.hidden = true;
      showStatus('正在登录…');
      try {
        const result = await auth.login(Object.fromEntries(new FormData(form)));
        if (result.activationRequired) showStatus('请设置新密码完成账号激活。');
        else proceed();
      } catch (error) {
        if (error.name !== 'AbortError') {
          showStatus(error.name === 'TimeoutError' || error instanceof TypeError ? notices.unavailable : error.message);
        }
      } finally {
        form.elements.password.value = '';
        busy = false;
        button.disabled = false;
      }
    };
    activationForm.onsubmit = async event => {
      event.preventDefault();
      if (busy) return;
      const {newPassword, confirmPassword} = activationForm.elements;
      if (newPassword.value !== confirmPassword.value) {
        activationStatus.textContent = '两次输入的新密码不一致。';
        return;
      }
      busy = true;
      activationButton.disabled = true;
      activationStatus.textContent = '正在激活…';
      try {
        form.elements.userName.value = await auth.activate(newPassword.value);
        showStatus('激活完成，请使用新密码登录。');
        form.elements.password.focus();
      } catch (error) {
        if (error.name !== 'AbortError') activationStatus.textContent = error.message;
      } finally {
        busy = false;
        activationButton.disabled = false;
        activationForm.reset();
      }
    };
    function cancelActivation() {
      auth.cancelActivation();
      activationForm.reset();
      activationStatus.textContent = '';
      showStatus('已取消激活，请重新登录。');
    }
    byId('identityCancelActivation').onclick = cancelActivation;
    dialog.addEventListener('cancel', event => { event.preventDefault(); cancelActivation(); });
  }

  function protectBusiness() {
    const shell = byId('businessShell');
    const waiting = byId('sessionWaiting');
    const waitingText = byId('sessionWaitingText');
    let started = false, leaving = false;

    function hide(message = '正在验证会话…') {
      shell.hidden = true;
      shell.inert = true;
      for (const dialog of shell.querySelectorAll('dialog[open]')) dialog.close();
      waitingText.textContent = message;
      waiting.hidden = false;
    }

    function renderRoute(identity) {
      const operations = new Set(identity.operations);
      const businessPages = Object.keys(pages).filter(page => pages[page](operations));
      const allowed = new Set([...businessPages, 'identity']);
      if (identity.platformAdmin || identity.enterpriseMemberManager) allowed.add('identityManagement');
      const requested = location.hash.slice(1);
      const page = allowed.has(requested) ? requested : businessPages[0] || 'identity';
      if (page !== requested) history.replaceState(null, '', '#' + page);
      for (const panel of shell.querySelectorAll('.page')) panel.classList.toggle('show', panel.id === page);
      for (const link of shell.querySelectorAll('nav a')) {
        const route = link.hash.slice(1);
        link.hidden = !allowed.has(route);
        link.classList.toggle('active', route === page);
        if (route === page) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
      }
      byId('noBusinessPermission').hidden = businessPages.length > 0;
      byId('businessTools').hidden = !businessPages.length;
      byId('businessSync').hidden = !operations.has('data.catalog.read');
    }

    async function signOut(notice = '') {
      if (leaving) return;
      leaving = true;
      hide('正在撤销服务器会话…');
      const revoked = await auth.logout();
      goToCover(revoked ? notice : 'logoutUnconfirmed');
    }

    function endRevokedSession(notice) {
      leaving = true;
      hide();
      auth.endSession();
      goToCover(notice);
    }

    hide();
    window.mdsNavigation = {signOut, endRevokedSession};
    byId('businessLogout').onclick = () => void signOut();
    window.addEventListener('hashchange', () => { if (auth.identity && !leaving) renderRoute(auth.identity); });
    window.addEventListener('dataspace-identity', ({detail}) => {
      if (leaving) return;
      if (!detail.authenticated) {
        hide();
        const reason = detail.reason;
        if (['page-hidden', 'permissions-changed'].includes(reason)) return;
        const notice = {'authority-unavailable': 'unavailable', 'session-invalid': 'expired', 'another-tab-changed-identity': 'switched'}[reason];
        goToCover(notice);
        return;
      }
      const identity = detail.identity;
      renderRoute(identity);
      byId('businessSessionSummary').textContent = identity.username + ' · ' + mdsOrganizationName(identity);
      if (!started) {
        started = true;
        window.dispatchEvent(new Event('dataspace-business-ready'));
      }
      waiting.hidden = true;
      shell.inert = false;
      shell.hidden = false;
    });
  }

  if (byId('signIn')) signIn();
  else protectBusiness();
})();
