<script>
  // Renders a provider's real brand glyph (inline SVG from PROVIDER_META.svg).
  // The glyph inherits the current text colour by default (currentColor) — pass
  // `colored` to paint it in the provider's brand colour instead. Falls back to a
  // generic link icon when the provider/meta is unknown.
  let { meta = null, colored = false } = $props();
</script>

{#if meta?.svg}
  <svg
    viewBox="0 0 24 24"
    width="1em"
    height="1em"
    fill="currentColor"
    role="img"
    aria-hidden="true"
    style={colored ? `color: ${meta.color}` : null}>
    <path d={meta.svg}></path>
  </svg>
{:else}
  <i class="fas fa-link" style={colored && meta?.color ? `color: ${meta.color}` : null}></i>
{/if}
