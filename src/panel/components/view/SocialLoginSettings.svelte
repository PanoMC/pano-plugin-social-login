{#if addon?.id === 'pano-plugin-social-login'}
  <div class="card">
    <div class="card-header">
      {$_('plugin-settings.title')}
    </div>
    <div class="card-body">
      <div class="alert alert-info mb-4">
        <h6 class="alert-heading mb-2">
          <i class="fas fa-info-circle me-1"></i>
          {$_('plugin-settings.setup-guide-title')}
        </h6>
        <p class="mb-0 small">{$_('plugin-settings.setup-guide-desc')}</p>
      </div>

      <!-- Redirect base URL -->
      <div class="row mb-4">
        <label class="col-md-6 col-form-label" for="redirectBaseUrl">
          <span class="d-block">{$_('plugin-settings.redirect-base-url')}</span>
          <small>{$_('plugin-settings.redirect-base-url-desc')}</small>
        </label>
        <div class="col-md-6">
          <input
            type="text"
            class="form-control"
            id="redirectBaseUrl"
            placeholder={originPlaceholder}
            bind:value={form.redirectBaseUrl} />
        </div>
      </div>

      <!-- Per-provider configuration -->
      {#each providerKeys as key (key)}
        {@const provider = form.providers[key]}
        {@const meta = PROVIDER_META[key]}
        {#if provider}
          <div class="border rounded p-3 mb-3">
            <div class="d-flex align-items-center gap-2 mb-1">
              <ProviderIcon {meta} colored />
              <span class="fw-bold">{provider.displayName}</span>
              <div class="form-check form-switch ms-auto mb-0">
                <input
                  class="form-check-input"
                  type="checkbox"
                  id="{key}Enabled"
                  bind:checked={form.providers[key].enabled} />
              </div>
            </div>

            {#if provider.enabled}
              <div class="animate__animated animate__fadeIn mt-3">
                <!-- Per-provider setup guide -->
                <div class="alert alert-secondary small mb-3">
                  <div class="fw-bold mb-2">
                    <i class="fas fa-book me-1"></i>{$_('plugin-settings.guide.heading')}
                  </div>
                  <div class="mb-1">
                    {$_('plugin-settings.guide.intro', { values: { provider: provider.displayName } })}
                  </div>
                  <ol class="mb-2 ps-3">
                    {#each $_(`plugin-settings.guide-notes.${key}`).split('\n') as step, i (i)}
                      <li>{step}</li>
                    {/each}
                  </ol>
                  {#if meta?.scopes}
                    <div class="mb-2">
                      <span class="fw-semibold">{$_('plugin-settings.guide.scopes-label')}:</span>
                      <code>{meta.scopes}</code>
                    </div>
                  {/if}
                  <div class="d-flex flex-wrap gap-2">
                    {#if meta?.consoleUrl}
                      <a
                        class="btn btn-sm btn-outline-primary"
                        href={meta.consoleUrl}
                        target="_blank"
                        rel="noopener noreferrer">
                        <i class="fas fa-arrow-up-right-from-square me-1"></i>{$_(
                          'plugin-settings.guide.open-console',
                        )}
                      </a>
                    {/if}
                    {#if meta?.docsUrl}
                      <a
                        class="btn btn-sm btn-outline-secondary"
                        href={meta.docsUrl}
                        target="_blank"
                        rel="noopener noreferrer">
                        <i class="fas fa-book me-1"></i>{$_('plugin-settings.guide.open-docs')}
                      </a>
                    {/if}
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
                        <input type="password" class="form-control" value="••••••••••••" disabled />
                        <button
                          class="btn btn-outline-secondary btn-sm"
                          type="button"
                          aria-label="Reveal secret"
                          on:click={() => openRevealModal(key)}>
                          <i class="fas fa-eye"></i>
                        </button>
                        <button
                          class="btn btn-outline-primary btn-sm"
                          type="button"
                          aria-label="Edit secret"
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
                          class="btn btn-outline-secondary"
                          type="button"
                          aria-label="Toggle secret visibility"
                          on:click={() => toggleShowSecret(key)}>
                          <i class="fas {provider.showSecret ? 'fa-eye-slash' : 'fa-eye'}"></i>
                        </button>
                        {#if provider.hasSecret}
                          <button
                            class="btn btn-outline-danger"
                            type="button"
                            aria-label="Cancel edit"
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
                        class="btn btn-outline-secondary"
                        type="button"
                        aria-label="Copy redirect URI"
                        on:click={() => copyToClipboard(provider.redirectUri)}>
                        <i class="fas fa-copy"></i>
                      </button>
                    </div>
                    <small class="d-block mt-1 text-muted">
                      {$_('plugin-settings.redirect-uri-desc')}
                    </small>
                  </div>
                </div>
              </div>
            {/if}
          </div>
        {/if}
      {/each}

      <!-- Reveal secret modal (password-gated) -->
      <div aria-hidden="true" class="modal fade" bind:this={revealModalElement} role="dialog" tabindex="-1">
        <div class="modal-dialog modal-dialog-centered" role="dialog">
          <div class="modal-content">
            <form on:submit|preventDefault={revealSecret}>
              <div class="modal-body text-center">
                <div class="pb-3">
                  <i class="fas fa-lock fa-3x d-block m-auto text-warning"></i>
                </div>
                {$_('plugin-settings.reveal-desc')}
                <input
                  class="form-control mt-3"
                  placeholder={$_('plugin-settings.reveal-password-placeholder')}
                  type="password"
                  bind:value={revealPassword}
                  bind:this={revealPasswordInput}
                  class:border-danger={revealError} />
                {#if revealError}
                  <small class="text-danger d-block mt-2">{$_('plugin-settings.reveal-error')}</small>
                {/if}
              </div>
              <div class="modal-footer flex-nowrap">
                <button
                  class="btn btn-link col-6 m-0"
                  type="button"
                  on:click={closeRevealModal}
                  disabled={revealLoading}>
                  {$_('plugin-settings.reveal-cancel')}
                </button>
                <button
                  class="btn btn-warning col-6 m-0"
                  type="submit"
                  disabled={!revealPassword || revealLoading}>
                  {$_('plugin-settings.reveal-confirm')}
                  {#if revealLoading}
                    <i class="fas fa-sync fa-spin ms-2"></i>
                  {/if}
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
          <small>{$_('plugin-settings.debug-logging-desc')}</small>
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
    </div>
  </div>
{/if}

<script>
  import { _, PROVIDER_META } from '../../../main.js';
  import ProviderIcon from '../../../components/ProviderIcon.svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { showToast } from '@panomc/sdk/toasts';

  export let addon;

  const providerKeys = ['google', 'discord', 'facebook', 'twitter', 'github', 'twitch', 'instagram'];
  const originPlaceholder = typeof window !== 'undefined' ? window.location.origin : '';

  function buildForm() {
    const providers = {};
    (addon?.config?.providers || []).forEach((p) => {
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
      const res = await ApiUtil.post({
        path: '/api/panel/social-login/config/reveal-secret',
        body: { provider: revealKey, password: revealPassword },
      });

      if (res.error) {
        revealError = true;
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
      revealError = true;
    } finally {
      revealLoading = false;
    }
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

      await ApiUtil.put({ path: '/api/panel/social-login/config', body });

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
      await showToast('components.toasts.settings-save-success');
    } catch (e) {
      console.error('[pano-plugin-social-login] Failed to save config', e);
      await showToast('components.toasts.settings-save-error');
    } finally {
      saving = false;
    }
  }
</script>
