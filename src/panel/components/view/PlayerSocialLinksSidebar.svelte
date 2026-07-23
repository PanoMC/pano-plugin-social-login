<script module>
  /**
   * Runs in the panel layout `load` (via `executeHookLoad`) so `request: event` carries
   * CSRF + cookies. ApiUtil is imported here separately from the instance script — Svelte 5
   * errors on identically-named imports across both scripts.
   * @param {import('@sveltejs/kit').LayoutLoadEvent} event
   */
  export async function load(event) {
    const { default: ApiUtil } = await import('@panomc/sdk/utils/api');
    const username = event.params?.username;
    if (!username || typeof username !== 'string') {
      return {};
    }
    try {
      const res = await ApiUtil.get({
        path: `/api/panel/social-login/player/${encodeURIComponent(username)}/links`,
        request: event,
      });
      if (res && !res.error) {
        return {
          socialPreload: {
            _ok: true,
            links: Array.isArray(res.links) ? res.links : [],
          },
        };
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] social sidebar load() failed', e);
    }
    return {};
  }
</script>

<div class="card mt-3 animate__animated animate__fadeIn">
  <div class="card-header d-flex align-items-center gap-2">
    <i class="fas fa-link text-secondary"></i>
    <span>{$_('sidebar.title')}</span>
  </div>
  <div class="card-body">
    {#if loading}
      <div class="d-flex justify-content-center py-2">
        <div class="spinner-border spinner-border-sm text-secondary" role="status"></div>
      </div>
    {:else if links.length === 0}
      <p class="text-muted small mb-0">{$_('sidebar.none')}</p>
    {:else}
      <div class="vstack gap-2">
        {#each links as link (link.provider)}
          {@const meta = PROVIDER_META[link.provider]}
          <div class="d-flex align-items-center gap-2">
            <i class="{meta?.icon || 'fas fa-link'}" style="color: {meta?.color || 'inherit'};"></i>
            <span class="fw-semibold">{meta?.name || link.provider}</span>
            {#if link.displayName}
              <span class="text-muted small text-truncate">{link.displayName}</span>
            {/if}
          </div>
        {/each}
      </div>
    {/if}
  </div>
</div>

<script>
  import { _, PROVIDER_META } from '../../../main.js';
  import ApiUtil from '@panomc/sdk/utils/api';

  export let playerData;

  /** @type {{ _ok: true, links: Array<{provider: string, displayName?: string}> } | undefined} */
  export let socialPreload = undefined;

  let loading = true;
  let links = [];
  let lastFetchedUsername = null;

  $: if (socialPreload?._ok) {
    links = socialPreload.links;
    loading = false;
    lastFetchedUsername = playerData?.username ?? null;
  } else if (playerData?.username && playerData.username !== lastFetchedUsername) {
    lastFetchedUsername = playerData.username;
    loadData();
  }

  async function loadData() {
    if (!playerData?.username) return;
    loading = true;
    try {
      const res = await ApiUtil.get({
        path: `/api/panel/social-login/player/${encodeURIComponent(playerData.username)}/links`,
      });
      if (res && !res.error) {
        links = Array.isArray(res.links) ? res.links : [];
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load sidebar links', e);
    } finally {
      loading = false;
    }
  }
</script>
