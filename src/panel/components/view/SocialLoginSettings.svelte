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
              <i class="{meta?.icon || 'fas fa-link'}" style="color: {meta?.color || 'inherit'};"></i>
              <span class="fw-bold">{provider.displayName}</span>
              <div class="form-check form-switch ms-auto mb-0">
                <input
                  class="form-check-input"
                  type="checkbox"
                  id="{key}Enabled"
                  bind:checked={provider.enabled} />
              </div>
            </div>

            {#if provider.enabled}
              <div class="animate__animated animate__fadeIn mt-3">
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
                      bind:value={provider.clientId} />
                  </div>
                </div>

                <!-- Client Secret -->
                <div class="row mb-2">
                  <label class="col-md-4 col-form-label" for="{key}ClientSecret">
                    {$_('plugin-settings.client-secret')}
                  </label>
                  <div class="col-md-8">
                    {#if provider.hasSecret && !provider.secretTouched}
                      <div class="d-flex align-items-center gap-2">
                        <input type="password" class="form-control" value="••••••••••••" disabled />
                        <button
                          class="btn btn-outline-primary btn-sm"
                          type="button"
                          aria-label="Edit secret"
                          on:click={() => editSecret(key)}>
                          <i class="fas fa-edit"></i>
                        </button>
                      </div>
                    {:else}
                      <div class="input-group">
                        <input
                          type="password"
                          class="form-control"
                          id="{key}ClientSecret"
                          placeholder={provider.hasSecret
                            ? $_('plugin-settings.client-secret-new-placeholder')
                            : ''}
                          bind:value={provider.clientSecret} />
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
  import ApiUtil from '@panomc/sdk/utils/api';
  import { showToast } from '@panomc/sdk/toasts';

  export let addon;

  const providerKeys = ['google', 'discord', 'facebook', 'twitter'];
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
    form.providers[key].clientSecret = '';
    form = form;
  }

  function cancelEditSecret(key) {
    form.providers[key].secretTouched = false;
    form.providers[key].clientSecret = '';
    form = form;
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
