<style>
  .social-login-social-account-links {
    animation: social-login-fade-in 1s both;
  }

  @keyframes social-login-fade-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }
</style>

<div class="social-login-social-account-links row pt-2 pb-3 mb-3 border-bottom">
  <div class="col-md-4 col-form-label pb-2">
    <div class="d-flex align-items-center gap-2 mb-2">
      <i class="fas fa-link text-secondary"></i>
      <span class="fw-bold">{$_('account-links.title')}</span>
    </div>
    <p class="text-secondary small mt-1 mb-0">{$_('account-links.desc')}</p>
  </div>

  <div class="col col-form-label">
    {#if enabledProviders.length === 0}
      <p class="text-muted small mb-0">{$_('account-links.none-enabled')}</p>
    {:else}
      <div class="vstack gap-2">
        {#each enabledProviders as key (key)}
          {@const meta = PROVIDER_META[key]}
          {@const link = linksByProvider[key]}
          <div class="d-flex align-items-center gap-2">
            <ProviderIcon {meta} colored />
            <span class="fw-semibold">{meta?.name || key}</span>
            {#if link}
              <span class="text-success small">
                <i class="fas fa-check-circle me-1"></i>{$_('account-links.linked')}
              </span>
              {#if link.displayName}
                <span class="text-muted small text-truncate">{link.displayName}</span>
              {/if}
              <button
                class="social-login-social-account-links__action btn btn-link btn-sm p-0 ms-auto text-danger"
                type="button"
                disabled={busyProvider === key}
                onclick={() => unlink(key)}>
                {#if busyProvider === key}
                  <span class="spinner-border spinner-border-sm me-1" role="status"></span>
                {/if}
                {$_('account-links.unlink')}
              </button>
            {:else}
              <button
                class="social-login-social-account-links__link btn btn-link btn-sm p-0 ms-auto"
                type="button"
                disabled={busyProvider === key}
                onclick={() => startLink(key)}>
                {#if busyProvider === key}
                  <span class="spinner-border spinner-border-sm me-1" role="status"></span>
                {/if}
                {$_('account-links.link')}
              </button>
            {/if}
          </div>
        {/each}
      </div>
      {#if errorMessage}
        <div class="social-login-social-account-links__error alert alert-danger py-2 mt-2 small">{errorMessage}</div>
      {/if}
    {/if}
  </div>
</div>

<script module>
  import { api } from '@panomc/sdk/plugin-api';

  export async function load(event) {
    try {
      const {
        session: { user },
      } = await event.parent();
      if (!user) return { links: [], enabledProviders: [] };

      const res = await api.get({ path: '/links', request: event });
      if (res && !res.error) {
        return {
          links: Array.isArray(res.items) ? res.items : [],
          enabledProviders: Array.isArray(res.enabledProviders) ? res.enabledProviders : [],
        };
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load account links', e);
    }
    return { links: [], enabledProviders: [] };
  }
</script>

<script>
  // api is declared in the module script above and is in scope here too.
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../../lib/text.js';
  import { PROVIDER_META } from '../../lib/providers.js';
  import { resolveSocialError } from '../../lib/errors.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';

  const _ = pluginText(derived, i18n);

  let { data } = $props();

  let enabledProviders = $derived(data?.enabledProviders || []);
  let linksByProvider = $derived(
    (data?.links || []).reduce((acc, link) => {
      acc[link.provider] = link;
      return acc;
    }, {}),
  );

  let busyProvider = $state(null);
  let errorMessage = $state('');

  async function startLink(provider) {
    busyProvider = provider;
    errorMessage = '';
    try {
      const res = await api.get({ path: `/providers/${provider}/link` });
      if (res?.redirectUrl) {
        window.location.href = res.redirectUrl;
        return;
      }
      errorMessage = resolveSocialError($_, res?.error?.code);
    } catch (e) {
      console.error('[pano-plugin-social-login] Link init failed', e);
      errorMessage = $_('error.generic');
    }
    busyProvider = null;
  }

  async function unlink(provider) {
    busyProvider = provider;
    errorMessage = '';
    try {
      const res = await api.post({
        path: '/unlink',
        body: { provider },
      });
      if (!res?.error) {
        window.location.reload();
        return;
      }
      errorMessage = resolveSocialError($_, res.error.code);
    } catch (e) {
      console.error('[pano-plugin-social-login] Unlink failed', e);
      errorMessage = $_('error.generic');
    }
    busyProvider = null;
  }
</script>
