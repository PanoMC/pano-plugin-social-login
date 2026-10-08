import { PanoPlugin, viewComponent } from '@panomc/sdk';
import { derived, get, writable } from 'svelte/store';
import { _ as i18n } from '@panomc/sdk/utils/language';
import ApiUtil from '@panomc/sdk/utils/api';
import { goto } from '@panomc/sdk/svelte';

const pluginId = 'pano-plugin-social-login';

// Renders plugin translations under the `plugins.<id>.` namespace.
export const _ = derived(i18n, ($_fn) => {
  return (key, options) => $_fn(`plugins.${pluginId}.${key}`, options);
});

export let pano;

// Provider keys enabled on the server — populated on the login/register pages.
export const enabledProviders = writable([]);

// Static presentation metadata for each provider (icon + brand colour), plus the OAuth
// setup pointers shown in the panel settings guide:
//   - `scopes`     : the exact scopes/permissions this plugin requests (must be granted on
//                    the provider app). Keep in sync with the *OAuthService.kt SCOPE consts.
//   - `consoleUrl` : where the admin creates the OAuth app / finds the Client ID & Secret.
//   - `docsUrl`    : official provider documentation for the OAuth app setup.
// The `twitter` key is kept for stability; the brand is X.
export const PROVIDER_META = {
  google: {
    name: 'Google',
    svg: 'M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387.307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48z',
    color: '#DB4437',
    scopes: 'openid email profile',
    consoleUrl: 'https://console.cloud.google.com/apis/credentials',
    docsUrl: 'https://developers.google.com/identity/protocols/oauth2/web-server',
  },
  discord: {
    name: 'Discord',
    svg: 'M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z',
    color: '#5865F2',
    scopes: 'identify email',
    consoleUrl: 'https://discord.com/developers/applications',
    docsUrl: 'https://discord.com/developers/docs/topics/oauth2',
  },
  facebook: {
    name: 'Facebook',
    svg: 'M9.101 23.691v-7.98H6.627v-3.667h2.474v-1.58c0-4.085 1.848-5.978 5.858-5.978.401 0 .955.042 1.468.103a8.68 8.68 0 0 1 1.141.195v3.325a8.623 8.623 0 0 0-.653-.036 26.805 26.805 0 0 0-.733-.009c-.707 0-1.259.096-1.675.309a1.686 1.686 0 0 0-.679.622c-.258.42-.374.995-.374 1.752v1.297h3.919l-.386 2.103-.287 1.564h-3.246v8.245C19.396 23.238 24 18.179 24 12.044c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.628 3.874 10.35 9.101 11.647Z',
    color: '#1877F2',
    scopes: 'email, public_profile',
    consoleUrl: 'https://developers.facebook.com/apps',
    docsUrl: 'https://developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow/',
  },
  twitter: {
    name: 'X',
    svg: 'M14.234 10.162 22.977 0h-2.072l-7.591 8.824L7.251 0H.258l9.168 13.343L.258 24H2.33l8.016-9.318L16.749 24h6.993zm-2.837 3.299-.929-1.329L3.076 1.56h3.182l5.965 8.532.929 1.329 7.754 11.09h-3.182z',
    color: '#000000',
    // Brand mark is intentionally monochrome — adapt to the background (currentColor)
    // instead of forcing near-black, which would vanish on dark surfaces.
    monochrome: true,
    scopes: 'users.read tweet.read',
    consoleUrl: 'https://developer.x.com/en/portal/dashboard',
    docsUrl: 'https://docs.x.com/fundamentals/developer-apps',
  },
  github: {
    name: 'GitHub',
    svg: 'M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12',
    color: '#181717',
    // Brand mark is intentionally monochrome — adapt to the background (currentColor).
    monochrome: true,
    scopes: 'read:user user:email',
    consoleUrl: 'https://github.com/settings/developers',
    docsUrl: 'https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app',
  },
  twitch: {
    name: 'Twitch',
    svg: 'M11.571 4.714h1.715v5.143H11.57zm4.715 0H18v5.143h-1.714zM6 0L1.714 4.286v15.428h5.143V24l4.286-4.286h3.428L22.286 12V0zm14.571 11.143l-3.428 3.428h-3.429l-3 3v-3H6.857V1.714h13.714Z',
    color: '#9146FF',
    scopes: 'user:read:email',
    consoleUrl: 'https://dev.twitch.tv/console/apps',
    docsUrl: 'https://dev.twitch.tv/docs/authentication/register-app',
  },
  instagram: {
    name: 'Instagram',
    svg: 'M7.0301.084c-1.2768.0602-2.1487.264-2.911.5634-.7888.3075-1.4575.72-2.1228 1.3877-.6652.6677-1.075 1.3368-1.3802 2.127-.2954.7638-.4956 1.6365-.552 2.914-.0564 1.2775-.0689 1.6882-.0626 4.947.0062 3.2586.0206 3.6671.0825 4.9473.061 1.2765.264 2.1482.5635 2.9107.308.7889.72 1.4573 1.388 2.1228.6679.6655 1.3365 1.0743 2.1285 1.38.7632.295 1.6361.4961 2.9134.552 1.2773.056 1.6884.069 4.9462.0627 3.2578-.0062 3.668-.0207 4.9478-.0814 1.28-.0607 2.147-.2652 2.9098-.5633.7889-.3086 1.4578-.72 2.1228-1.3881.665-.6682 1.0745-1.3378 1.3795-2.1284.2957-.7632.4966-1.636.552-2.9124.056-1.2809.0692-1.6898.063-4.948-.0063-3.2583-.021-3.6668-.0817-4.9465-.0607-1.2797-.264-2.1487-.5633-2.9117-.3084-.7889-.72-1.4568-1.3876-2.1228C21.2982 1.33 20.628.9208 19.8378.6165 19.074.321 18.2017.1197 16.9244.0645 15.6471.0093 15.236-.005 11.977.0014 8.718.0076 8.31.0215 7.0301.0839m.1402 21.6932c-1.17-.0509-1.8053-.2453-2.2287-.408-.5606-.216-.96-.4771-1.3819-.895-.422-.4178-.6811-.8186-.9-1.378-.1644-.4234-.3624-1.058-.4171-2.228-.0595-1.2645-.072-1.6442-.079-4.848-.007-3.2037.0053-3.583.0607-4.848.05-1.169.2456-1.805.408-2.2282.216-.5613.4762-.96.895-1.3816.4188-.4217.8184-.6814 1.3783-.9003.423-.1651 1.0575-.3614 2.227-.4171 1.2655-.06 1.6447-.072 4.848-.079 3.2033-.007 3.5835.005 4.8495.0608 1.169.0508 1.8053.2445 2.228.408.5608.216.96.4754 1.3816.895.4217.4194.6816.8176.9005 1.3787.1653.4217.3617 1.056.4169 2.2263.0602 1.2655.0739 1.645.0796 4.848.0058 3.203-.0055 3.5834-.061 4.848-.051 1.17-.245 1.8055-.408 2.2294-.216.5604-.4763.96-.8954 1.3814-.419.4215-.8181.6811-1.3783.9-.4224.1649-1.0577.3617-2.2262.4174-1.2656.0595-1.6448.072-4.8493.079-3.2045.007-3.5825-.006-4.848-.0608M16.953 5.5864A1.44 1.44 0 1 0 18.39 4.144a1.44 1.44 0 0 0-1.437 1.4424M5.8385 12.012c.0067 3.4032 2.7706 6.1557 6.173 6.1493 3.4026-.0065 6.157-2.7701 6.1506-6.1733-.0065-3.4032-2.771-6.1565-6.174-6.1498-3.403.0067-6.156 2.771-6.1496 6.1738M8 12.0077a4 4 0 1 1 4.008 3.9921A3.9996 3.9996 0 0 1 8 12.0077',
    color: '#E4405F',
    scopes: 'instagram_business_basic',
    consoleUrl: 'https://developers.facebook.com/apps',
    docsUrl: 'https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login',
  },
};

