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

/**
 * The answer of an API call as an object. The SDK client hands back the raw text when the body is not JSON (a proxy's
 * HTML 502, an empty 500) and does not look at the status, so anything that is not an object counts as a connection
 * error: `{ error: { code: 'NETWORK_ERROR' } }`.
 *
 * @param {unknown} body
 * @returns {any}
 */
export function apiBody(body) {
  if (body && typeof body === 'object' && !Array.isArray(body)) return body;
  return { error: { code: 'NETWORK_ERROR' } };
}

const BAN_CODES = ['LOGIN_USER_IS_BANNED', 'IP_IS_BANNED'];

/**
 * Like `resolveSocialError`, for the whole `error` object of an answer: a ban (`LOGIN_USER_IS_BANNED`, `IP_IS_BANNED`)
 * adds the reason core sends in `details.reason`, when there is one. For `PLUGIN_DENIED_LOGIN` the reason is a full
 * i18n key of the plugin that denied (e.g. `plugins.pano-plugin-auth-guard.errors.…`): pass the unprefixed translate
 * function as `options.raw` to show it. `options.message` replaces the base text (a page's own text for the code).
 *
 * @param {(key: string, options?: any) => string} $t
 * @param {{ code?: string, details?: { reason?: unknown } } | undefined | null} error
 * @param {{ raw?: (key: string, options?: any) => string, message?: string }} [options]
 */
export function resolveSocialErrorBody($t, error, options = {}) {
  const msg = options.message || resolveSocialError($t, error?.code);
  const raw = error?.details?.reason;
  const reason = typeof raw === 'string' ? raw.trim() : '';
  if (!reason) return msg;
  if (error?.code === 'PLUGIN_DENIED_LOGIN') {
    if (!options.raw) return msg;
    try {
      // A missing translation comes back as the key itself.
      const translated = options.raw(reason);
      if (translated && translated !== reason) return translated;
    } catch (e) {
      // fall through
    }
    return msg;
  }
  if (error?.code && BAN_CODES.includes(error.code)) {
    return `${msg} ${$t('error.ban-reason', { values: { reason } })}`;
  }
  return msg;
}
