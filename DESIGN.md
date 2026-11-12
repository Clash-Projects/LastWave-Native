# DESIGN.md — LastWave UI

For any agent doing UI work: new screens, restyles, components, motion.
Code rules from [AGENTS.md](AGENTS.md) still apply (fewest lines, rewrite don't patch, delete what you replace).

## 1. Classify the design prompt first

| Prompt | Deliver |
|---|---|
| "Make it look like X" (Apple Music, SimpMusic, …) | Read X's real layout/source, match its structure and hierarchy, keep our tokens. |
| "Polish / clean up this screen" | Same content, fewer elements, tighter spacing, one clear focal point. |
| "Add a control / button" | Reuse the nearest existing component; place it where the user's thumb already is. |
| "Fix layout bug" | Fix the measurement cause (insets, weights, constraints), not the symptom (magic offsets). |

## 2. Principles: polished and minimal

- **One focal point per screen.** Everything else is quieter (smaller, lower contrast).
- **Remove before adding.** If a screen needs a new element, first look for one to drop.
  No decorative dividers, badges, borders or shadows that carry no meaning.
- **Hierarchy through type and space, not boxes.** Prefer spacing and weight over
  cards and outlines. A card on a flat background is just a border.
- **Consistent rhythm.** Spacing from the 4 dp grid (4, 8, 12, 16, 24, 32). Same
  corner radius for the same kind of surface.
- **Content first.** Artwork and titles lead; controls recede until needed.
- **Every state designed:** loading, empty, error, long text (marquee or ellipsis),
  RTL, large font scale, tablet/landscape.

## 3. Use what exists

- Colour from `MaterialTheme.colorScheme` (and the artwork-derived palette in the
  player). No new hex literals for UI roles.
- Type from `MaterialTheme.typography`; change size/weight with `.copy()` only when a
  role doesn't exist.
- Glass: `LiquidGlassSurface` / `liquidGlassChrome` / `LiquidGlassPreset`. Glass is
  for chrome that floats over content (player controls, nav, headers), never a skin
  for content. It needs API 31+ and falls back to a tinted surface — check both.
- Motion: `ExpressiveMotion` springs. Animate state changes users cause (press,
  toggle, expand); never animate on a loop for decoration.
- Icons: Material icons, filled for "on", outlined for "off" of the same pair.

## 4. Interaction

- Touch targets ≥ 48 dp even when the glyph is smaller.
- Feedback on every tap: pressed scale, toggle state, or a short toast — not all three.
- Destructive actions confirm once; nothing else asks "are you sure".
- A control that can't act right now is hidden, not shown disabled, unless its
  existence teaches something (e.g. the YouTube-like button only appears when signed in).

## 5. Before you call it done

- Look at it in light and dark, portrait and landscape, on a small phone and a tablet.
- Check content descriptions on icon-only buttons.
- Diff check: did the change add more lines than the feature needs? Trim it.
