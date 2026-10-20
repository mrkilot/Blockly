package id.eblo.chat.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import id.eblo.chat.ChatMessage;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.EbloChatClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional "claim bonus" helper: every minute it asks Twitch whether a channel-points bonus chest is waiting in
 * one of your joined channels and, if so, claims it. It only claims - it does not fake watch time.
 *
 * Uses Twitch's UNOFFICIAL web API (gql.twitch.tv) with the browser "auth-token" cookie, which is not a supported
 * public API: it can stop working at any time and automating the site is against Twitch's terms. Off by default.
 */
public final class PointsClaimer {
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"; // Twitch web client
    private static final String HASH_CONTEXT = "1530a003a7d374b0380b79db0be0534f30ff46e61cffa2bc0e2468a909fbc024";
    private static final String HASH_CLAIM = "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0";

    private static final Map<String, String> CHANNEL_IDS = new ConcurrentHashMap<>();
    private static final AtomicInteger CLAIMED = new AtomicInteger();
    private static volatile String status = "";
    private static ScheduledExecutorService exec;

    private PointsClaimer() {}

    public static synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eblochat-points");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                cycle();
            } catch (Throwable t) {
                status = t.getClass().getSimpleName();
                EbloChatClient.LOGGER.debug("points cycle failed", t);
            }
        }, 20, 60, TimeUnit.SECONDS);
    }

    public static int claimed() {
        return CLAIMED.get();
    }

    /** Last status / error text for the settings screen ("" = nothing yet). */
    public static String status() {
        return status;
    }

    private static void cycle() throws Exception {
        Config.Data d = Config.d;
        if (!d.autoClaim) return;
        String token = d.webToken == null ? "" : d.webToken.trim();
        if (token.isEmpty()) {
            status = "no token";
            return;
        }
        if (TwitchChat.state() != TwitchChat.State.ONLINE) return;
        for (String ch : new ArrayList<>(d.channels)) {
            if (!Config.d.autoClaim) return;
            checkChannel(ch, token);
            Thread.sleep(1000 + ThreadLocalRandom.current().nextInt(2000));
        }
    }

    private static void checkChannel(String channel, String token) throws Exception {
        String id = CHANNEL_IDS.get(channel);
        if (id == null) {
            UserLookup.Info info = UserLookup.fetchSync(channel);
            if (!info.ok || info.id.isEmpty()) {
                status = "#" + channel + ": " + (info.error.isEmpty() ? "unknown channel" : info.error);
                return;
            }
            id = info.id;
            CHANNEL_IDS.put(channel, id);
        }

        String ctxBody = "{\"operationName\":\"ChannelPointsContext\",\"variables\":{\"channelLogin\":\"" + channel
                + "\",\"includeGoalTypes\":[\"CREATOR\",\"BOOST\"]},\"extensions\":{\"persistedQuery\":{\"version\":1,"
                + "\"sha256Hash\":\"" + HASH_CONTEXT + "\"}}}";
        JsonObject ctx = post(ctxBody, token);
        if (ctx == null) return;
        JsonObject points = path(ctx, "data", "community", "channel", "self", "communityPoints");
        if (points == null) return;
        JsonObject claim = points.has("availableClaim") && points.get("availableClaim").isJsonObject()
                ? points.getAsJsonObject("availableClaim") : null;
        if (claim == null || !claim.has("id")) {
            status = "ok";
            return;
        }
        String claimId = claim.get("id").getAsString();

        String claimBody = "{\"operationName\":\"ClaimCommunityPoints\",\"variables\":{\"input\":{\"channelID\":\"" + id
                + "\",\"claimID\":\"" + claimId + "\"}},\"extensions\":{\"persistedQuery\":{\"version\":1,"
                + "\"sha256Hash\":\"" + HASH_CLAIM + "\"}}}";
        JsonObject res = post(claimBody, token);
        if (res == null) return;
        long balance = -1;
        JsonObject cp = path(res, "data", "claimCommunityPoints", "currentPoints");
        if (cp != null) balance = cp.getAsLong();
        int n = CLAIMED.incrementAndGet();
        status = "ok";
        String text = "Channel points bonus claimed" + (balance >= 0 ? " (balance " + balance + ")" : "") + " [#" + n + " this session]";
        ChatStore.add(ChatMessage.system(channel, text, true, false, System.currentTimeMillis()));
    }

    /** Returns the parsed JSON, or null (and sets {@link #status}) on HTTP / GraphQL errors. */
    private static JsonObject post(String body, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://gql.twitch.tv/gql"))
                .timeout(Duration.ofSeconds(15))
                .header("Client-Id", CLIENT_ID)
                .header("Authorization", "OAuth " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = TwitchChat.http().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            status = "token rejected (HTTP " + resp.statusCode() + ")";
            return null;
        }
        if (resp.statusCode() != 200) {
            status = "HTTP " + resp.statusCode();
            return null;
        }
        JsonElement root = JsonParser.parseString(resp.body());
        JsonObject o = root.isJsonArray() && !root.getAsJsonArray().isEmpty()
                ? root.getAsJsonArray().get(0).getAsJsonObject()
                : root.isJsonObject() ? root.getAsJsonObject() : null;
        if (o == null) {
            status = "bad response";
            return null;
        }
        if (o.has("errors") && o.get("errors").isJsonArray()) {
            JsonArray errs = o.getAsJsonArray("errors");
            if (!errs.isEmpty()) {
                JsonObject e = errs.get(0).getAsJsonObject();
                status = e.has("message") ? e.get("message").getAsString() : "GraphQL error";
                return null;
            }
        }
        return o;
    }

    private static JsonObject path(JsonObject root, String... keys) {
        JsonElement cur = root;
        for (String k : keys) {
            if (!cur.isJsonObject() || !cur.getAsJsonObject().has(k)) return null;
            cur = cur.getAsJsonObject().get(k);
        }
        return cur.isJsonObject() ? cur.getAsJsonObject() : null;
    }
}
