# 3D Chess — Approved Chess Piece Art Direction

## Approved visual direction
The user approved the supplied fantasy-medieval sculptural chess set reference on 2026-11-20. This is the canonical art direction for the in-game pieces.

## Team materials
- White side: aged silver / dark steel with bright worn-metal highlights.
- Black side: antique gold / bronze with deep engraved shadows.
- Both teams use the same sculptural silhouettes and ornamental detailing; only the material palette differs.
- Each piece sits on a substantial round, tiered pedestal with engraved bands and a central heraldic medallion.
- Overall presentation: premium museum-quality fantasy chess, realistic sculpted forms, readable silhouettes at board scale.

## Piece identity requirements
- King: crowned, mature royal figure; robe or armor; prominent cross-topped or heraldic crown.
- Queen: regal crowned figure with flowing robe and distinct feminine silhouette.
- Bishop: armored cleric / bishop figure with tall mitre and ceremonial staff or shield.
- Knight: rearing horse and mounted-knight silhouette. Must remain visibly distinct from the bishop.
- Rook: fortified stone tower with battlements, masonry courses, and an arched entrance.
- Pawn: compact armored foot soldier with helmet and shield; visibly shorter and simpler than all other pieces.

## Production asset requirements
Create twelve independent, game-ready 3D assets (six piece types × two team materials), not a flat sprite sheet:
- Preferred interchange format: GLB/glTF.
- Origin centered on the pedestal; consistent base diameter and scale across all twelve pieces.
- Upright orientation, real depth, clean topology, baked or PBR-ready materials, sensible polygon budgets for Android.
- Separate meshes/material slots where useful; no baked background, labels, captions, or presentation stands beyond each piece's own pedestal.
- Silhouettes and details must remain legible from the game's elevated three-quarter camera.
- Use physically based metal/roughness materials and restrained highlights to avoid washed-out gold or silver.

## Integration acceptance checklist
- Replace the procedural placeholder geometry in `ChessBoardView.kt` with the twelve GLB assets.
- Load assets once and reuse meshes/materials for all board instances.
- Preserve legal move selection, highlights, board rotation, and move animations.
- Fit every model to the same square footprint; prevent collisions with neighboring pieces.
- Verify both sides and all six piece types on an Android device at the actual gameplay camera angle.
- Keep the approved gold-versus-silver visual identity consistent in menus and in-game UI.

## Status
Art direction is approved. The existing Android renderer still uses procedural geometry; the GLB assets have not yet been created or integrated. Do not describe the approved reference sheet itself as an installed 3D model pack.
