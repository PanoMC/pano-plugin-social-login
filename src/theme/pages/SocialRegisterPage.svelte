<style>
  .social-login-social-register-page {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
    animation: social-login-fade-in 1s both;
  }

  .social-login-social-register-page__body {
    padding: 2rem;
  }

  .social-login-social-register-page__badge {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.35rem 0.7rem;
    border-radius: 0.5rem;
    color: #fff;
    font-weight: 500;
    font-size: 0.9rem;
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

<div class="social-login-social-register-page">
  <div class="social-login-social-register-page__card card">
    <div class="social-login-social-register-page__body card-body">
      {#if !isBrowser}
        <!-- URL params aren't available during SSR — without this gate the `!token` branch would
             flash INVALID_FLOW_STATE before CSR populates the form. -->
        <div class="text-center py-4">
          <div class="spinner-border text-secondary" role="status"></div>
        </div>
      {:else}
      <h5 class="social-login-social-register-page__title card-title text-center">{$_('register.title')}</h5>

      {#if providerMeta}
        <div class="text-center mb-3">
          <span class="social-login-social-register-page__badge" style="background-color: {providerMeta.color};">
            <ProviderIcon meta={providerMeta} />
            {$_('register.via', { values: { provider: providerMeta.name } })}
          </span>
        </div>
      {/if}

      <p class="text-muted text-center small">{$_('register.description')}</p>

      {#if error}
        <div class="social-login-social-register-page__alert alert alert-danger py-2" role="alert">{error}</div>
      {/if}

      {#if verifyEmail}
        <div class="social-login-social-register-page__verify-email alert alert-success py-2 text-center" role="alert">
          {$_('register.verify-email', { values: { email: verifyEmail } })}
        </div>
        <a href="/login" class="social-login-social-register-page__action btn btn-secondary w-100 mt-2">{$_('register.back-to-login')}</a>
      {:else if success}
        <div class="social-login-social-register-page__success alert alert-success py-2 text-center" role="alert">
          {$_('register.success')}
        </div>
      {:else if !token}
        <div class="social-login-social-register-page__invalid-flow-state alert alert-danger py-2" role="alert">{$_('register.error.INVALID_FLOW_STATE')}</div>
        <a href="/login" class="social-login-social-register-page__action btn btn-secondary w-100 mt-2">{$_('register.back-to-login')}</a>
      {:else}
        <form onsubmit={(e) => { e.preventDefault(); onSubmit(); }}>
          <div class="vstack gap-3">
            <div class="form-floating">
              <input
                type="text"
                id="socialUsername"
                class="social-login-social-register-page__input form-control"
                disabled={loading}
                maxlength="16"
                bind:value={username} />
              <label for="socialUsername">{$_('register.username-label')}</label>
            </div>
            <div class="form-floating">
              <input
                type="email"
                id="socialEmail"
                class="social-login-social-register-page__input-2 form-control"
                disabled={loading}
                bind:value={email} />
              <label for="socialEmail">{$_('register.email-label')}</label>
            </div>
            <div class="form-floating">
              <input
                type="password"
                id="socialPassword"
                class="social-login-social-register-page__input-3 form-control"
                disabled={loading}
                bind:value={password} />
              <label for="socialPassword">{$_('register.password-label')}</label>
            </div>
            <div class="form-floating">
              <input
                type="password"
                id="socialPasswordRepeat"
                class="social-login-social-register-page__input-4 form-control"
                disabled={loading}
                bind:value={passwordRepeat} />
              <label for="socialPasswordRepeat">{$_('register.password-repeat-label')}</label>
            </div>
            {#if requireAgreement}
              <div class="form-check">
                <input
                  type="checkbox"
                  id="socialAgreement"
                  class="social-login-social-register-page__check form-check-input"
                  disabled={loading}
                  bind:checked={agreement} />
                <label class="form-check-label small" for="socialAgreement">
                  {@html $_('register.agreement', {
                    values: {
                      link: `<a class="rounded focus-ring" href="/rules">${$_('register.server-rules')}</a>`,
                    },
                  })}
                </label>
              </div>
            {/if}
            {#each contentItems as item (item.id)}
              {#if item.component && typeof item.component !== 'function'}
                {@const Field = item.component.default || item.component}
                <div class="mb-2">
                  <Field data={{ pageType: 'register' }} />
                </div>
              {/if}
            {/each}
            <button
              type="submit"
              class="social-login-social-register-page__submitting btn btn-lg btn-secondary w-100"
              class:disabled={loading || !canSubmit}
              disabled={loading || !canSubmit}>
              {#if loading}
                <span class="spinner-border spinner-border-sm me-2" role="status" aria-label="Loading"></span>
                <span>{$_('register.submitting')}</span>
              {:else}
                {$_('register.submit')}
              {/if}
            </button>
          </div>
        </form>
      {/if}
      {/if}
    </div>
  </div>
</div>

<script module>
  // A page of the sign-in round trip; the build registers it (no registration code in the entry).
  export const view = { path: '/social-login/register' };
</script>

<script>
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../lib/text.js';
  import { PROVIDER_META } from '../lib/providers.js';
  import { resolveSocialError } from '../lib/errors.js';
  import { finishLogin } from '../lib/signIn.js';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { getHost } from '../lib/host.js';
  import ProviderIcon from '../../components/ProviderIcon.svelte';
  import { onMount } from 'svelte';

  const _ = pluginText(derived, i18n);
  const pano = getHost();

  const ERROR_KEYS = [
    'REGISTER_USERNAME_NOT_AVAILABLE',
    'REGISTER_EMAIL_NOT_AVAILABLE',
    'REGISTER_USERNAME_EMPTY',
    'REGISTER_EMAIL_EMPTY',
    'REGISTER_USERNAME_TOO_SHORT',
    'REGISTER_USERNAME_TOO_LONG',
    'REGISTER_INVALID_USERNAME',
    'REGISTER_INVALID_EMAIL',
    'REGISTER_PASSWORD_AND_PASSWORD_REPEAT_NOT_SAME',
    'REGISTER_NOT_ACCEPTED_AGREEMENT',
    'PASSWORD_EMPTY',
    'PASSWORD_TOO_SHORT',
    'PASSWORD_TOO_LONG',
    'IP_IS_BANNED',
    'INVALID_FLOW_STATE',
    'SOCIAL_ACCOUNT_ALREADY_LINKED',
  ];

  const isBrowser = typeof window !== 'undefined';

  let token = $state(null);
  let provider = $state('');
  let username = $state('');
  let email = $state('');
  let password = $state('');
  let passwordRepeat = $state('');
  let agreement = $state(false);
  let requireAgreement = $state(true);
  let loading = $state(false);
  let error = $state(null);
  let success = $state(false);
  // Set when the site wants the e-mail confirmed first: the account exists, no session yet.
  let verifyEmail = $state('');

  // Plugin-contributed register fields (e.g. auth-guard captcha) injected into "register-content".
  let contentItems = $state([]);
  let contentUnsub = null;

  let providerMeta = $derived(provider ? PROVIDER_META[provider] : null);
  let canSubmit = $derived(
    username.length >= 3 && email.length > 0 && password.length >= 6 && agreement,
  );

  onMount(async () => {
    const url = new URL(window.location.href);
    token = url.searchParams.get('token');
    provider = url.searchParams.get('provider') || '';
    username = url.searchParams.get('username') || '';
    email = url.searchParams.get('email') || '';

    if (!token) {
      error = $_('register.error.INVALID_FLOW_STATE');
    }

    // Mirror the theme register: only require the agreement when the site enables it.
    // (The site info is read from the API: a view does not reach into the theme's session context, rule V5.)
    try {
      const info = await ApiUtil.get({ path: '/site-info' });
      if (info && !info.error) {
        requireAgreement = !!info.hasRegisterAgreement;
        if (!requireAgreement) agreement = true;
      }
    } catch (e) {
      // keep the strict default: the agreement stays required
    }

    // Run the theme's register lifecycle so register-content plugins (captcha, …) populate and
    // render here exactly as they do on the theme's own register page. No knowledge of which
    // plugin or what it adds — just the generic theme hook surface.
    if (pano?.ui?.auth?.register?.load) {
      try {
        await pano.ui.auth.register.load();
        contentUnsub = pano.ui.auth.register.content.get().subscribe((items) => {
          contentItems = items || [];
        });
      } catch (e) {
        console.error('[pano-plugin-social-login] Failed to load register content', e);
      }
    }
  });

  // Cleanup via $effect (client-only by design) rather than top-level `onDestroy` — Svelte 5
  // SSR throws on this page's compiled onDestroy output (see SocialLinkExistsPage for context).
  $effect(() => {
    return () => {
      if (contentUnsub) contentUnsub();
    };
  });

  function resolveError(code) {
    if (code && ERROR_KEYS.includes(code)) {
      return $_(`register.error.${code}`);
    }
    return resolveSocialError($_, code);
  }

  async function onSubmit() {
    if (!token || loading || !canSubmit) return;
    loading = true;
    error = null;

    try {
      const res = await fetch('/api/plugins/pano-plugin-social-login/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pendingToken: token,
          username,
          email,
          password,
          passwordRepeat,
          agreement,
        }),
      });
      const body = await res.json();

      if (body.error) {
        error = resolveError(body.error.code);
        loading = false;
        return;
      }

      if (body.action === 'verify-email') {
        verifyEmail = body.email || email;
        loading = false;
        return;
      }

      success = true;
      // A full page load: the server renders the signed-in page from the cookies the answer set.
      await finishLogin(body.csrfToken);
    } catch (e) {
      console.error('[pano-plugin-social-login] Register error:', e);
      error = $_('error.connection');
      loading = false;
    }
  }
</script>
