<div class="row pt-2 pb-3 mb-3 border-bottom animate__animated animate__fadeIn">
  <label class="col-md-4 col-form-label pb-2">
    <div class="d-flex align-items-center gap-2 mb-2">
      <i class="fas fa-link text-secondary"></i>
      <span class="fw-bold">{$_('account-links.title')}</span>
    </div>
    <p class="text-secondary small mt-1 mb-0">{$_('account-links.desc')}</p>
  </label>

  <div class="col col-form-label">
    {#if enabledProviders.length === 0}
      <p class="text-muted small mb-0">{$_('account-links.none-enabled')}</p>
    {:else}
      <div class="vstack gap-2">
        {#each enabledProviders as key (key)}
          {@const meta = PROVIDER_META[key]}
          {@const link = linksByProvider[key]}
          <div class="d-flex align-items-center gap-2">
            <i class={meta?.icon || 'fas fa-link'} style="color: {meta?.color || 'inherit'};"></i>
            <span class="fw-semibold">{meta?.name || key}</span>
            {#if link}
              <span class="text-success small">
                <i class="fas fa-check-circle me-1"></i>{$_('account-links.linked')}
              </span>
              {#if link.displayName}
                <span class="text-muted small text-truncate">{link.displayName}</span>
              {/if}
              <button
                class="btn btn-link btn-sm p-0 ms-auto text-danger"
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
                class="btn btn-link btn-sm p-0 ms-auto"
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
    {/if}
  </div>
</div>

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  export async function load(event) {
    try {
      const {
        session: { user },
      } = await event.parent();
      if (!user) return { links: [], enabledProviders: [] };

      const res = await ApiUtil.get({ path: '/api/social-login/links', request: event });
      if (res && !res.error) {
        return {
          links: Array.isArray(res.links) ? res.links : [],
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
  import { _, PROVIDER_META } from '../../../main.js';
  import ApiUtil from '@panomc/sdk/utils/api';

  let { data } = $props();

  let enabledProviders = $derived(data?.enabledProviders || []);
  let linksByProvider = $derived(
    (data?.links || []).reduce((acc, link) => {
      acc[link.provider] = link;
      return acc;
    }, {}),
  );

  let busyProvider = $state(null);

  async function startLink(provider) {
    busyProvider = provider;
    try {
      const res = await ApiUtil.get({ path: `/api/social-login/${provider}/link` });
      if (res?.redirectUrl) {
        window.location.href = res.redirectUrl;
        return;
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Link init failed', e);
    }
    busyProvider = null;
  }

  async function unlink(provider) {
    busyProvider = provider;
    try {
      const res = await ApiUtil.post({
        path: '/api/social-login/unlink',
        body: { provider },
      });
      if (res?.result === 'ok') {
        window.location.reload();
        return;
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Unlink failed', e);
    }
    busyProvider = null;
  }
</script>
