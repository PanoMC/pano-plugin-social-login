<style>
  .callback-container {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .callback-card {
    border-radius: 1rem;
    box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
    overflow: hidden;
  }

  .callback-card .card-body {
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
</style>

{#if !isBrowser}
  <div class="callback-container">
    <div class="card callback-card">
      <div class="card-body text-center">
        <div class="status-icon loading">
          <div class="spinner-border" role="status"></div>
        </div>
      </div>
    </div>
  </div>
{:else}
  <div class="callback-container animate__animated animate__fadeIn">
    <div class="card callback-card">
      <div class="card-body text-center">
        {#if callbackState === 'loading'}
          <div class="status-icon loading">
            <div class="spinner-border" role="status"></div>
          </div>
          <h5>{$_('callback.loading')}</h5>
        {:else if callbackState === 'success'}
          <div class="status-icon success animate__animated animate__bounceIn">
            <i class="fas fa-check-circle"></i>
          </div>
          <h5 class="text-success">{$_('callback.login-success')}</h5>
          <p class="text-muted">{$_('callback.redirecting')}</p>
        {:else if callbackState === 'linked'}
          <div class="status-icon success animate__animated animate__bounceIn">
            <i class="fas fa-link"></i>
          </div>
          <h5 class="text-success">{$_('callback.link-success')}</h5>
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
  </div>
{/if}

<script>
  import { _, applyLoginSession } from '../../main.js';
  import { getContext, onMount } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';

  const isBrowser = typeof window !== 'undefined';

  let session;
  let callbackState = $state('loading');
  let errorMessage = $state('');

  try {
    session = getContext('session');
  } catch (e) {
    // SSR or no context available — session stays undefined.
  }

  onMount(async () => {
    const url = new URL(window.location.href);
    const provider = url.searchParams.get('provider') || '';
    const code = url.searchParams.get('code');
    const state = url.searchParams.get('state');
    const errorParam = url.searchParams.get('error');

    if (errorParam) {
      callbackState = 'error';
      errorMessage = url.searchParams.get('error_description') || errorParam;
      return;
    }

    if (!code || !state) {
      callbackState = 'error';
      errorMessage = $_('callback.no-code');
      return;
    }

    try {
      const res = await fetch('/api/social-login/process', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider, code, state }),
      });
      const body = await res.json();

      if (body.error) {
        callbackState = 'error';
        errorMessage = body.error;
        return;
      }

      if (body.action === 'login') {
        callbackState = 'success';
        await applyLoginSession(session, body.csrfToken);
        setTimeout(() => goto('/'), 1500);
      } else if (body.action === 'link') {
        callbackState = 'linked';
        setTimeout(() => goto('/profile/settings'), 1500);
      } else if (body.action === 'register') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider,
          username: body.suggestedUsername || '',
          email: body.email || '',
        });
        goto(`/social-login/register?${params.toString()}`);
      } else if (body.action === 'account-exists') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider,
          email: body.email || '',
        });
        goto(`/social-login/link?${params.toString()}`);
      } else {
        callbackState = 'error';
        errorMessage = $_('error.generic');
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Process error:', e);
      callbackState = 'error';
      errorMessage = $_('error.connection');
    }
  });
</script>
