<style>
  .social-2fa-container {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .social-2fa-card {
    border-radius: 1rem;
    box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
    overflow: hidden;
  }

  .social-2fa-card .card-body {
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

  .totp-input {
    letter-spacing: 0.4em;
    text-align: center;
    font-size: 1.4rem;
  }
</style>

<div class="social-2fa-container animate__animated animate__fadeIn">
  <div class="card social-2fa-card">
    <div class="card-body">
      <h5 class="card-title text-center">{$_('two-factor.title')}</h5>

      {#if providerMeta}
        <div class="text-center mb-3">
          <span class="provider-badge" style="background-color: {providerMeta.color};">
            <ProviderIcon meta={providerMeta} />
            {providerMeta.name}
          </span>
        </div>
      {/if}

      <p class="text-muted text-center small">{$_('two-factor.description')}</p>

      {#if error}
        <div class="alert alert-danger py-2" role="alert">{error}</div>
      {/if}

      {#if success}
        <div class="alert alert-success py-2" role="alert">{$_('two-factor.success')}</div>
      {/if}

      <form on:submit|preventDefault={onSubmit}>
        <div class="form-floating mb-3">
          <input
            type="text"
            id="socialTotpCode"
            class="form-control totp-input"
            inputmode="numeric"
            autocomplete="one-time-code"
            placeholder="000000"
            disabled={loading || success || !token}
            bind:value={totpCode} />
          <label for="socialTotpCode">{$_('two-factor.code-label')}</label>
        </div>
        <button
          type="submit"
          class="btn btn-lg btn-secondary w-100"
          class:disabled={loading || success || !canSubmit}
          disabled={loading || success || !canSubmit}>
          {#if loading}
            <span class="spinner-border spinner-border-sm me-2" role="status" aria-label="Loading"></span>
            <span>{$_('two-factor.submitting')}</span>
          {:else}
            <span>{$_('two-factor.submit')}</span>
          {/if}
        </button>
      </form>

      <div class="text-center mt-3">
        <a class="btn btn-link {loading ? 'disabled pe-none' : ''}" href="/login">
          {$_('two-factor.back-to-login')}
        </a>
      </div>
    </div>
  </div>
</div>

<script>
  import { _, applyLoginSession, PROVIDER_META, resolveSocialError } from '../../main.js';
  import ProviderIcon from '../../components/ProviderIcon.svelte';
  import { getContext, onMount } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';

  let session;
  try {
    session = getContext('session');
  } catch (e) {
    // SSR or no context — session stays undefined.
  }

  let token = $state(null);
  let provider = $state('');
  let totpCode = $state('');
  let loading = $state(false);
  let error = $state(null);
  let success = $state(false);

  let providerMeta = $derived(provider ? PROVIDER_META[provider] : null);
  let canSubmit = $derived(token && totpCode.trim().length >= 6);

  onMount(() => {
    const url = new URL(window.location.href);
    token = url.searchParams.get('token');
    provider = url.searchParams.get('provider') || '';

    if (!token) {
      error = $_('error.INVALID_FLOW_STATE');
    }
  });

  async function onSubmit() {
    if (!token || loading || !canSubmit) return;
    loading = true;
    error = null;

    try {
      const res = await fetch('/api/social-login/2fa', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pendingToken: token, totpCode: totpCode.trim() }),
      });
      const body = await res.json();

      if (body.action === 'login') {
        success = true;
        await applyLoginSession(session, body.csrfToken);
        setTimeout(() => goto('/'), 1000);
        return;
      }

      error = resolveSocialError($_, body.error);
    } catch (e) {
      console.error('[pano-plugin-social-login] 2FA verify failed', e);
      error = $_('error.connection');
    }
    loading = false;
  }
</script>
