# 3D Chess — Approved Chess Piece Art Direction

## Canonical approved model
The user approved the **Classic Staunton 3D Chess Set** on 2026-11-21. This supersedes the earlier fantasy-medieval concept and is the only approved direction for the current game.

- Saved Blendstra project: `c0999f0c-787a-42a3-b97e-8654892aba1e`
- Exact saved version: `version_5e2b0678e7108cbda3671c80385bd19ebde36d5a6aea934c1a13ca71fdee5fa8`
- [Open the approved model](https://blendstra.com/studio?workspace=wsp_adb20fdc3deb8b498a5627d0c41c91052953abee&project=c0999f0c-787a-42a3-b97e-8654892aba1e&version=version_5e2b0678e7108cbda3671c80385bd19ebde36d5a6aea934c1a13ca71fdee5fa8)
- Saved model inspection reports 343 scene objects and a full 32-piece set with board.
- Visual direction: polished ebony black versus warm ivory, walnut board and restrained brass accents; recognizable Staunton silhouettes including horse-head knight, battlement rook, bishop mitre, queen crown and king cross.

## Integration requirements
- Use the approved model/version as the visual source of truth; do not substitute a new design.
- Replace the procedural piece geometry in `ChessBoardView.kt` with optimized, reusable game meshes derived from the approved model.
- Preserve legal move selection, square highlights, board rotation, touch gestures and move animations.
- Fit every piece to its square footprint and preserve the correct side/orientation.
- Keep Android and web gameplay camera framing, board proportions, materials and interaction behavior consistent.
- Optimize geometry/materials for Android GPU memory and frame rate; retain a detailed master separately from a lightweight runtime asset.
- Verify all 32 starting pieces at the actual gameplay camera angle and test on physical Android devices.

## Current implementation status
- The approved complete Staunton model is saved and viewable in Blendstra.
- Android currently draws procedural Staunton-inspired geometry in OpenGL ES 2.0; it is not yet using the saved model's meshes.
- The browser page at `docs/index.html` is a standalone Three.js visual/camera preview, not a complete browser chess game.
- The exported model must be transferred into the repository and converted/optimized for the Android renderer before exact mesh integration can be claimed.
- Do not describe the saved Blendstra model as already installed in the Android APK or as a delivered 4K runtime asset.
