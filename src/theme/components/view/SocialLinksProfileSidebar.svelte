{#if ownProfile && links.length > 0}
  <div class="card mb-3 animate__animated animate__fadeIn">
    <div class="card-header d-flex align-items-center gap-2">
      <i class="fas fa-link text-secondary"></i>
      <span>{$_('profile-sidebar.title')}</span>
    </div>
    <div class="card-body">
      <div class="d-flex flex-wrap gap-2">
        {#each links as link (link.provider)}
          {@const meta = PROVIDER_META[link.provider]}
          <span
            class="badge d-inline-flex align-items-center gap-1"
            style="background-color: {meta?.color || '#555'};">
            <ProviderIcon {meta} />
            {meta?.name || link.provider}
          </span>
        {/each}
      </div>
    </div>
  </div>
{/if}

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  export async function load(event) {
    try {
      const username = event.params?.username;
      const { session } = await event.parent();
      const sessionUser = session?.user;

      // Linked accounts are private — only render them on the user's own profile.
      if (!username || !sessionUser || sessionUser.username !== username) {
        return { ownProfile: false, links: [] };
      }

      const res = await ApiUtil.get({ path: '/api/social-login/links', request: event });
      return {
        ownProfile: true,
        links: res && !res.error && Array.isArray(res.links) ? res.links : [],
      };
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load profile links', e);
      return { ownProfile: false, links: [] };
    }
  }
</script>

<script>
  import { _, PROVIDER_META } from '../../../main.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';

  let { data } = $props();

  let ownProfile = $derived(!!data?.ownProfile);
  let links = $derived(data?.links || []);
</script>
