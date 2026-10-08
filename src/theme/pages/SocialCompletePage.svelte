<style>
  .complete-container {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .complete-card {
    border-radius: 1rem;
    box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
    overflow: hidden;
  }

  .complete-card .card-body {
    padding: 2rem;
  }

  .status-icon {
    font-size: 3rem;
    margin-bottom: 1rem;
  }

  .status-icon.success {
    color: var(--bs-success);
  }

  .status-icon.error {
    color: var(--bs-danger);
  }

  .status-icon.loading {
    color: var(--bs-primary);
  }

  /* The slot mount is needed for plugin widgets (e.g. auth-guard's TwoFactorLoginGuard) to
     register their global fetch interceptor — but we don't want their inline UI in our card. */
  .slot-mount {
    display: none;
  }
</style>

{#if !isBrowser}
  <div class="complete-container">
    <div class="card complete-card">
      <div class="card-body text-center">
        <div class="status-icon loading">
          <div class="spinner-border" role="status"></div>
        </div>
      </div>
    </div>
  </div>
{:else}
  <div class="complete-container animate__animated animate__fadeIn">
    <div class="card complete-card">
      <div class="card-body text-center">
        {#if pageState === 'loading'}
          <div class="status-icon loading">
            <div class="spinner-border" role="status"></div>
          </div>
          <h5>{$_('complete.loading')}</h5>
          <p class="text-muted small mb-0">{$_('complete.subtitle')}</p>
        {:else if pageState === 'success'}
          <div class="status-icon success animate__animated animate__bounceIn">
            <i class="fas fa-check-circle"></i>
          </div>
          <h5 class="text-success">{$_('callback.login-success')}</h5>
          <p class="text-muted">{$_('callback.redirecting')}</p>
        {:else}
          <div class="status-icon error animate__animated animate__shakeX">
            <i class="fas fa-times-circle"></i>
          </div>
          <h5 class="text-danger">{$_('callback.error')}</h5>
          <p class="text-muted">{errorMessage}</p>
          <a href={sitePath('/login')} class="btn btn-secondary">{$_('callback.back-to-login')}</a>
        {/if}
      </div>
    </div>

    <!--
      Mount the active theme's login-content slot items so plugin widgets (e.g. auth-guard's
      TwoFactorLoginGuard / CaptchaWidget) install their fetch interceptors and can pop their
      challenge UI when /api/auth/complete-pending responds. Visually hidden — the modal-style
      widgets render their UI as overlays; inline widgets (captcha) aren't relevant here since the
      core completion endpoint doesn't run captcha hooks.
    -->
    <div class="slot-mount" aria-hidden="true">
      {#each contentItems as item (item.id)}
        {#if item.id !== 'login-form' && item.component && typeof item.component !== 'function'}
          {@const Field = item.component.default || item.component}
          <Field data={{ pageType: 'login' }} />
        {/if}
      {/each}
    </div>
  </div>
{/if}

<script>
  import { _, finishLogin, resolveSocialError, pano, sitePath } from '../../main.js';
  import { getContext, onMount, tick } from 'svelte';

  const isBrowser = typeof window !== 'undefined';

  let session;
  let pageState = $state('loading');
  let errorMessage = $state('');

  try {
    session = getContext('session');
  } catch (e) {
    // SSR — session stays undefined.
  }

  // Plugin-contributed login-content items (e.g. auth-guard's TwoFactorLoginGuard, captcha widget).
  // Populated by `pano.ui.auth.login.load()` in onMount; the explicit subscribe mirrors the pattern
  // used by SocialLinkExistsPage.
  let contentItems = $state([]);
  let contentUnsub = null;

  // Cleanup via $effect (client-only by design) rather than top-level `onDestroy` — see
  // SocialLinkExistsPage for context: SSR throws on this page's compiled onDestroy output.
  $effect(() => {
    return () => {
      if (contentUnsub) contentUnsub();
    };
  });

  onMount(async () => {
    const url = new URL(window.location.href);
    const token = url.searchParams.get('token') || '';

    if (!token) {
      pageState = 'error';
      errorMessage = $_('complete.invalid-token');
      return;
    }

    // Populate the login-content slot so its widgets (auth-guard's TwoFactorLoginGuard, etc.)
    // mount and register their fetch interceptors BEFORE we fire the completion request.
    if (pano?.ui?.auth?.login?.load) {
      try {
        await pano.ui.auth.login.load();
        contentUnsub = pano.ui.auth.login.content.get().subscribe((items) => {
          contentItems = items || [];
        });
      } catch (e) {
        console.error('[pano-plugin-social-login] Failed to load login content', e);
      }
    }
    // Let Svelte render the newly-resolved slot items so their onMount runs (and the
    // TwoFactorLoginGuard's fetch monkey-patch is active before we fire the POST).
    await tick();

    try {
      const res = await fetch('/api/auth/complete-pending', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pendingToken: token }),
      });
      const body = await res.json();

      if (body.result !== 'success' && body.result !== 'ok') {
        // PLUGIN_DENIED_LOGIN (e.g. 2FA cancelled) and other failures land here. Token-side errors
        // (expired/missing) come back as INVALID_TOKEN. Generic surface — challenge UIs would've
        // intercepted the response themselves before we got here on success.
        pageState = 'error';
        errorMessage = resolveError(body);
        return;
      }

      pageState = 'success';
      if ((await finishLogin(session, body.csrfToken)) === 'NO_PANEL_ACCESS') {
        pageState = 'error';
        errorMessage = resolveSocialError($_, 'NO_PANEL_ACCESS');
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Complete-pending error:', e);
      pageState = 'error';
      errorMessage = $_('error.connection');
    }
  });

  function resolveError(body) {
    if (body.error === 'PLUGIN_DENIED_LOGIN' && body.reason) {
      // Translate plugin-i18n reason key if available; otherwise show the raw reason.
      try {
        return $_(body.reason);
      } catch (e) {
        return body.reason;
      }
    }
    if (body.error === 'INVALID_TOKEN') {
      return $_('complete.invalid-token');
    }
    return resolveSocialError($_, body.error);
  }
</script>
