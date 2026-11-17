import { PanoPlugin, viewComponent } from '@panomc/sdk';
import { derived, get } from 'svelte/store';
import { _ as i18n } from '@panomc/sdk/utils/language';
import { api } from '@panomc/sdk/plugin-api';
import { PLUGIN_ID, pluginText } from './theme/lib/text.js';
import { setHost } from './theme/lib/host.js';
import { enabledProviders } from './theme/lib/enabledProviders.js';
import { PROVIDER_META } from './theme/lib/providers.js';

const pluginId = PLUGIN_ID;

// Renders plugin translations under the `plugins.<id>.` namespace.
export const _ = pluginText(derived, i18n);

export let pano;

// The panel's views read the provider table from the entry; the theme views import it from src/theme/lib.
export { PROVIDER_META };

async function loadPublicConfig(event) {
  try {
    const config = await api.get({
      path: '/config',
      request: event,
    });
    if (!config.error && Array.isArray(config.items)) {
      enabledProviders.set(config.items);
    }
  } catch (e) {
    console.error('[pano-plugin-social-login] Failed to load config', e);
  }
}

/**
 * The provider buttons as an item of a login or register form. The theme names the view (`view`), so a theme can
 * redraw it; the panel has no view registry and loads the file (decision 43: panel side as it was).
 */
function buttonsItem(pano) {
  return pano.isPanel
    ? { component: viewComponent(() => import('./theme/components/view/SocialLoginButtons.svelte')) }
    : { view: 'social-login:SocialLoginButtons' };
}

/** Provider buttons and `?socialError=` on a login form — the theme's /login or the panel's. */
function registerLoginContent(pano) {
  pano.ui.auth.login.onLoad(async (data, event) => {
    const url = new URL(event.url);
    const socialError = url.searchParams.get('socialError');
    if (socialError) {
      data.error = socialError;
    }

    await loadPublicConfig(event);

    if (get(enabledProviders).length > 0) {
      pano.ui.auth.login.alternativeMethods.add({
        id: `${pluginId}-login-buttons`,
        ...buttonsItem(pano),
        priority: 50,
      });
    }
  });
}

/**
 * The pages an OAuth round trip lands on, for the panel of a SERVERS install. In a theme the three pages are views
 * that declare their own path (`export const view` in each file), so nothing is registered here.
 */
function registerPanelSignInPages(pano, options = {}) {
  // OAuth callback handler.
  pano.ui.page.register({
    path: '/social-login/callback',
    component: viewComponent(() => import('./theme/pages/SocialCallbackPage.svelte')),
    ...options,
  });

  // "An account with this email exists" — log in to link instead of registering.
  pano.ui.page.register({
    path: '/social-login/link',
    component: viewComponent(() => import('./theme/pages/SocialLinkExistsPage.svelte')),
    ...options,
  });

  // Plugin-agnostic completion page for any pending auth step-up (2FA, future challenges, …).
  // social-login routes here whenever the platform's onBeforeLogin hooks asked to pause the
  // session; the page mounts the host's login-content slot so installed plugins (auth-guard's
  // 2FA modal, captcha widget, …) drive UI exactly as on the login page.
  pano.ui.page.register({
    path: '/social-login/complete',
    component: viewComponent(() => import('./theme/pages/SocialCompletePage.svelte')),
    ...options,
  });
}

export default class SocialLoginPlugin extends PanoPlugin {
  onLoad() {
    pano = this.pano;
    setHost(pano);

    if (pano.isPanel) {
      // Player detail — admin unlink controls inside the edit-player modal.
      pano.ui.player.editModal.cardRows.edit((items) => {
        items.push({
          id: `${pluginId}-player-social-links-edit`,
          component: viewComponent(
            () => import('./panel/components/view/PlayerSocialLinksEdit.svelte'),
          ),
          permission: `pano.plugin.${pluginId}.manage.social.links`,
          priority: 100,
        });
      });

      // Player detail — linked accounts sidebar card.
      pano.ui.hook.register({
        name: 'panel:player-detail:sidebar',
        component: viewComponent(
          () => import('./panel/components/view/PlayerSocialLinksSidebar.svelte'),
        ),
        permission: `pano.plugin.${pluginId}.manage.social.links`,
      });

      // Load config for the addon detail page before the settings component renders.
      pano.ui.addon.onLoad(async (data, event) => {
        if (data.addon.id !== pluginId) return;

        try {
          const config = await api.panel.get({
            path: '/config',
            request: event,
          });
          // On an error the settings view gets no providers and shows its load-error state.
          if (config && !config.error) {
            data.addon.config = config;
          }
        } catch (e) {
          console.error('[pano-plugin-social-login] Failed to load panel config', e);
        }
      });

      // Plugin settings on the addon detail page.
      pano.ui.hook.register({
        name: `panel:plugin-detail:content:${pluginId}`,
        component: viewComponent(
          () => import('./panel/components/view/SocialLoginSettings.svelte'),
        ),
        permission: `pano.plugin.${pluginId}.manage.social.login.settings`,
      });

      // A SERVERS install signs in on the panel itself: the provider buttons go on its login
      // page and the OAuth pages are served there, open to a visitor with no session yet.
      // Panels older than `pano.ui.auth` skip this.
      if (pano.ui.auth?.login) {
        registerLoginContent(pano);
        registerPanelSignInPages(pano, { public: true });
      }
    } else {
      // The callback, link, complete and register pages come from `export const view` in their files.

      // User settings — link/unlink controls.
      pano.ui.settings.cardRows.edit((items) => {
        items.push({
          id: `${pluginId}-account-links`,
          view: 'social-login:SocialAccountLinks',
          priority: 105,
        });
      });

      // User profile — read-only linked accounts (own profile only).
      pano.ui.profile.cardRows.edit((items) => {
        items.push({
          id: `${pluginId}-profile-links`,
          view: 'social-login:SocialLinksProfileSidebar',
          priority: 90,
        });
      });

      // Login page — provider buttons (only if at least one provider is enabled).
      registerLoginContent(pano);

      // Register page — provider buttons (only if at least one provider is enabled).
      pano.ui.auth.register.onLoad(async (data, event) => {
        const url = new URL(event.url);
        const socialError = url.searchParams.get('socialError');
        if (socialError) {
          data.error = socialError;
        }

        await loadPublicConfig(event);

        if (get(enabledProviders).length > 0) {
          pano.ui.auth.register.alternativeMethods.add({
            id: `${pluginId}-register-buttons`,
            ...buttonsItem(pano),
            priority: 50,
          });
        }
      });
    }
  }

  onContextUpdate(ctx) {}

  onUnload() {}
}
