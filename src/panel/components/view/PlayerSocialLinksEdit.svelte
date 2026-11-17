<div class="pt-2 pb-3 mb-3 border-bottom animate__animated animate__fadeIn">
  <div class="d-flex align-items-center gap-2 mb-1">
    <i class="fas fa-link text-secondary"></i>
    <span class="fw-bold">{$_('panel-edit.title')}</span>
  </div>
  <p class="text-secondary small mb-3">{$_('panel-edit.desc')}</p>

  {#if loading}
    <div class="d-flex justify-content-center py-2">
      <div class="spinner-border spinner-border-sm text-secondary" role="status"></div>
    </div>
  {:else if links.length === 0}
    <p class="text-muted small mb-0">{$_('panel-edit.none')}</p>
  {:else}
    <div class="vstack gap-2">
      {#each links as link (link.provider)}
        {@const meta = PROVIDER_META[link.provider]}
        {@const willUnlink = pendingUnlinks.includes(link.provider)}
        <div class="d-flex align-items-center gap-2">
          <ProviderIcon {meta} colored />
          <span class="fw-semibold">{meta?.name || link.provider}</span>
          {#if link.displayName}
            <span class="text-muted small text-truncate">{link.displayName}</span>
          {/if}
          <span class="ms-auto"></span>
          {#if willUnlink}
            <span class="badge bg-warning text-dark">
              <i class="fas fa-exclamation-triangle me-1"></i>{$_('panel-edit.will-unlink')}
            </span>
            <button
              class="btn btn-outline-secondary btn-sm"
              type="button"
              on:click={() => togglePending(link.provider)}>
              <i class="fas fa-undo me-1"></i>{$_('panel-edit.undo')}
            </button>
          {:else}
            <button
              class="btn btn-outline-danger btn-sm"
              type="button"
              on:click={() => togglePending(link.provider)}>
              <i class="fas fa-unlink me-1"></i>{$_('panel-edit.unlink-button')}
            </button>
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</div>

<script>
  import { _, PROVIDER_META } from '../../../main.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { showErrorToast } from '../../toast.util.js';
  import { socialLinksChanged } from '../../linksChanged.js';
  import { PLUGIN_ID } from '../../../theme/lib/text.js';

  export let playerData;
  export let onHookRegister;

  let loading = true;
  let links = [];
  let pendingUnlinks = [];
  let lastLoadedUsername = null;

  $: if (playerData?.username && playerData.username !== lastLoadedUsername) {
    lastLoadedUsername = playerData.username;
    pendingUnlinks = [];
    loadData();
  }

  async function loadData() {
    if (!playerData?.username) return;
    loading = true;
    try {
      const res = await api.panel.get({
        path: `/player/${encodeURIComponent(playerData.username)}/links`,
      });
      if (res && !res.error) {
        links = Array.isArray(res.items) ? res.items : [];
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load links', e);
    } finally {
      loading = false;
    }
  }

  function togglePending(provider) {
    if (pendingUnlinks.includes(provider)) {
      pendingUnlinks = pendingUnlinks.filter((p) => p !== provider);
    } else {
      pendingUnlinks = [...pendingUnlinks, provider];
    }
  }

  $: isDirty = pendingUnlinks.length > 0;

  $: if (onHookRegister) {
    onHookRegister({
      _id: 'pano-plugin-social-login-edit',
      isDirty: isDirty,
      save: save,
    });
  }

  async function save() {
    if (!isDirty) return;
    loading = true;
    const unlinked = [];
    const failed = [];
    try {
      for (const provider of pendingUnlinks) {
        const res = await api.panel.post({
          path: `/player/${encodeURIComponent(playerData.username)}/unlink`,
          body: { provider },
        });
        if (res && res.error) {
          console.error('[pano-plugin-social-login] Unlink failed:', res.error.code);
          failed.push({ provider, code: res.error.code });
        } else {
          unlinked.push(provider);
        }
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Save error:', e);
      for (const provider of pendingUnlinks) {
        if (!unlinked.includes(provider) && !failed.some((f) => f.provider === provider)) {
          failed.push({ provider, code: 'NETWORK_ERROR' });
        }
      }
    } finally {
      // Drop only the links that really went; failed ones stay listed (the modal closes regardless).
      links = links.filter((l) => !unlinked.includes(l.provider));
      pendingUnlinks = [];
      loading = false;
    }

    if (unlinked.length > 0) {
      socialLinksChanged.set({ username: playerData.username, at: Date.now() });
    }

    if (failed.length > 0) {
      for (const { provider, code } of failed) {
        const name = PROVIDER_META[provider]?.name || provider;
        const key =
          code === 'SOCIAL_ACCOUNT_NOT_LINKED'
            ? 'panel-edit.error.SOCIAL_ACCOUNT_NOT_LINKED'
            : 'panel-edit.unlink-error';
        await showErrorToast(`plugins.${PLUGIN_ID}.${key}`, { provider: name });
      }
      // The edit-player modal catches and logs a thrown save; the toast above is what the admin sees.
      throw new Error(`Unlink failed for: ${failed.map((f) => f.provider).join(', ')}`);
    }
  }
</script>
