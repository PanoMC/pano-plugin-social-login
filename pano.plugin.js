// Plugin-level options of the Pano plugin kit (@panomc/plugin-kit). The namespace is `social-login` (the plugin id minus
// `pano-plugin-`). The views are the files of these folders; ProviderIcon stays in src/components because the panel
// imports it too.
export default {
  viewDirs: ['src/theme/pages', 'src/theme/components', 'src/components'],
  styles: {
    // The provider's brand colour (Google red, Discord blue, ...) is data of PROVIDER_META: it is painted inline on the
    // sign-in buttons, the provider badges and the glyph, so no stylesheet (and no theme) can lose it.
    styleAttrAllow: ['ProviderIcon', 'SocialLoginButtons', 'SocialLinkExistsPage', 'SocialRegisterPage', 'SocialLinksProfileSidebar'],
  },
};
