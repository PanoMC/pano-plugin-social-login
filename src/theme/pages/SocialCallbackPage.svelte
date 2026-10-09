<style>
  .social-login-social-callback-page {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
  }

  .social-login-social-callback-page.is-animated {
    animation: social-login-fade-in 1s both;
  }

  .social-login-social-callback-page__body {
    padding: 2rem;
  }

  .social-login-social-callback-page__icon {
    font-size: 3rem;
    margin-bottom: 1rem;
  }

  .social-login-social-callback-page__icon.is-success {
    animation: social-login-bounce-in 0.75s both;
  }

  .social-login-social-callback-page__icon.is-error {
    animation: social-login-shake-x 1s both;
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
  <div class="social-login-social-callback-page">
    <div class="social-login-social-callback-page__card card">
      <div class="social-login-social-callback-page__body card-body text-center">
        <div class="social-login-social-callback-page__icon is-loading">
          <div class="spinner-border" role="status"></div>
        </div>
      </div>
    </div>
  </div>
{:else}
  <div class="social-login-social-callback-page is-animated">
    <div class="social-login-social-callback-page__card card">
      <div class="social-login-social-callback-page__body card-body text-center">
        {#if callbackState === 'loading'}
          <div class="social-login-social-callback-page__icon is-loading">
            <div class="spinner-border" role="status"></div>
          </div>
          <h5>{$_('callback.loading')}</h5>
        {:else if callbackState === 'success'}
          <div class="social-login-social-callback-page__icon is-success">
            <i class="fas fa-check-circle"></i>
          </div>
          <h5 class="social-login-social-callback-page__title text-success">{$_('callback.login-success')}</h5>
          <p class="text-muted">{$_('callback.redirecting')}</p>
        {:else if callbackState === 'linked'}
          <div class="social-login-social-callback-page__icon is-success">
            <i class="fas fa-link"></i>
          </div>
          <h5 class="social-login-social-callback-page__link-success text-success">{$_('callback.link-success')}</h5>
          <p class="text-muted">{$_('callback.redirecting')}</p>
        {:else}
          <div class="social-login-social-callback-page__icon is-error">
            <i class="fas fa-times-circle"></i>
          </div>
          <h5 class="social-login-social-callback-page__error text-danger">{$_('callback.error')}</h5>
          <p class="text-muted">{errorMessage}</p>
          <a href={sitePath('/login')} class="social-login-social-callback-page__action btn btn-secondary">{$_('callback.back-to-login')}</a>
        {/if}
      </div>
    </div>
  </div>
{/if}

<script module>
  // A page of the sign-in round trip; the build registers it (no registration code in the entry).
  export const view = { path: '/social-login/callback' };
</script>

<script>
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../lib/text.js';
  import { resolveSocialError } from '../lib/errors.js';
  import { finishLogin, sitePath } from '../lib/signIn.js';
  import { getHost } from '../lib/host.js';
  import { onMount } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';
  import { api } from '@panomc/sdk/plugin-api';

  const _ = pluginText(derived, i18n);
  const pano = getHost();

  const isBrowser = typeof window !== 'undefined';

  let callbackState = $state('loading');
  let errorMessage = $state('');

  onMount(async () => {
    const url = new URL(window.location.href);
    const provider = url.searchParams.get('provider') || '';
    const code = url.searchParams.get('code');
    const state = url.searchParams.get('state');
    const errorParam = url.searchParams.get('error');

    if (errorParam) {
      callbackState = 'error';
      errorMessage = resolveSocialError($_, errorParam);
      return;
    }

    if (!code || !state) {
      callbackState = 'error';
      errorMessage = $_('callback.no-code');
      return;
    }

    try {
      // Through the plugin-scoped client: it repeats the CSRF cookie in X-CSRF-Token, which the link intent
      // (a signed-in visitor attaching an account) needs on this POST.
      const body = await api.post({ path: '/process', body: { provider, code, state } });

      if (body.error) {
        callbackState = 'error';
        errorMessage = resolveSocialError($_, body.error.code);
        return;
      }

      if (body.action === 'login') {
        callbackState = 'success';
        if ((await finishLogin(body.csrfToken, 1500)) === 'NO_PANEL_ACCESS') {
          callbackState = 'error';
          errorMessage = resolveSocialError($_, 'NO_PANEL_ACCESS');
        }
      } else if (body.action === 'link') {
        callbackState = 'linked';
        setTimeout(() => goto(pano?.isPanel ? sitePath('/') : '/profile/settings'), 1500);
      } else if (body.action === 'register' && pano?.isPanel) {
        // A brand-new account would have no panel access; the panel never creates one.
        callbackState = 'error';
        errorMessage = resolveSocialError($_, 'NO_LINKED_ACCOUNT');
      } else if (body.action === 'register') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider,
          username: body.suggestedUsername || '',
          email: body.email || '',
        });
        goto(`/social-login/register?${params.toString()}`);
      } else if (body.action === 'account-exists') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider,
          email: body.email || '',
        });
        goto(sitePath(`/social-login/link?${params.toString()}`));
      } else if (body.action === 'complete-required') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider,
        });
        goto(sitePath(`/social-login/complete?${params.toString()}`));
      } else {
        callbackState = 'error';
        errorMessage = $_('error.generic');
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Process error:', e);
      callbackState = 'error';
      errorMessage = $_('error.connection');
    }
  });
</script>
