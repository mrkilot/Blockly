package id.eblo.chat.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import id.eblo.chat.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Bans / timeouts via the Twitch Helix API (IRC chat commands were removed by Twitch).
 * Needs a user token with the scope moderator:manage:banned_users.
 * Results: null = success, "gui.eblochat..." = translation key, anything else = raw error text.
 */
public final class Moderation {
    private static final String SCOPE = "moderator:manage:banned_users";

    private Moderation() {}

    public static CompletableFuture<String> ban(String channel, String targetId, int seconds) {
        return CompletableFuture.supplyAsync(() -> run(channel, targetId, seconds, false), TwitchChat.pool());
    }

    public static CompletableFuture<String> unban(String channel, String targetId) {
        return CompletableFuture.supplyAsync(() -> run(channel, targetId, 0, true), TwitchChat.pool());
    }

    private static String run(String channel, String targetId, int seconds, boolean unban) {
        try {
            String token = Config.d.oauth.trim();
            if (token.startsWith("oauth:")) token = token.substring(6);
            if (token.isEmpty()) return "gui.eblochat.ban.no_oauth";
            if (channel.startsWith("@") || channel.isEmpty()) return "gui.eblochat.ban.no_channel";

            // 1) validate token -> client id, moderator id, scopes
            HttpRequest v = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/validate"))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "OAuth " + token).GET().build();
            HttpResponse<String> vr = TwitchChat.http().send(v, HttpResponse.BodyHandlers.ofString());
            if (vr.statusCode() != 200) return "gui.eblochat.ban.bad_token";
            JsonObject vo = JsonParser.parseString(vr.body()).getAsJsonObject();
            String clientId = vo.has("client_id") ? vo.get("client_id").getAsString() : "";
            String modId = vo.has("user_id") ? vo.get("user_id").getAsString() : "";
            boolean hasScope = false;
            JsonArray scopes = vo.has("scopes") && vo.get("scopes").isJsonArray() ? vo.getAsJsonArray("scopes") : new JsonArray();
            for (JsonElement e : scopes) if (SCOPE.equals(e.getAsString())) hasScope = true;
            if (!hasScope) return "gui.eblochat.ban.no_scope";

            // 2) broadcaster id
            UserLookup.Info b = UserLookup.fetchSync(channel);
            if (!b.ok || b.id.isEmpty()) return "gui.eblochat.ban.no_channel";

            String base = "https://api.twitch.tv/helix/moderation/bans?broadcaster_id=" + enc(b.id) + "&moderator_id=" + enc(modId);
            HttpRequest.Builder rb;
            if (unban) {
                rb = HttpRequest.newBuilder(URI.create(base + "&user_id=" + enc(targetId))).DELETE();
            } else {
                JsonObject data = new JsonObject();
                data.addProperty("user_id", targetId);
                if (seconds > 0) data.addProperty("duration", seconds);
                JsonObject body = new JsonObject();
                body.add("data", data);
                rb = HttpRequest.newBuilder(URI.create(base))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            }
            HttpRequest req = rb.timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + token).header("Client-Id", clientId).build();
            HttpResponse<String> resp = TwitchChat.http().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 204) return null;
            try {
                JsonObject eo = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (eo.has("message")) return eo.get("message").getAsString();
            } catch (Exception ignored) {
            }
            return "HTTP " + resp.statusCode();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** "10m", "2h", "1d", "30" (minutes), "perm"/"0" -> seconds; 0 = permanent, -1 = invalid. Capped at 14 days. */
    public static int parseDuration(String in) {
        String s = in.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        if (s.equals("perm") || s.equals("permanent") || s.equals("навсегда") || s.equals("0")) return 0;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0 || i > 9) return -1;
        long n = Long.parseLong(s.substring(0, i));
        String u = s.substring(i).trim();
        long mult = switch (u) {
            case "s", "с", "сек" -> 1;
            case "", "m", "м", "мин" -> 60;
            case "h", "ч" -> 3600;
            case "d", "д" -> 86400;
            case "w", "н" -> 604800;
            default -> -1;
        };
        if (mult < 0 || n == 0) return -1;
        return (int) Math.min(n * mult, 1209600L);
    }
}
