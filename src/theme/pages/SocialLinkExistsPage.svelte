<style>
  .social-link-container {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .social-link-card {
    border-radius: 1rem;
    box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
    overflow: hidden;
  }

  .social-link-card .card-body {
    padding: 2rem;
  }

  .provider-badge {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.35rem 0.7rem;
    border-radius: 0.5rem;
    color: #fff;
    font-weight: 500;
    font-size: 0.9rem;
  }
</style>

<div class="social-link-container animate__animated animate__fadeIn">
  <div class="card social-link-card">
    <div class="card-body">
      <h5 class="card-title text-center">{$_('link-existing.title')}</h5>

      {#if providerMeta}
        <div class="text-center mb-3">
          <span class="provider-badge" style="background-color: {providerMeta.color};">
            <i class={providerMeta.icon}></i>
            {providerMeta.name}
          </span>
        </div>
      {/if}

      <p class="text-muted text-center small">
        {$_('link-existing.description', { values: { email: email } })}
      </p>

      {#if error}
        <div class="alert alert-danger py-2" role="alert">{error}</div>
      {/if}

      {#if success}
        <div class="alert alert-success py-2 text-center" role="alert">
          {$_('link-existing.success')}
        </div>
      {:else if !token}
        <div class="alert alert-danger py-2" role="alert">
          {$_('link-existing.error.INVALID_FLOW_STATE')}
        </div>
        <a href="/login" class="btn btn-secondary w-100 mt-2">{$_('link-existing.back-to-login')}</a>
      {:else}
        <form onsubmit={(e) => { e.preventDefault(); onSubmit(); }}>
          <div class="vstack gap-3">
            <div class="form-floating">
              <input
                type="text"
                id="linkUsernameOrEmail"
                class="form-control"
                disabled={loading}
                bind:value={usernameOrEmail} />
              <label for="linkUsernameOrEmail">{$_('link-existing.username-label')}</label>
            </div>
            <div class="form-floating">
              <input
                type="password"
                id="linkPassword"
                class="form-control"
                disabled={loading}
                bind:value={password} />
              <label for="linkPassword">{$_('link-existing.password-label')}</label>
            </div>
            <button
              type="submit"
              class="btn btn-lg btn-secondary w-100"
              class:disabled={loading || !canSubmit}
              disabled={loading || !canSubmit}>
              {#if loading}
                <span class="spinner-border spinner-border-sm me-2" role="status" aria-label="Loading"></span>
                <span>{$_('link-existing.submitting')}</span>
              {:else}
                {$_('link-existing.submit')}
              {/if}
            </button>
            <a href="/login" class="text-center small">{$_('link-existing.back-to-login')}</a>
          </div>
        </form>
      {/if}
    </div>
  </div>
</div>

<script>
  import { _, applyLoginSession, PROVIDER_META } from '../../main.js';
  import { getContext, onMount } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';

  const ERROR_KEYS = [
    'LOGIN_IS_INVALID',
    'LOGIN_USER_IS_BANNED',
    'LOGIN_EMAIL_NOT_VERIFIED',
    'EMAIL_MISMATCH',
    'INVALID_FLOW_STATE',
    'SOCIAL_ACCOUNT_ALREADY_LINKED',
  ];

  let session;
  try {
    session = getContext('session');
  } catch (e) {
    // SSR or no context — session stays undefined.
  }

  let token = $state(null);
  let provider = $state('');
  let email = $state('');
  let usernameOrEmail = $state('');
  let password = $state('');
  let loading = $state(false);
  let error = $state(null);
  let success = $state(false);

  let providerMeta = $derived(provider ? PROVIDER_META[provider] : null);
  let canSubmit = $derived(usernameOrEmail.length > 0 && password.length > 0);

  onMount(() => {
    const url = new URL(window.location.href);
    token = url.searchParams.get('token');
    provider = url.searchParams.get('provider') || '';
    email = url.searchParams.get('email') || '';

    if (!token) {
      error = $_('link-existing.error.INVALID_FLOW_STATE');
    }
  });

  function resolveError(code) {
    if (code && ERROR_KEYS.includes(code)) {
      return $_(`link-existing.error.${code}`);
    }
    return code || $_('error.generic');
  }

  async function onSubmit() {
    if (!token || loading || !canSubmit) return;
    loading = true;
    error = null;

    try {
      const res = await fetch('/api/social-login/link-existing', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pendingToken: token,
          usernameOrEmail,
          password,
        }),
      });
      const body = await res.json();

      if (body.result !== 'success' && body.result !== 'ok') {
        error = resolveError(body.error);
        loading = false;
        return;
      }

      success = true;
      await applyLoginSession(session, body.csrfToken);
      setTimeout(() => goto('/'), 1200);
    } catch (e) {
      console.error('[pano-plugin-social-login] Link-existing error:', e);
      error = $_('error.connection');
      loading = false;
    }
  }
</script>
