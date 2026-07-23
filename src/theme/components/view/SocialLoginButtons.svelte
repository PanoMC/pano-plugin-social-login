<style>
  .social-buttons {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .btn-social {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    width: 100%;
    padding: 0.65rem 1rem;
    border-radius: 0.5rem;
    font-weight: 500;
    border: none;
    color: #fff;
    transition: all 0.2s ease;
  }

  .btn-social:hover:not(:disabled) {
    transform: translateY(-1px);
    filter: brightness(1.08);
    color: #fff;
  }

  .btn-social:active:not(:disabled) {
    transform: translateY(0);
  }

  .btn-social:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
</style>

{#if $enabledProviders.length > 0}
  <div class="social-buttons">
    {#each $enabledProviders as key (key)}
      {@const meta = PROVIDER_META[key]}
      <button
        class="btn btn-social"
        type="button"
        style="background-color: {meta?.color || '#555'};"
        disabled={loadingProvider !== null}
        onclick={() => startAuth(key)}>
        {#if loadingProvider === key}
          <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
        {:else}
          <i class={meta?.icon || 'fas fa-link'}></i>
        {/if}
        <span>{$_('continue-with', { values: { provider: meta?.name || key } })}</span>
      </button>
    {/each}
  </div>
  {#if errorMessage}
    <div class="alert alert-danger py-2 mt-2 small animate__animated animate__shakeX">{errorMessage}</div>
  {/if}
{/if}

<script>
  import { _, enabledProviders, PROVIDER_META } from '../../../main.js';

  let loadingProvider = $state(null);
  let errorMessage = $state('');

  async function startAuth(provider) {
    loadingProvider = provider;
    errorMessage = '';
    try {
      const res = await fetch(`/api/social-login/${provider}/auth`);
      const body = await res.json();

      if (body.result === 'ok' && body.redirectUrl) {
        window.location.href = body.redirectUrl;
        return;
      }

      errorMessage = body.error || $_('error.generic');
    } catch (e) {
      console.error('[pano-plugin-social-login] Auth init failed', e);
      errorMessage = $_('error.generic');
    }
    loadingProvider = null;
  }
</script>