async function loadPublicConfig(event) {
  try {
    const config = await ApiUtil.get({
      path: '/api/social-login/config',
      request: event,
    });
    if (config.result === 'ok' && Array.isArray(config.enabledProviders)) {
      enabledProviders.set(config.enabledProviders);
    }
  } catch (e) {
    console.error('[pano-plugin-social-login] Failed to load config', e);
  }
}

/**
 * Establishes a client-side session after the backend hands back a csrfToken — the same
 * shape the theme's own login flow uses (fetch credentials, push them into the session store).
 */
export async function applyLoginSession(session, csrfToken) {
  const credsRes = await fetch('/api/auth/credentials', {
    headers: { 'X-CSRF-Token': csrfToken },
  });
  const creds = await credsRes.json();
  if (session) {
    session.update((sessionData) => {
      sessionData.user = Object.keys(creds)
        .filter((key) => key !== 'result')
        .reduce((object, key) => {
          object[key] = creds[key];
          return object;
        }, {});
      sessionData.csrfToken = csrfToken;
      return sessionData;
    });
  }
}

/**
 * Where a site path lives on this host. On a SERVERS install no theme runs and the panel serves
 * the sign-in pages (the platform moves `/social-login/…` and `/login` under `/panel`), so every
 * link and redirect between them has to stay there.
 */
