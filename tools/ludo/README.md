# Ludo Club

Standalone offline Android Ludo game for 2–6 local seats.

## Current gameplay

- Configure 2–6 seats and choose how many are human-controlled; remaining seats are local bots.
- Four pieces per player, six to leave the yard, safe squares, captures, bonus turns after a six or capture, three-six turn forfeits, and exact rolls to finish.
- Six-seat circular board with six player colours.
- Kenney CC0 dice artwork, four bundled OGG game cues, and optional Android vibration.
- No INTERNET permission, sign-in, analytics, remote service, or online play.

## Implementation

- Kotlin + Jetpack Compose, following Tool-Hubs' shared ToolHubsTheme.
- Rules and bot selection live in LudoEngine.kt; screen and board rendering live in MainActivity.kt.
- Kenney dice sprites and OGG cues are bundled in app resources and loaded locally with Compose and SoundPool.

## Asset research

The six-seat board and glossy player pieces are custom Compose Canvas artwork. The dice faces and game sound effects use the bundled Kenney Board Game Pack assets under CC0:

- [Kenney Board Game Pack](https://kenney.nl/assets/boardgame-pack) — CC0, dice/pieces and OGG sound effects. [OpenGameArt mirror and licence details](https://opengameart.org/content/boardgame-pack).

The upstream licence is included at src/main/res/raw/kenney_boardgame_license.txt. The bundled files are six white dice faces and four OGG effects (dice shuffle, dice throw, piece movement and capture).
