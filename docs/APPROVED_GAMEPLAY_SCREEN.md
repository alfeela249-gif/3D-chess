# Approved Gameplay Screen Art Direction

Approved by the user on 2026-11-20.

The cathedral-hall gameplay screen concept is approved as the visual direction for the in-game board.

## Visual specification
- Premium dark marble chessboard with gold trim and ornate carved table details.
- Fantasy-medieval sculptural pieces: antique gold/bronze versus aged silver/dark steel.
- Elevated three-quarter camera with a clear view of all 64 squares and piece silhouettes.
- Atmospheric stone hall, warm torchlight, banners and restrained cinematic depth.
- Player panels with names/avatars and chess clocks at the upper corners.
- Gameplay actions: undo, reset, settings; play modes/actions: Wi-Fi, friends, versus computer.
- Turn indicator and game title in the HUD.
- Keep the board and pieces visually dominant; HUD must remain legible on Android phone screens.

## Implementation requirements
- Treat the generated concept as a design reference, not as a functional screenshot or shipped UI.
- Implement the HUD as native interactive UI, not as a flattened image.
- Render board and pieces as real 3D geometry/assets; do not use the concept image as a board texture.
- Preserve the already-approved gold/silver piece art direction in `docs/APPROVED_PIECE_ART_DIRECTION.md`.
- Adapt layout responsively for portrait and landscape Android screens; prioritize a usable gameplay board.
- Do not claim the screen is implemented until committed app code and an Android build verify it.
