import { showToast } from '@panomc/sdk/toasts';

// Success/failure colouring for this plugin's toasts, matching the panel.
//
// `showToast` from @panomc/sdk/toasts is the host panel's ToastContainer `show`, whose
// signature is (text, params, toastComponent, options). Passing `undefined` for
// toastComponent keeps the host's DefaultToast, and `options.variant` maps to Bootstrap's
// text-success / text-danger in the host's Toast component.
//
// These wrappers live here rather than coming from @panomc/sdk/toasts because this plugin
// is pinned to @panomc/sdk 1.0.0-dev.39, which predates the variants. They can be dropped
// for a direct SDK import once the pin moves. On a panel build older than the variant
// support the extra argument is simply ignored and the toast renders neutral, so this
// degrades instead of breaking.
//
// The sibling plugins keep these two wrappers in src/main.js next to the `_` export. This
// one is a separate module on purpose: src/main.js has a large in-flight change here, and
// a separate file keeps this commit mergeable into it without a conflict.

export function showSuccessToast(text, params = {}) {
  return showToast(text, params, undefined, { variant: 'success' });
}

export function showErrorToast(text, params = {}) {
  return showToast(text, params, undefined, { variant: 'danger' });
}
