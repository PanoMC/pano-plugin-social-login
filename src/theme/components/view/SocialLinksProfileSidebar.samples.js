// Sample data of SocialLinksProfileSidebar for the view catalogue (doc 02 section 7). Pure data: import only view helpers and
// relative .js fixtures.
/** @type {string[]} */
export const notApplicable = [];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      data: {
        ownProfile: true,
        links: [
          { provider: 'discord', displayName: 'Steve#1234', email: 'steve@example.com', avatarUrl: null, linkedAt: 1760000000000 },
          { provider: 'github', displayName: 'steve', email: null, avatarUrl: null, linkedAt: 1760000000000 },
        ],
      },
    },
  },
  empty: { props: { data: { ownProfile: true, links: [] } } },
  error: { props: { data: { ownProfile: false, links: [] } } },
  loading: { props: { data: { ownProfile: false, links: [] } } },
};
