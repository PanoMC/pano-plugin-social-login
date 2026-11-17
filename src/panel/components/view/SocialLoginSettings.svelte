{#if addon?.id === 'pano-plugin-social-login'}
  <div class="card">
    <div class="card-header">
      {$_('plugin-settings.title')}
    </div>
    <div class="card-body">
      {#if !hasProviders}
        <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
          <i class="fas fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
          <div>{$_('plugin-settings.load-error')}</div>
        </div>
      {:else}
      <div class="alert alert-info d-flex align-items-start mb-4" role="alert">
        <i class="fas fa-circle-info me-3 mt-1" aria-hidden="true"></i>
        <div>
          <h5 class="alert-heading mb-2">{$_('plugin-settings.setup-guide-title')}</h5>
          <div>{$_('plugin-settings.setup-guide-desc')}</div>
        </div>
      </div>

      <!-- Redirect base URL -->
      <div class="row mb-4">
        <div class="col-md-6">
          <label class="col-form-label pb-0 d-block" for="redirectBaseUrl">
            {$_('plugin-settings.redirect-base-url')}
          </label>
          <div class="form-text text-body-secondary mt-0" id="redirectBaseUrlHint">
            {$_('plugin-settings.redirect-base-url-desc')}
          </div>
        </div>
        <div class="col-md-6">
          <input
            type="text"
            class="form-control"
            id="redirectBaseUrl"
            aria-describedby="redirectBaseUrlHint"
            placeholder={originPlaceholder}
            bind:value={form.redirectBaseUrl} />
        </div>
      </div>

      <!-- Per-provider configuration -->
      <div class="accordion mb-4">
        {#each providerKeys as key (key)}
          {@const provider = form.providers[key]}
          {@const meta = PROVIDER_META[key]}
          {#if provider}
            <div class="accordion-item">
              <div class="d-flex align-items-center">
                <h2 class="accordion-header flex-grow-1" id="{key}Heading">
                  <button
                    type="button"
                    class="accordion-button"
                    class:collapsed={expandedProvider !== key}
                    disabled={!provider.enabled}
                    aria-expanded={expandedProvider === key}
                    aria-controls="{key}Collapse"
                    on:click={() => (expandedProvider = expandedProvider === key ? null : key)}>
                    <span class="me-2"><ProviderIcon {meta} colored /></span>
                    {provider.displayName}
                  </button>
                </h2>
                <div class="form-check form-switch mx-3 mb-0">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    id="{key}Enabled"
                    bind:checked={form.providers[key].enabled}
                    on:change={(event) => {
                      if (event.currentTarget.checked) expandedProvider = key;
                      else if (expandedProvider === key) expandedProvider = null;
                    }} />
                  <label class="visually-hidden" for="{key}Enabled">{provider.displayName}</label>
                </div>
              </div>
              <div
                id="{key}Collapse"
                class="accordion-collapse collapse"
                class:show={expandedProvider === key}
                aria-labelledby="{key}Heading">
                <div class="accordion-body">
                  {#if provider.enabled}
                    <!-- Per-provider setup guide -->
                    <div class="alert alert-info d-flex align-items-start mb-3" role="alert">
                      <i class="fas fa-circle-info me-3 mt-1" aria-hidden="true"></i>
                      <div>
                        <h5 class="alert-heading mb-2">{$_('plugin-settings.guide.heading')}</h5>
                        <div class="mb-1">
                          {$_('plugin-settings.guide.intro', { values: { provider: provider.displayName } })}
                        </div>
                        <ol class="mb-2 ps-3">
                          {#each $_(`plugin-settings.guide-notes.${key}`).split('\n') as step, i (i)}
                            <li>{step}</li>
                          {/each}
                        </ol>
                        {#if key === 'instagram'}
                          <div class="mb-2">{$_('plugin-settings.instagram-hint')}</div>
                        {/if}
                        {#if meta?.scopes}
                          <div>
                            <strong>{$_('plugin-settings.guide.scopes-label')}:</strong>
                            <code>{meta.scopes}</code>
                          </div>
                        {/if}
                        <div class="d-flex flex-wrap gap-3 mt-2">
                          {#if meta?.consoleUrl}
                            <a
                              class="alert-link"
                              href={meta.consoleUrl}
                              target="_blank"
                              rel="noopener noreferrer">
                              {$_('plugin-settings.guide.open-console')}
                            </a>
                          {/if}
                          {#if meta?.docsUrl}
                            <a
                              class="alert-link"
                              href={meta.docsUrl}
                              target="_blank"
                              rel="noopener noreferrer">
                              {$_('plugin-settings.guide.open-docs')}
                            </a>
                          {/if}
                        </div>
                      </div>
                    </div>

                    <!-- Client ID -->
                    <div class="row mb-2">
                      <label class="col-md-4 col-form-label" for="{key}ClientId">
                        {$_('plugin-settings.client-id')}
                      </label>
                      <div class="col-md-8">
                        <input
                          type="text"
                          class="form-control"
                          id="{key}ClientId"
                          bind:value={form.providers[key].clientId} />
                      </div>
                    </div>

                    <!-- Client Secret -->
                    <div class="row mb-2">
                      <label class="col-md-4 col-form-label" for="{key}ClientSecret">
                        {$_('plugin-settings.client-secret')}
                      </label>
                      <div class="col-md-8">
                        {#if provider.hasSecret && !provider.revealed && !provider.secretTouched}
                          <!-- Secret is saved but not revealed -->
                          <div class="d-flex align-items-center gap-2">
                            <input type="password" class="form-control" id="{key}ClientSecret" value="••••••••••••" disabled />
                            <button
                              class="btn btn-outline-primary btn-sm"
                              type="button"
                              title={$_('plugin-settings.reveal-secret')}
                              aria-label={$_('plugin-settings.reveal-secret')}
                              on:click={() => openRevealModal(key)}>
                              <i class="fas fa-eye"></i>
                            </button>
                            <button
                              class="btn btn-outline-primary btn-sm"
                              type="button"
                              title={$_('plugin-settings.edit-secret')}
                              aria-label={$_('plugin-settings.edit-secret')}
                              on:click={() => editSecret(key)}>
                              <i class="fas fa-edit"></i>
                            </button>
                          </div>
                        {:else}
                          <!-- Revealed, or entering a new secret -->
                          <div class="input-group">
                            <input
                              type={provider.showSecret ? 'text' : 'password'}
                              class="form-control"
                              id="{key}ClientSecret"
                              placeholder={provider.hasSecret
                                ? $_('plugin-settings.client-secret-new-placeholder')
                                : ''}
                              bind:value={form.providers[key].clientSecret}
                              on:input={() => markSecretTouched(key)} />
                            <button
                              class="btn btn-outline-primary"
                              type="button"
                              title={$_('plugin-settings.toggle-secret-visibility')}
                              aria-label={$_('plugin-settings.toggle-secret-visibility')}
                              on:click={() => toggleShowSecret(key)}>
                              <i class="fas {provider.showSecret ? 'fa-eye-slash' : 'fa-eye'}"></i>
                            </button>
                            {#if provider.hasSecret}
                              <button
                                class="btn btn-outline-danger"
                                type="button"
                                title={$_('plugin-settings.cancel-edit')}
                                aria-label={$_('plugin-settings.cancel-edit')}
                                on:click={() => cancelEditSecret(key)}>
                                <i class="fas fa-times"></i>
                              </button>
                            {/if}
                          </div>
                        {/if}
                      </div>
                    </div>

                    <!-- Redirect URI (read-only, copy to provider console) -->
                    <div class="row">
                      <label class="col-md-4 col-form-label" for="{key}RedirectUri">
                        {$_('plugin-settings.redirect-uri')}
                      </label>
                      <div class="col-md-8">
                        <div class="input-group">
                          <input
                            type="text"
                            class="form-control"
                            id="{key}RedirectUri"
                            value={provider.redirectUri}
                            readonly />
                          <button
                            class="btn btn-outline-primary"
                            type="button"
                            title={$_('plugin-settings.copy-redirect-uri')}
                            aria-label={$_('plugin-settings.copy-redirect-uri')}
                            on:click={() => copyToClipboard(provider.redirectUri)}>
                            <i class="fas fa-copy"></i>
                          </button>
                        </div>
                        <small class="d-block mt-1 text-body-secondary">
                          {$_('plugin-settings.redirect-uri-desc')}
                        </small>
                      </div>
                    </div>
                  {/if}
                </div>
              </div>
            </div>
          {/if}
        {/each}
      </div>

      <!-- Reveal secret modal (password-gated) -->
      <div aria-hidden="true" aria-labelledby="revealSecretTitle" class="modal fade" bind:this={revealModalElement} role="dialog" tabindex="-1">
        <div class="modal-dialog modal-dialog-centered" role="dialog">
          <div class="modal-content">
            <form on:submit|preventDefault={revealSecret}>
              <div class="modal-body text-center">
                <div class="pb-3">
                  <i class="fas fa-lock fa-3x d-block m-auto" aria-hidden="true"></i>
                </div>
                <h5 class="mb-2" id="revealSecretTitle">{$_('plugin-settings.reveal-title')}</h5>
                <div class="text-body-secondary">{$_('plugin-settings.reveal-desc')}</div>
                <div class="text-start mt-3">
                  <input
                    class="form-control"
                    placeholder={$_('plugin-settings.reveal-password-placeholder')}
                    aria-label={$_('plugin-settings.reveal-password-placeholder')}
                    type="password"
                    bind:value={revealPassword}
                    bind:this={revealPasswordInput}
                    class:border-danger={revealError} />
                  {#if revealError}
                    <small class="text-danger d-block mt-2">{$_(revealError)}</small>
                  {/if}
                </div>
              </div>
              <div class="modal-footer flex-nowrap">
                <button
                  class="btn btn-link text-decoration-none col-6 m-0"
                  type="button"
                  on:click={closeRevealModal}
                  disabled={revealLoading}>
                  {$_('plugin-settings.reveal-cancel')}
                </button>
                <button
                  class="btn btn-primary col-6 m-0"
                  type="submit"
                  disabled={!revealPassword || revealLoading}>
                  {#if revealLoading}
                    <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                  {/if}
                  {$_('plugin-settings.reveal-confirm')}
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>

      <!-- Debug logging -->
      <div class="row mb-3">
        <label class="col-md-6 col-form-label" for="debugLogging">
          <span class="d-block">{$_('plugin-settings.debug-logging')}</span>
          <small class="text-body-secondary">{$_('plugin-settings.debug-logging-desc')}</small>
        </label>
        <div class="col-md-6 d-flex align-items-center">
          <div class="form-check form-switch">
            <input
              class="form-check-input"
              type="checkbox"
              id="debugLogging"
              bind:checked={form.debugLogging} />
          </div>
        </div>
      </div>

      <div class="mt-4">
        <button class="btn btn-secondary" on:click={saveConfig} disabled={saving || !hasChanges}>
          {#if saving}
            <span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
          {/if}
          {$_('plugin-settings.save')}
        </button>
      </div>
      {/if}
    </div>
  </div>
{/if}

<script>
  import { _, PROVIDER_META } from '../../../main.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { showSuccessToast, showErrorToast } from '../../toast.util.js';
  import { PLUGIN_ID } from '../../../theme/lib/text.js';

  export let addon;

  const providerKeys = ['google', 'discord', 'facebook', 'twitter', 'github', 'twitch', 'instagram'];
  const originPlaceholder = typeof window !== 'undefined' ? window.location.origin : '';

  // The host toast translates the full key, so plugin texts need the `plugins.<id>.` prefix.
  const pluginKey = (key) => `plugins.${PLUGIN_ID}.${key}`;

  // The config answers its provider list under `items`; `providers` is the older key.
  const configProviders = (config) =>
    Array.isArray(config?.items) ? config.items : Array.isArray(config?.providers) ? config.providers : null;

  function buildForm() {
    const providers = {};
    (configProviders(addon?.config) || []).forEach((p) => {
      providers[p.key] = {
        key: p.key,
        displayName: p.displayName,
        enabled: !!p.enabled,
        clientId: p.clientId || '',
        clientSecret: '',
        hasSecret: !!p.hasSecret,
        secretTouched: false,
        revealed: false,
        showSecret: false,
        redirectUri: p.redirectUri || '',
      };
    });
    return {
      redirectBaseUrl: addon?.config?.redirectBaseUrl || '',
      debugLogging: !!addon?.config?.debugLogging,
      providers,
    };
  }

  let form = buildForm();
  $: hasProviders = Object.keys(form.providers).length > 0;
  let expandedProvider = providerKeys.find((key) => form.providers[key]?.enabled) || null;
  let saving = false;

  function snapshot(f) {
    return JSON.stringify({
      redirectBaseUrl: f.redirectBaseUrl,
      debugLogging: f.debugLogging,
      providers: Object.fromEntries(
        Object.entries(f.providers).map(([k, p]) => [k, { enabled: p.enabled, clientId: p.clientId }]),
      ),
    });
  }

  let initialSnapshot = snapshot(form);

  $: anySecretTouched = Object.values(form.providers).some((p) => p.secretTouched && p.clientSecret);
  $: hasChanges = snapshot(form) !== initialSnapshot || anySecretTouched;

  function editSecret(key) {
    form.providers[key].secretTouched = true;
    form.providers[key].revealed = false;
    form.providers[key].showSecret = false;
    form.providers[key].clientSecret = '';
    form = form;
  }

  function cancelEditSecret(key) {
    form.providers[key].secretTouched = false;
    form.providers[key].revealed = false;
    form.providers[key].showSecret = false;
    form.providers[key].clientSecret = '';
    form = form;
  }

  function toggleShowSecret(key) {
    form.providers[key].showSecret = !form.providers[key].showSecret;
    form = form;
  }

  function markSecretTouched(key) {
    if (!form.providers[key].secretTouched) {
      form.providers[key].secretTouched = true;
      form = form;
    }
  }

  // --- Reveal a saved client secret (password-gated, mirrors pano-plugin-premium-login) ---
  let revealKey = null;
  let revealModalElement;
  let revealModal;
  let revealPasswordInput;
  let revealPassword = '';
  /** @type {string | false} plugin text key of the reveal error, or false */
  let revealError = false;
  let revealLoading = false;

  function openRevealModal(key) {
    revealKey = key;
    revealModal = new window.bootstrap.Modal(revealModalElement, { backdrop: 'static', keyboard: false });
    revealLoading = false;
    revealError = false;
    revealPassword = '';
    revealModal.show();
    setTimeout(() => {
      if (revealPasswordInput) revealPasswordInput.focus();
    }, 500);
  }

  function closeRevealModal() {
    if (revealModal) revealModal.hide();
    revealPassword = '';
    revealError = false;
  }

  async function revealSecret() {
    if (!revealPassword || revealLoading || !revealKey) return;
    revealLoading = true;
    revealError = false;
    try {
      const res = await api.panel.post({
        path: '/config/reveal-secret',
        body: { provider: revealKey, password: revealPassword },
      });

      if (res.error) {
        revealError = revealErrorKey(res.error.code);
        revealLoading = false;
        return;
      }

      const p = form.providers[revealKey];
      if (p) {
        p.clientSecret = res.clientSecret || '';
        p.revealed = true;
        p.showSecret = true;
      }
      form = form;
      closeRevealModal();
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to reveal secret', e);
      revealError = 'error.generic';
    } finally {
      revealLoading = false;
    }
  }

  function revealErrorKey(code) {
    if (code === 'INVALID_PASSWORD') return 'plugin-settings.reveal-error';
    if (code === 'RATE_LIMITED' || code === 'NETWORK_ERROR') return `error.${code}`;
    return 'error.generic';
  }

  function saveErrorKey(error) {
    if (error?.code === 'PROVIDER_CREDENTIALS_REQUIRED' || error?.code === 'INVALID_REDIRECT_BASE_URL') {
      return `plugin-settings.error.${error.code}`;
    }
    if (error?.code === 'RATE_LIMITED' || error?.code === 'NETWORK_ERROR') return `error.${error.code}`;
    return 'plugin-settings.save-error';
  }

  async function copyToClipboard(text) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      console.error('[pano-plugin-social-login] Clipboard copy failed', e);
    }
  }

  async function saveConfig() {
    if (saving || !hasChanges) return;
    saving = true;
    try {
      const body = {
        redirectBaseUrl: form.redirectBaseUrl,
        debugLogging: form.debugLogging,
      };
      for (const key of providerKeys) {
        const provider = form.providers[key];
        if (!provider) continue;
        body[`${key}Enabled`] = provider.enabled;
        body[`${key}ClientId`] = provider.clientId;
        // Only send the secret when the admin actually typed a new one.
        if (provider.secretTouched && provider.clientSecret) {
          body[`${key}ClientSecret`] = provider.clientSecret;
        }
      }

      const res = await api.panel.put({ path: '/config', body });
      if (res?.error) {
        // Keep the form as typed (secrets included) so the admin can fix it and retry.
        const providerKey = res.error.details?.provider;
        const provider = form.providers[providerKey]?.displayName || PROVIDER_META[providerKey]?.name || providerKey || '';
        await showErrorToast(pluginKey(saveErrorKey(res.error)), { provider });
        return;
      }

      // Reload from the server so derived values (e.g. each provider's redirect URI) are current.
      const fresh = await api.panel.get({ path: '/config' });
      if (fresh && !fresh.error && configProviders(fresh)) {
        const expanded = expandedProvider;
        addon.config = fresh;
        form = buildForm();
        expandedProvider = expanded && form.providers[expanded]?.enabled ? expanded : null;
        initialSnapshot = snapshot(form);
        await showSuccessToast('components.toasts.settings-save-success');
        return;
      }

      for (const key of providerKeys) {
        const provider = form.providers[key];
        if (provider && provider.secretTouched && provider.clientSecret) {
          provider.hasSecret = true;
          provider.secretTouched = false;
          provider.revealed = false;
          provider.showSecret = false;
          provider.clientSecret = '';
        }
      }
      form = form;
      initialSnapshot = snapshot(form);
      await showSuccessToast('components.toasts.settings-save-success');
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to save config', e);
      await showErrorToast(pluginKey('plugin-settings.save-error'));
    } finally {
      saving = false;
    }
  }
</script>
