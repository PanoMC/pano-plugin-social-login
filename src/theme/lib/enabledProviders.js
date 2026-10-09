// Provider keys enabled on the server, filled on the login and register pages. A hand-written store (a view
// helper cannot import `svelte/store`): `subscribe` / `set` / `update` are all the views and `get()` need.

/** @type {string[]} */
let value = [];
/** @type {Set<(value: string[]) => void>} */
const subscribers = new Set();

export const enabledProviders = {
  /** @param {(value: string[]) => void} run */
  subscribe(run) {
    subscribers.add(run);
    run(value);

    return () => {
      subscribers.delete(run);
    };
  },

  /** @param {string[]} next */
  set(next) {
    value = next;

    for (const run of [...subscribers]) run(value);
  },

  /** @param {(current: string[]) => string[]} fn */
  update(fn) {
    enabledProviders.set(fn(value));
  },
};
