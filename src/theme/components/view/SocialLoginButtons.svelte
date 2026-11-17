<style>
  .social-login-social-login-buttons__list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .social-login-social-login-buttons__button {
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

  .social-login-social-login-buttons__button:hover:not(:disabled) {
    transform: translateY(-1px);
    filter: brightness(1.08);
    color: #fff;
  }

  .social-login-social-login-buttons__button:active:not(:disabled) {
    transform: translateY(0);
  }

  .social-login-social-login-buttons__button:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }

  .social-login-social-login-buttons__error {
    animation: social-login-shake-x 1s both;
  }

  @keyframes social-login-shake-x {
    from,
    to {
      transform: translate3d(0, 0, 0);
    }
    10%,
    30%,
    50%,
    70%,
    90% {
      transform: translate3d(-10px, 0, 0);
    }
    20%,
    40%,
    60%,
    80% {
      transform: translate3d(10px, 0, 0);
    }
  }
</style>

{#if $enabledProviders.length > 0}
  <div class="social-login-social-login-buttons">
    <div class="social-login-social-login-buttons__list">
      {#each $enabledProviders as key (key)}
        {@const meta = PROVIDER_META[key]}
        <button
          class="social-login-social-login-buttons__button btn"
          type="button"
          style="background-color: {meta?.color || '#555'};"
          disabled={loadingProvider !== null}
          onclick={() => startAuth(key)}>
          {#if loadingProvider === key}
            <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
          {:else}
            <ProviderIcon {meta} />
          {/if}
          <span>{$_('continue-with', { values: { provider: meta?.name || key } })}</span>
        </button>
      {/each}
    </div>
    {#if errorMessage}
      <div class="social-login-social-login-buttons__error alert alert-danger py-2 mt-2 small">{errorMessage}</div>
    {/if}
  </div>
{/if}

<script>
  import { derived, get } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../../lib/text.js';
  import { PROVIDER_META } from '../../lib/providers.js';
  import { enabledProviders } from '../../lib/enabledProviders.js';
  import { apiBody, resolveSocialError } from '../../lib/errors.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { onMount } from 'svelte';

  const _ = pluginText(derived, i18n);

  let loadingProvider = $state(null);
  let errorMessage = $state('');

  // The enabled-provider list is populated during SSR by the plugin's login/register
  // onLoad hook (a module-level store). On the client the module re-initialises to [],
  // so the SSR-rendered buttons would otherwise disappear after hydration. Re-fetch the
  // public config on mount (client only) to repopulate the store so the buttons persist.
  onMount(async () => {
    if (get(enabledProviders).length > 0) return;
    try {
      const res = apiBody(await api.get({ path: '/config' }));
      if (!res.error && Array.isArray(res.items)) {
        enabledProviders.set(res.items);
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load social config (client)', e);
    }
  });

  async function startAuth(provider) {
    loadingProvider = provider;
    errorMessage = '';
    try {
      const body = apiBody(await api.get({ path: `/providers/${provider}/auth` }));

      if (!body.error && body.redirectUrl) {
        window.location.href = body.redirectUrl;
        return;
      }

      errorMessage = resolveSocialError($_, body.error?.code);
    } catch (e) {
      console.error('[pano-plugin-social-login] Auth init failed', e);
      errorMessage = $_('error.generic');
    }
    loadingProvider = null;
  }
</script>