export function sitePath(path) {
  if (!pano?.isPanel) return path;
  return path === '/' ? '/panel' : `/panel${path}`;
}

/**
 * Finishes a social sign-in once the backend has set the cookies. The panel checks panel access
 * and opens the dashboard itself (`pano.ui.auth.login.complete`); the theme refreshes its
 * session store and goes home after `delay` ms. Resolves to `'ok'` or `'NO_PANEL_ACCESS'`.
 */
export async function finishLogin(session, csrfToken, delay = 1200) {
  if (pano?.isPanel && pano.ui.auth?.login?.complete) {
    return await pano.ui.auth.login.complete(csrfToken);
  }

  await applyLoginSession(session, csrfToken);
  setTimeout(() => goto('/'), delay);

  return 'ok';
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
        component: viewComponent(
          () => import('./theme/components/view/SocialLoginButtons.svelte'),
        ),
        priority: 50,
      });
    }
  });
}

/**
 * The pages an OAuth round trip lands on. `/social-login/register` is theme-only: a brand-new
 * account has no panel access, so the panel never offers to create one.
 */
function registerSignInPages(pano, options = {}) {
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

/**
 * Maps a backend error code (the thrown Error's status message, e.g. `INVALID_FLOW_STATE`) to a
 * human-readable, localized message under the `error.<CODE>` namespace. Unknown codes — and raw
 * provider/exchange messages — fall back to the generic message, so a raw code is never shown.
 * Pass the component's `$_` translate function as `$t`.
 */
export function resolveSocialError($t, code) {
  if (!code) return $t('error.generic');
  const msg = $t(`error.${code}`);
  // svelte-i18n returns the (prefixed) key id when a translation is missing — detect & fall back.
  if (!msg || msg.includes(`error.${code}`)) return $t('error.generic');
  return msg;
}

export default class SocialLoginPlugin extends PanoPlugin {
  onLoad() {
    pano = this.pano;

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
          const config = await ApiUtil.get({
            path: '/api/panel/social-login/config',
            request: event,
          });
          data.addon.config = config;
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
        registerSignInPages(pano, { public: true });
      }
    } else {
      registerSignInPages(pano);

      // Registration form for a brand-new account coming from a social provider.
      pano.ui.page.register({
        path: '/social-login/register',
        component: viewComponent(() => import('./theme/pages/SocialRegisterPage.svelte')),
      });

      // User settings — link/unlink controls.
      pano.ui.settings.cardRows.edit((items) => {
        items.push({
          id: `${pluginId}-account-links`,
          component: viewComponent(
            () => import('./theme/components/view/SocialAccountLinks.svelte'),
          ),
          priority: 105,
        });
      });

      // User profile — read-only linked accounts (own profile only).
      pano.ui.profile.cardRows.edit((items) => {
        items.push({
          id: `${pluginId}-profile-links`,
          component: viewComponent(
            () => import('./theme/components/view/SocialLinksProfileSidebar.svelte'),
          ),
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
            component: viewComponent(
              () => import('./theme/components/view/SocialLoginButtons.svelte'),
            ),
            priority: 50,
          });
        }
      });
    }
  }

  onContextUpdate(ctx) {}

  onUnload() {}
}
