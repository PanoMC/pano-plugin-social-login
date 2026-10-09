/**
 * Maps a backend error code (`error.code` of the answer, e.g. `INVALID_FLOW_STATE`) to a human-readable, localized
 * message under the `error.<CODE>` namespace. Unknown codes - and raw provider/exchange messages - fall back to the
 * generic message, so a raw code is never shown. Pass the component's `$_` translate function as `$t`.
 *
 * @param {(key: string, options?: any) => string} $t
 * @param {string | undefined | null} code
 */
export function resolveSocialError($t, code) {
  if (!code) return $t('error.generic');
  const msg = $t(`error.${code}`);
  // svelte-i18n returns the (prefixed) key id when a translation is missing - detect & fall back.
  if (!msg || msg.includes(`error.${code}`)) return $t('error.generic');
  return msg;
}
