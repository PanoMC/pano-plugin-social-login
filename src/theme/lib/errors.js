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

const BAN_CODES = ['LOGIN_USER_IS_BANNED', 'IP_IS_BANNED'];

/**
 * Like `resolveSocialError`, for the whole `error` object of an answer: a ban (`LOGIN_USER_IS_BANNED`, `IP_IS_BANNED`)
 * adds the reason core sends in `details.reason`, when there is one.
 *
 * @param {(key: string, options?: any) => string} $t
 * @param {{ code?: string, details?: { reason?: unknown } } | undefined | null} error
 */
export function resolveSocialErrorBody($t, error) {
  const msg = resolveSocialError($t, error?.code);
  const reason = error?.details?.reason;
  if (error?.code && BAN_CODES.includes(error.code) && typeof reason === 'string' && reason.trim()) {
    return `${msg} ${$t('error.ban-reason', { values: { reason: reason.trim() } })}`;
  }
  return msg;
}
