<p align="center"><img src="icon.png" width="128" alt="Blockly"></p>

# Blockly

Twitch chat inside Minecraft. A client-side Fabric mod for **Minecraft 26.1.2** with Chatterino-style features.

**Requirements:** Minecraft 26.1.2, Fabric Loader >= 0.19.2, Fabric API 0.155.0+26.1.2, Java 25.
Mod Menu is optional (adds a settings button).

## Features
- Multi-channel tabs, badges, message history, mentions, user cards, moderation events
- **HUD chat window** on top of the game: 8 position presets, width/height sliders, font size 75–200 %, background opacity, fade-out of old messages
- **Emotes:** 7TV, BetterTTV, FrankerFaceZ and official Twitch emotes (animated GIFs are shown as a still frame)
- Optional channel-points auto-claim (off by default, needs a web token)
- OAuth and web tokens are masked in the UI, with an eye button to reveal them

## Build
```
gradle wrapper --gradle-version 9.7.0   # once
./gradlew build                          # -> build/libs/blockly-<version>.jar
```

## Use
- **O** opens the chat window, **H** opens the HUD settings (both rebindable).
- Settings: channels, nick + OAuth token (only needed to *send* messages), highlight words, ignored users, blocked terms, emotes, channel points.
- Click a name = user card; right-click a name = insert @mention; right-click a message = copy; Tab = next tab; PgUp/PgDn/wheel = scroll.
- Commands: `/join /part /clear /user /ignore /unignore /highlight /mentions /reconnect`, `/me`.

## Network and privacy
The mod connects to Twitch chat (IRC over WebSocket), the Twitch/IVR APIs for user info, and the 7TV, BetterTTV and FrankerFaceZ APIs for emotes.
Tokens are stored locally in `config/eblochat.json` and sent only to Twitch. Never share that file.
The mod is unofficial and not affiliated with Twitch.

## AI disclosure
Parts of the code were written with the help of an AI assistant (Claude) and reviewed and tested by the author.

## License
MIT
