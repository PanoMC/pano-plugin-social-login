import { writable } from 'svelte/store';

// The edit-player modal and the player-detail sidebar are separate hook components. After the
// modal unlinks accounts it sets this to `{ username, at }`, and the sidebar refetches that
// player's links instead of keeping the stale `socialPreload` from the layout load.
export const socialLinksChanged = writable(null);
