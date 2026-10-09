// The Pano object the plugin entry (main.js) received in onLoad, for the views. A view cannot import the entry
// (view imports rule V1), and a helper cannot import packages, so the entry hands the object over here.
// Empty in a copy of this file that a theme ships beside an overridden view: the view then behaves as it does in a theme.

/** @type {any} */
let current = null;

/** @param {any} pano */
export function setHost(pano) {
  current = pano;
}

/** @returns {any} the Pano object of the entry, or null before onLoad ran */
export function getHost() {
  return current;
}
