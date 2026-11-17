<style>
  .social-login-social-links-profile-sidebar {
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

{#if ownProfile && links.length > 0}
  <div class="social-login-social-links-profile-sidebar card">
    <div class="social-login-social-links-profile-sidebar__header card-header d-flex align-items-center gap-2">
      <i class="fas fa-link text-secondary"></i>
      <span>{$_('profile-sidebar.title')}</span>
    </div>
    <div class="social-login-social-links-profile-sidebar__body card-body">
      <div class="d-flex flex-wrap gap-2">
        {#each links as link (link.provider)}
          {@const meta = PROVIDER_META[link.provider]}
          <span
            class="social-login-social-links-profile-sidebar__badge badge d-inline-flex align-items-center gap-1"
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
  import { api } from '@panomc/sdk/plugin-api';
  import { apiBody } from '../../lib/errors.js';

  // A card of the `profile-content` slot. The profile page (`/profile`) is the signed-in visitor's own profile.
  export async function load(event) {
    try {
      const username = event.params?.username;
      const { session } = await event.parent();
      const sessionUser = session?.user;

      // Linked accounts are private — only render them on the user's own profile.
      if (!sessionUser || (username && sessionUser.username !== username)) {
        return { ownProfile: false, links: [] };
      }

      const res = apiBody(await api.get({ path: '/links', request: event }));
      return {
        ownProfile: true,
        links: !res.error && Array.isArray(res.items) ? res.items : [],
      };
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load profile links', e);
      return { ownProfile: false, links: [] };
    }
  }
</script>

<script>
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../../lib/text.js';
  import { PROVIDER_META } from '../../lib/providers.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';
  import { onMount } from 'svelte';

  const _ = pluginText(derived, i18n);

  let { data } = $props();

  // Some hosts hand a profile-content item only the page data and drop what load() returned (theme-core's
  // ProfileView renders `{data}`), so the links are asked for once more in the browser when `data` has none.
  // `/links` answers only for the signed-in visitor, whose own profile this page is.
  let fetched = $state(null);

  onMount(async () => {
    if (data && 'ownProfile' in data) return;
    try {
      const res = apiBody(await api.get({ path: '/links' }));
      if (!res.error) fetched = Array.isArray(res.items) ? res.items : [];
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to load profile links', e);
    }
  });

  let ownProfile = $derived(data && 'ownProfile' in data ? !!data.ownProfile : fetched !== null);
  let links = $derived((data && 'ownProfile' in data ? data.links : fetched) || []);
</script>
