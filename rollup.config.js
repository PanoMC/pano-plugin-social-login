import { panoPlugin } from '@panomc/plugin-kit/rollup';

// The build is the kit preset (@panomc/plugin-kit): server and client bundles, the entry facade, the svelte version
// guard against the SDK pin, the plugin-scoped api client (`@panomc/sdk/plugin-api`), PANO_SDK_DIR, DEV and
// BUNDLE_SDK all live there. Plugin-level options (the view folders) are in pano.plugin.js.
export default panoPlugin();
