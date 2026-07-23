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
          <a href="/login" class="btn btn-secondary">{$_('callback.back-to-login')}</a>
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
      {#each $contentItems as item (item.id)}
        {#if item.id !== 'login-form' && item.component}
          <ViewComponent component={item.component} data={{ pageType: 'login' }} />
        {/if}
      {/each}
    </div>
  </div>
{/if}

<script>
  import { _, applyLoginSession, resolveSocialError } from '../../main.js';
  import { getContext, onMount, tick } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';
  import pano from '@panomc/sdk';

  const isBrowser = typeof window !== 'undefined';

  let session;
  let pageState = $state('loading');
  let errorMessage = $state('');

  try {
    session = getContext('session');
  } catch (e) {
    // SSR — session stays undefined.
  }

  // Subscribe to the theme's login-content slot. Items are populated by `pano.ui.auth.login.load()`
  // in onMount; the reactive store causes them to render once available.
  const contentItems = pano.ui.auth.login.content.get();

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
    try {
      await pano.ui.auth.login.load();
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load login content', e);
    }
    // Let Svelte render the newly-resolved slot items so their onMount runs.
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
      await applyLoginSession(session, body.csrfToken);
      setTimeout(() => goto('/'), 1200);
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
