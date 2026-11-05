# Blockly (Fabric, Minecraft 26.1.2)

Twitch chat inside Minecraft with Chatterino-style features (inspired by https://chat.eblo.id — Chatterino+).

Requirements: Minecraft 26.1.2, Fabric Loader >= 0.19.2, Fabric API 0.155.0+26.1.2, Java 25.

## Build
    gradle wrapper --gradle-version 9.1.0   # once
    ./gradlew build                          # -> build/libs/eblochat-1.0.0.jar

## Use
* Press **O** (rebindable) to open the chat window.
* Settings: channels, your nick + OAuth token (only needed to *send* messages and to ban users), highlight words, ignored users, blocked terms, toggles.

### Getting an OAuth token

You need a **user OAuth token** so the mod can send chat messages and moderate on your behalf.

**Recommended — via twitchtokengenerator.com:**
1. Open https://twitchtokengenerator.com/
2. Select **User Access Token** → Generate User Access Token.
3. Under **Scopes**, select: `chat:read`, `chat:edit`, `moderator:manage:banned_users`.
4. Click **Connect Twitch**, log in and authorize. Copy the **Access Token**.
5. In Blockly settings, paste it into **OAuth token**. Also set your Twitch **nick**.

**Alternative — via dev.twitch.tv:**
1. Register a Twitch application at https://dev.twitch.tv/console and copy its **Client ID**.
2. In Blockly settings, paste the Client ID into **Twitch Client ID** (for reference).
3. Click **Token instruction** to see a reminder of the required scopes.
4. Get the token via twitchtokengenerator.com and paste it into **OAuth token**.

> ⚠️ Never share your OAuth token or Client Secret with anyone. They give full access to your Twitch account.
* Click a name = user card; right-click a name = insert @mention; right-click a message = copy; Tab = next tab; PgUp/PgDn/wheel = scroll.
* Commands in the input line: /join /part /clear /user /ignore /unignore /highlight /mentions /reconnect, and /me.
