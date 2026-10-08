<script>
  // Renders a provider's real brand glyph (inline SVG from PROVIDER_META.svg).
  // The glyph inherits the current text colour by default (currentColor) — pass
  // `colored` to paint it in the provider's brand colour instead. Brands flagged
  // `monochrome` (e.g. X, GitHub) always stay on currentColor even when `colored`
  // is requested, since their near-black mark would vanish on dark backgrounds.
  // Falls back to a generic link icon when the provider/meta is unknown.
  let { meta = null, colored = false } = $props();

  let brandColor = $derived(colored && meta?.color && !meta?.monochrome ? meta.color : null);
</script>

{#if meta?.svg}
  <svg
    viewBox="0 0 24 24"
    width="1em"
    height="1em"
    fill="currentColor"
    role="img"
    aria-hidden="true"
    style={brandColor ? `color: ${brandColor}` : null}>
    <path d={meta.svg}></path>
  </svg>
{:else}
  <i class="fas fa-link" style={brandColor ? `color: ${brandColor}` : null}></i>
{/if}
