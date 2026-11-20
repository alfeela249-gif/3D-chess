# 3D Chess — Approved Android UI Design

The Canva concept at https://canva.link/bxuafns4617u34o is the approved visual reference for the Android application.

## Visual direction
- Premium dark chess-room atmosphere.
- Deep walnut / burgundy backgrounds with warm antique-gold accents.
- Cream typography for primary text and gold for secondary labels.
- Serif display typography for the 3D CHESS brand.
- Rounded rectangular controls with restrained gold outlines.
- High-contrast chessboard and pieces remain the focal point.

## Approved screen structure
1. **Home:** 3D CHESS identity; play against medium computer; local Wi-Fi; online play; friends; account.
2. **Game mode:** computer, local Wi-Fi without internet, and online multiplayer.
3. **Game table:** 3D board, turn indicator, player identities, captured pieces, move history, draw/resign/rematch controls.
4. **Friends:** username search, friend requests, friend list, online state, and invite action.
5. **Account:** email/password sign-in, account creation, Google sign-in, and offline/guest access.

## Implementation notes
- The Android home screen now uses the approved 3D CHESS title treatment and Arabic subtitle.
- Existing game, account, friends, LAN, and online actions are retained.
- The Canva presentation is a design reference, not runtime UI code. Screen-by-screen implementation and device testing remain necessary.
- The 2D selector currently reports that 2D mode is not yet implemented; do not present it as a working mode.
- Online synchronization currently uses polling; production hardening and real-device testing remain outstanding.
