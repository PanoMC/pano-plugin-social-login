// Sample data of SocialAccountLinks for the view catalogue (doc 02 section 7). Pure data: import only view helpers and
// relative .js fixtures.
/** @type {string[]} */
export const notApplicable = [];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      data: {
        links: [
          { provider: 'discord', displayName: 'Steve#1234', email: 'steve@example.com', linkedAt: 1760000000000 },
        ],
        enabledProviders: ['discord', 'github', 'google'],
      },
    },
  },
  empty: { props: { data: { links: [], enabledProviders: ['discord', 'github'] } } },
  error: { props: { data: { links: [], enabledProviders: [] } } },
  loading: { props: { data: { links: [], enabledProviders: [] } } },
};
