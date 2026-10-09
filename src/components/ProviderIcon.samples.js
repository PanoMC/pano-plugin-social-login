// Sample data of ProviderIcon for the view catalogue (doc 02 section 7). Pure data: import only view helpers and
// relative .js fixtures.
import { PROVIDER_META } from '../theme/lib/providers.js';

/** @type {string[]} */
export const notApplicable = [];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { meta: PROVIDER_META.discord, colored: true } },
  empty: { props: { meta: null } },
  error: { props: { meta: null } },
  loading: { props: { meta: null } },
};
