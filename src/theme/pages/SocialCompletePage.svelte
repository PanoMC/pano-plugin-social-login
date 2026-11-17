<style>
  .social-login-social-complete-page {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .social-login-social-complete-page.is-animated {
    animation: social-login-fade-in 1s both;
  }

  .social-login-social-complete-page__body {
    padding: 2rem;
  }

  .social-login-social-complete-page__icon {
    font-size: 3rem;
    margin-bottom: 1rem;
  }

  .social-login-social-complete-page__icon.is-success {
    animation: social-login-bounce-in 0.75s both;
  }

  .social-login-social-complete-page__icon.is-error {
    animation: social-login-shake-x 1s both;
  }

  /* The slot mount is needed for plugin widgets (e.g. auth-guard's TwoFactorLoginGuard) to
     register their global fetch interceptor - but we don't want their inline UI in our card. */
  .social-login-social-complete-page__slot-mount {
    display: none;
  }

  @keyframes social-login-fade-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  @keyframes social-login-bounce-in {
    from,
    20%,
    40%,
    60%,
    80%,
    to {
      animation-timing-function: cubic-bezier(0.215, 0.61, 0.355, 1);
    }
    0% {
      opacity: 0;
      transform: scale3d(0.3, 0.3, 0.3);
    }
    20% {
      transform: scale3d(1.1, 1.1, 1.1);
    }
    40% {
      transform: scale3d(0.9, 0.9, 0.9);
    }
    60% {
      opacity: 1;
      transform: scale3d(1.03, 1.03, 1.03);
    }
    80% {
      transform: scale3d(0.97, 0.97, 0.97);
    }
    to {
      opacity: 1;
      transform: scale3d(1, 1, 1);
    }
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

{#if !isBrowser}
  <div class="social-login-social-complete-page">
    <div class="social-login-social-complete-page__card card">
      <div class="social-login-social-complete-page__body card-body text-center">
        <div class="social-login-social-complete-page__icon is-loading">
          <div class="spinner-border" role="status"></div>
        </div>
      </div>
    </div>
  </div>
{:else}
  <div class="social-login-social-complete-page is-animated">
    <div class="social-login-social-complete-page__card card">
      <div class="social-login-social-complete-page__body card-body text-center">
        {#if pageState === 'loading'}
          <div class="social-login-social-complete-page__icon is-loading">
            <div class="spinner-border" role="status"></div>
          </div>
          <h5>{$_('complete.loading')}</h5>
          <p class="text-muted small mb-0">{$_('complete.subtitle')}</p>
        {:else if pageState === 'success'}
          <div class="social-login-social-complete-page__icon is-success">
            <i class="fas fa-check-circle"></i>
          </div>
          <h5 class="social-login-social-complete-page__title text-success">{$_('callback.login-success')}</h5>
          <p class="text-muted">{$_('callback.redirecting')}</p>
        {:else if pageState === 'username-required'}
          <!-- The account still has a temporary username: core asks for a new one on the password sign-in
               (the login page's username step), so the visitor continues there. -->
          <div class="social-login-social-complete-page__icon text-primary">
            <i class="fas fa-user-edit"></i>
          </div>
          <h5 class="social-login-social-complete-page__title">{$_('complete.username-required-title')}</h5>
          <p class="text-muted">{$_('complete.username-required')}</p>
          <a href={sitePath('/login')} class="social-login-social-complete-page__action btn btn-primary">{$_('complete.username-required-action')}</a>
        {:else}
          <div class="social-login-social-complete-page__icon is-error">
            <i class="fas fa-times-circle"></i>
          </div>
          <h5 class="social-login-social-complete-page__error text-danger">{$_('callback.error')}</h5>
          <p class="text-muted">{errorMessage}</p>
          <a href={sitePath('/login')} class="social-login-social-complete-page__action btn btn-secondary">{$_('callback.back-to-login')}</a>
        {/if}
      </div>
    </div>

    <!--
      Mount the active theme's login-content slot items so plugin widgets (e.g. auth-guard's
      TwoFactorLoginGuard / CaptchaWidget) install their fetch interceptors and can pop their
      challenge UI when /api/v1/auth/complete-pending responds. Visually hidden — the modal-style
      widgets render their UI as overlays; inline widgets (captcha) aren't relevant here since the
      core completion endpoint doesn't run captcha hooks.
    -->
    <div class="social-login-social-complete-page__slot-mount" aria-hidden="true">
      {#each contentItems as item (item.id)}
        {#if item.id !== 'login-form' && item.component && typeof item.component !== 'function'}
          {@const Field = item.component.default || item.component}
          <Field data={{ pageType: 'login' }} />
        {/if}
      {/each}
    </div>
  </div>
{/if}

<script module>
  // A page of the sign-in round trip; the build registers it (no registration code in the entry).
  export const view = { path: '/social-login/complete' };
</script>

<script>
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { pluginText } from '../lib/text.js';
  import { apiBody, resolveSocialError, resolveSocialErrorBody } from '../lib/errors.js';
  import { finishLogin, sitePath } from '../lib/signIn.js';
  import { getHost } from '../lib/host.js';
  import { onMount, tick } from 'svelte';

  const _ = pluginText(derived, i18n);
  const pano = getHost();

  const isBrowser = typeof window !== 'undefined';

  let pageState = $state('loading');
  let errorMessage = $state('');

  // Plugin-contributed login-content items (e.g. auth-guard's TwoFactorLoginGuard, captcha widget).
  // Populated by `pano.ui.auth.login.load()` in onMount; the explicit subscribe mirrors the pattern
  // used by SocialLinkExistsPage.
  let contentItems = $state([]);
  let contentUnsub = null;

  // Cleanup via $effect (client-only by design) rather than top-level `onDestroy` — see
  // SocialLinkExistsPage for context: SSR throws on this page's compiled onDestroy output.
  $effect(() => {
    return () => {
      if (contentUnsub) contentUnsub();
    };
  });

  onMount(async () => {
    const url = new URL(window.location.href);
    const token = url.searchParams.get('token') || '';

    if (!token) {
      pageState = 'error';
      errorMessage = $_('complete.invalid-token');
      return;
    }

    // Populate the login-content slot so its widgets (auth-guard's TwoFactorLoginGuard, etc.)
    // mount and register their fetch interceptors BEFORE we fire the completion request.
    if (pano?.ui?.auth?.login?.load) {
      try {
        await pano.ui.auth.login.load();
        contentUnsub = pano.ui.auth.login.content.get().subscribe((items) => {
          contentItems = items || [];
        });
      } catch (e) {
        console.error('[pano-plugin-social-login] Failed to load login content', e);
      }
    }
    // Let Svelte render the newly-resolved slot items so their onMount runs (and the
    // TwoFactorLoginGuard's fetch monkey-patch is active before we fire the POST).
    await tick();

    try {
      // Through the SDK: it sends X-CSRF-Token and resolves the API base. It calls the global fetch, so the
      // login-content widgets' fetch interceptors (2FA modal, captcha) still see the request.
      const body = apiBody(await ApiUtil.post({ path: '/auth/complete-pending', body: { pendingToken: token } }));

      if (body.error?.code === 'USERNAME_REQUIRED') {
        // A plugin (or core) wants a new username first. The pending sign-in is used up, so the visitor finishes on the
        // login page, which asks for the username (`SET_USERNAME`) as on any password sign-in.
        pageState = 'username-required';
        return;
      }

      if (body.error) {
        // PLUGIN_DENIED_LOGIN (e.g. 2FA cancelled) and other failures land here. Token-side errors
        // (expired/missing) come back as INVALID_TOKEN. Generic surface — challenge UIs would've
        // intercepted the response themselves before we got here on success.
        pageState = 'error';
        errorMessage = resolveError(body);
        return;
      }

      pageState = 'success';
      if ((await finishLogin(body.csrfToken)) === 'NO_PANEL_ACCESS') {
        pageState = 'error';
        errorMessage = resolveSocialError($_, 'NO_PANEL_ACCESS');
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Complete-pending error:', e);
      pageState = 'error';
      errorMessage = $_('error.connection');
    }
  });

  function resolveError(body) {
    if (body.error?.code === 'INVALID_TOKEN') {
      return $_('complete.invalid-token');
    }
    // PLUGIN_DENIED_LOGIN carries an i18n key of the denying plugin as its reason: translated unprefixed.
    return resolveSocialErrorBody($_, body.error, { raw: $i18n });
  }
</script>
