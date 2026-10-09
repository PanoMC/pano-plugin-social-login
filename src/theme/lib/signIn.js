import { getHost } from './host.js';

/**
 * Where a site path lives on this host. On a SERVERS install no theme runs and the panel serves
 * the sign-in pages (the platform moves `/social-login/...` and `/login` under `/panel`), so every
 * link and redirect between them has to stay there.
 *
 * @param {string} path
 */
export function sitePath(path) {
  if (!getHost()?.isPanel) return path;
  return path === '/' ? '/panel' : `/panel${path}`;
}

/**
 * Finishes a social sign-in once the backend has set the cookies. The panel checks panel access
 * and opens the dashboard itself (`pano.ui.auth.login.complete`). The theme goes home after `delay` ms with a
 * full page load: the server then renders the page for the signed-in visitor from the cookies, so a view needs
 * no hold on the theme's session store (rule V5). Resolves to `'ok'` or `'NO_PANEL_ACCESS'`.
 *
 * @param {string} csrfToken
 * @param {number} [delay]
 * @returns {Promise<string>}
 */
export async function finishLogin(csrfToken, delay = 1200) {
  const pano = getHost();

  if (pano?.isPanel && pano.ui.auth?.login?.complete) {
    return await pano.ui.auth.login.complete(csrfToken);
  }

  setTimeout(() => globalThis.location?.assign(`${pano?.base ?? ''}/`), delay);

  return 'ok';
}
