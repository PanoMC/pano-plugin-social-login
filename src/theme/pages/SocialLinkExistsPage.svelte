<style>
  .social-login-social-link-exists-page {
    max-width: 480px;
    margin: 0 auto;
    padding: 2rem 1rem;
    animation: social-login-fade-in 1s both;
  }

  .social-login-social-link-exists-page__body {
    padding: 2rem;
  }

  .social-login-social-link-exists-page__badge {
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

<div class="social-login-social-link-exists-page">
  <div class="social-login-social-link-exists-page__card card">
    <div class="social-login-social-link-exists-page__body card-body">
      {#if !isBrowser}
        <!-- URL params (token/provider/email) aren't available during SSR, so don't render the
             conditional content yet — the `!token` branch would otherwise flash the
             INVALID_FLOW_STATE alert before CSR replaces it with the real form. -->
        <div class="text-center py-4">
          <div class="spinner-border text-secondary" role="status"></div>
        </div>
      {:else}
      <h5 class="social-login-social-link-exists-page__title card-title text-center">{$_('link-existing.title')}</h5>

      {#if providerMeta}
        <div class="text-center mb-3">
          <span class="social-login-social-link-exists-page__badge" style="background-color: {providerMeta.color};">
            <ProviderIcon meta={providerMeta} />
            {providerMeta.name}
          </span>
        </div>
      {/if}

      <p class="text-muted text-center small">
        {$_('link-existing.description', { values: { email: email } })}
      </p>

      {#if error}
        <div class="social-login-social-link-exists-page__alert alert alert-danger py-2" role="alert">{error}</div>
      {/if}

      {#if success}
        <div class="social-login-social-link-exists-page__success alert alert-success py-2 text-center" role="alert">
          {$_('link-existing.success')}
        </div>
      {:else if !token}
        <div class="social-login-social-link-exists-page__invalid-flow-state alert alert-danger py-2" role="alert">
          {$_('link-existing.error.INVALID_FLOW_STATE')}
        </div>
        <a href={sitePath('/login')} class="social-login-social-link-exists-page__action btn btn-secondary w-100 mt-2">{$_('link-existing.back-to-login')}</a>
      {:else}
        <form onsubmit={(e) => { e.preventDefault(); onSubmit(); }}>
          <div class="vstack gap-3">
            <div class="form-floating">
              <input
                type="text"
                id="linkUsernameOrEmail"
                class="social-login-social-link-exists-page__input form-control"
                disabled={loading}
                bind:value={usernameOrEmail} />
              <label for="linkUsernameOrEmail">{$_('link-existing.username-label')}</label>
            </div>
            <div class="form-floating">
              <input
                type="password"
                id="linkPassword"
                class="social-login-social-link-exists-page__input-2 form-control"
                disabled={loading}
                bind:value={password} />
              <label for="linkPassword">{$_('link-existing.password-label')}</label>
            </div>
            {#each contentItems as item (item.id)}
              {#if item.component && typeof item.component !== 'function'}
                {@const Field = item.component.default || item.component}
                <div>
                  <!-- deferred: false — this form is single-submit (username + password in one go), so the
                     login-content captcha should render immediately rather than waiting for an
                     initial failed request as on the theme's two-step /login page. -->
                <Field data={{ pageType: 'login', deferred: false }} />
                </div>
              {/if}
            {/each}
            <button
              type="submit"
              class="social-login-social-link-exists-page__submitting btn btn-lg btn-secondary w-100"
              class:disabled={loading || !canSubmit}
              disabled={loading || !canSubmit}>
              {#if loading}
                <span class="spinner-border spinner-border-sm me-2" role="status" aria-label="Loading"></span>
                <span>{$_('link-existing.submitting')}</span>
              {:else}
                {$_('link-existing.submit')}
              {/if}
            </button>
            <a href={sitePath('/login')} class="text-center small">{$_('link-existing.back-to-login')}</a>
          </div>
        </form>
      {/if}
      {/if}
    </div>
  </div>
</div>

<script module>
  // A page of the sign-in round trip; the build registers it (no registration code in the entry).
  export const view = { path: '/social-login/link' };
</script>

<script>
  import { derived } from 'svelte/store';
  import { _ as i18n } from '@panomc/sdk/utils/language';
  import { pluginText } from '../lib/text.js';
  import { PROVIDER_META } from '../lib/providers.js';
  import { apiBody, resolveSocialError, resolveSocialErrorBody } from '../lib/errors.js';
  import { finishLogin, sitePath } from '../lib/signIn.js';
  import { getHost } from '../lib/host.js';
  import ProviderIcon from '../../components/ProviderIcon.svelte';
  import { onMount } from 'svelte';
  import { goto } from '@panomc/sdk/svelte';
  import { api } from '@panomc/sdk/plugin-api';

  const _ = pluginText(derived, i18n);
  const pano = getHost();

  const ERROR_KEYS = [
    'LOGIN_IS_INVALID',
    'LOGIN_USER_IS_BANNED',
    'LOGIN_EMAIL_NOT_VERIFIED',
    'EMAIL_MISMATCH',
    'INVALID_FLOW_STATE',
    'SOCIAL_ACCOUNT_ALREADY_LINKED',
  ];

  const isBrowser = typeof window !== 'undefined';

  let token = $state(null);
  let provider = $state('');
  let email = $state('');
  let usernameOrEmail = $state('');
  let password = $state('');
  let loading = $state(false);
  let error = $state(null);
  let success = $state(false);

  // Plugin-contributed login fields (e.g. auth-guard captcha) injected into "login-content".
  let contentItems = $state([]);
  let contentUnsub = null;

  let providerMeta = $derived(provider ? PROVIDER_META[provider] : null);
  let canSubmit = $derived(usernameOrEmail.length > 0 && password.length > 0);

  onMount(async () => {
    const url = new URL(window.location.href);
    token = url.searchParams.get('token');
    provider = url.searchParams.get('provider') || '';
    email = url.searchParams.get('email') || '';

    if (!token) {
      error = $_('link-existing.error.INVALID_FLOW_STATE');
    }

    // This is a password login, so run the theme's login lifecycle: login-content plugins
    // (captcha, …) populate and render here just like on the theme's own login page.
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
  });

  // Cleanup runs via $effect (client-only by design) rather than a top-level `onDestroy` — the
  // latter throws during SSR on this page in Svelte 5 ("Cannot read properties of null (reading
  // 'r')") because the compiled SSR output calls onDestroy outside a component context.
  $effect(() => {
    return () => {
      if (contentUnsub) contentUnsub();
    };
  });

  /** The `error` of an answer: the page's own text for its codes, plus a ban's or a plugin's reason. */
  function resolveError(error) {
    const code = error?.code;
    const message = code && ERROR_KEYS.includes(code) ? $_(`link-existing.error.${code}`) : undefined;
    return resolveSocialErrorBody($_, error, { raw: $i18n, message });
  }

  async function onSubmit() {
    if (!token || loading || !canSubmit) return;
    loading = true;
    error = null;

    try {
      // Through the plugin-scoped client: it sends X-CSRF-Token, which core requires once a session cookie exists.
      const body = apiBody(
        await api.post({
          path: '/link-existing',
          body: {
            pendingToken: token,
            usernameOrEmail,
            password,
          },
        }),
      );

      if (body.error) {
        error = resolveError(body.error);
        loading = false;
        return;
      }

      // The link was created, but a step-up (e.g. 2FA) is required before the session can be issued.
      // Hand off to the plugin-agnostic completion page; auth-guard's modal handles the challenge.
      if (body.action === 'complete-required') {
        const params = new URLSearchParams({
          token: body.pendingToken || '',
          provider: body.provider || provider || '',
        });
        goto(sitePath(`/social-login/complete?${params.toString()}`));
        return;
      }

      success = true;
      if ((await finishLogin(body.csrfToken)) === 'NO_PANEL_ACCESS') {
        success = false;
        loading = false;
        error = resolveSocialError($_, 'NO_PANEL_ACCESS');
      }
    } catch (e) {
      console.error('[pano-plugin-social-login] Link-existing error:', e);
      error = $_('error.connection');
      loading = false;
    }
  }
</script>
