// The plugin's own texts: `plugins.<pluginId>.<key>` of the site language. A view passes the two stores it imported
// from the packages (a helper cannot import them): `const _ = pluginText(derived, i18n)`, then `$_('callback.loading')`.

export const PLUGIN_ID = 'pano-plugin-social-login';

/**
 * @param {(store: any, fn: (value: any) => any) => any} derived `derived` of `svelte/store`
 * @param {any} i18n `_` of `@panomc/sdk/utils/language`
 * @returns {{ subscribe: Function }} a store of `(key, options) => string`
 */
export function pluginText(derived, i18n) {
  return derived(i18n, ($fn) => (/** @type {string} */ key, /** @type {any} */ options) => $fn(`plugins.${PLUGIN_ID}.${key}`, options));
}
