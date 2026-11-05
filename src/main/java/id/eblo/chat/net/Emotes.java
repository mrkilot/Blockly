package id.eblo.chat.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import id.eblo.chat.ChatMessage;
import id.eblo.chat.EbloChatClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emote catalogue and image cache: official Twitch emotes (from the IRC "emotes" tag) plus 7TV, BetterTTV and
 * FrankerFaceZ (global + per channel). Images are downloaded lazily, scaled to a small texture and registered as
 * dynamic textures. Animated GIF emotes are decoded frame by frame. WebP is not supported by Java, so only PNG/GIF/JPEG
 * sources are used (7TV offers PNG/GIF, FFZ and Twitch serve PNG).
 */
public final class Emotes {
    /** Emote texture; animated emotes hold one texture per frame and pick the current one by wall-clock time. */
    public static final class Img {
        private final Identifier[] ids;
        private final long[] ends;   // cumulative end time of each frame, ms
        private final int w, h;

        Img(Identifier[] ids, long[] ends, int w, int h) {
            this.ids = ids;
            this.ends = ends;
            this.w = w;
            this.h = h;
        }

        public int w() { return w; }

        public int h() { return h; }

        public Identifier id() {
            if (ids.length == 1) return ids[0];
            long total = ends[ends.length - 1];
            if (total <= 0) return ids[0];
            long t = System.currentTimeMillis() % total;
            for (int i = 0; i < ends.length; i++) if (t < ends[i]) return ids[i];
            return ids[0];
        }
    }

    public static final class Emote {
        public final String code;
        private final String[] urls;
        private volatile Img img;
        private volatile boolean started, failed;

        Emote(String code, String... urls) {
            this.code = code;
            this.urls = urls;
        }
    }

    private static final class Chan {
        final Map<String, Emote> seventv = new ConcurrentHashMap<>();
        final Map<String, Emote> bttv = new ConcurrentHashMap<>();
        final Map<String, Emote> ffz = new ConcurrentHashMap<>();
    }

    private static final Map<String, Emote> G_7TV = new ConcurrentHashMap<>();
    private static final Map<String, Emote> G_BTTV = new ConcurrentHashMap<>();
    private static final Map<String, Emote> G_FFZ = new ConcurrentHashMap<>();
    private static final Map<String, Chan> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<String, Emote> TWITCH = new ConcurrentHashMap<>();
    private static final AtomicInteger VERSION = new AtomicInteger();
    private static final AtomicInteger TEX = new AtomicInteger();
    private static volatile boolean globalsStarted;

    private static final int TEX_H = 32;      // all emotes are normalised to this texture height
    private static final int TEX_MAX_W = 128;

    private Emotes() {}

    /** Bumped whenever an image finishes loading (layouts that depend on emote widths are refreshed). */
    public static int version() {
        return VERSION.get();
    }

    // ---- lookup --------------------------------------------------------------------------------------------------

    /** Finds the emote a word stands for in this message, or null. */
    public static Emote resolve(ChatMessage m, String word) {
        if (word.isEmpty()) return null;
        String tid = m.twitchEmotes.get(word);
        if (tid != null) {
            // "default" serves the animated GIF when the Twitch emote has one, otherwise the static PNG.
            return TWITCH.computeIfAbsent(tid, i -> new Emote(word,
                    "https://static-cdn.jtvnw.net/emoticons/v2/" + i + "/default/dark/2.0",
                    "https://static-cdn.jtvnw.net/emoticons/v2/" + i + "/default/light/2.0",
                    "https://static-cdn.jtvnw.net/emoticons/v2/" + i + "/static/dark/2.0"));
        }
        Chan c = CHANNELS.get(m.channel);
        if (c != null) {
            Emote e = c.seventv.get(word);
            if (e == null) e = c.bttv.get(word);
            if (e == null) e = c.ffz.get(word);
            if (e != null) return e;
        }
        Emote e = G_7TV.get(word);
        if (e == null) e = G_BTTV.get(word);
        if (e == null) e = G_FFZ.get(word);
        return e;
    }

    /** Texture of the emote if it is ready; otherwise starts the download and returns null. */
    public static Img image(Emote e) {
        Img i = e.img;
        if (i != null) return i;
        if (!e.started && !e.failed) {
            e.started = true;
            TwitchChat.pool().execute(() -> loadImage(e));
        }
        return null;
    }

    /** Parses the Twitch "emotes" tag ("25:0-4,12-16/1902:6-10") into code -> emote id. */
    public static Map<String, String> parseTwitchTag(String tag, String text, boolean action) {
        if (tag == null || tag.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        try {
            for (String part : tag.split("/")) {
                int colon = part.indexOf(':');
                if (colon < 0) continue;
                String id = part.substring(0, colon);
                String first = part.substring(colon + 1).split(",")[0];
                int dash = first.indexOf('-');
                int start = Integer.parseInt(first.substring(0, dash));
                int end = Integer.parseInt(first.substring(dash + 1));
                String code = slice(text, start, end);
                if (code == null && action) code = slice(text, start - 8, end - 8); // positions may include "ACTION "
                if (code != null) out.put(code, id);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String slice(String text, int start, int end) {
        if (start < 0 || end < start || end >= text.length()) return null;
        String s = text.substring(start, end + 1);
        return s.isBlank() || s.indexOf(' ') >= 0 ? null : s;
    }

    // ---- catalogue loading ---------------------------------------------------------------------------------------

    public static void init() {
        if (globalsStarted) return;
        globalsStarted = true;
        TwitchChat.pool().execute(() -> run("7TV global", () -> load7tvSet(getStringRetry("https://7tv.io/v3/emote-sets/global"), "emotes", G_7TV)));
        TwitchChat.pool().execute(() -> run("BTTV global", () -> loadBttv(getString("https://api.betterttv.net/3/cached/emotes/global"), null, G_BTTV)));
        TwitchChat.pool().execute(() -> run("FFZ global", () -> loadFfzGlobal(getString("https://api.frankerfacez.com/v1/set/global"))));
    }

    /** Loads the channel's 7TV / BTTV / FFZ emotes once (channelId = Twitch user id of the channel). */
    public static void loadChannel(String channel, String channelId) {
        if (channel == null || channel.isEmpty() || channelId == null || channelId.isEmpty()) return;
        if (CHANNELS.putIfAbsent(channel, new Chan()) != null) return;
        Chan c = CHANNELS.get(channel);
        TwitchChat.pool().execute(() -> run("7TV " + channel, () -> {
            String body = getStringRetry("https://7tv.io/v3/users/twitch/" + channelId);
            if (body == null) return;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("emote_set") && root.get("emote_set").isJsonObject()) {
                load7tvSet(root.getAsJsonObject("emote_set").toString(), "emotes", c.seventv);
            }
        }));
        TwitchChat.pool().execute(() -> run("BTTV " + channel, () -> {
            String body = getString("https://api.betterttv.net/3/cached/users/twitch/" + channelId);
            if (body == null) return;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("channelEmotes")) loadBttv(root.get("channelEmotes").toString(), null, c.bttv);
            if (root.has("sharedEmotes")) loadBttv(root.get("sharedEmotes").toString(), null, c.bttv);
        }));
        TwitchChat.pool().execute(() -> run("FFZ " + channel, () -> {
            String body = getString("https://api.frankerfacez.com/v1/room/id/" + channelId);
            if (body == null) return;
            readFfzSets(JsonParser.parseString(body).getAsJsonObject(), c.ffz, false);
        }));
    }

    private interface Task {
        void run() throws Exception;
    }

    private static void run(String what, Task t) {
        try {
            t.run();
            VERSION.incrementAndGet();
        } catch (Exception e) {
            EbloChatClient.LOGGER.warn("Emote list '{}' failed: {}", what, e.toString());
        }
    }

    private static void load7tvSet(String body, String key, Map<String, Emote> into) {
        if (body == null) return;
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has(key) || !root.get(key).isJsonArray()) return;
        for (JsonElement el : root.getAsJsonArray(key)) {
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name");
            String id = str(o, "id");
            if (name.isEmpty() || id.isEmpty()) continue;
            boolean animated = false;
            String host = "//cdn.7tv.app/emote/" + id;
            if (o.has("data") && o.get("data").isJsonObject()) {
                JsonObject d = o.getAsJsonObject("data");
                animated = d.has("animated") && d.get("animated").getAsBoolean();
                if (d.has("host") && d.get("host").isJsonObject() && d.getAsJsonObject("host").has("url")) {
                    host = d.getAsJsonObject("host").get("url").getAsString();
                }
            }
            String base = (host.startsWith("//") ? "https:" + host : host);
            java.util.List<String> urls = new java.util.ArrayList<>();
            String first = animated ? ".gif" : ".png", second = animated ? ".png" : ".gif";
            for (String sz : new String[]{"/2x", "/3x", "/1x"}) {
                String a = base + sz + first;
                String b = base + sz + second;
                // 7TV's CDN (cdn.7tv.app) is blocked in Russia, so the proxy is tried first; the direct
                // URL stays as a fallback in case the proxy is unavailable.
                urls.add(proxied(a));
                urls.add(proxied(b));
                urls.add(a);
                urls.add(b);
            }
            into.putIfAbsent(name, new Emote(name, urls.toArray(new String[0])));
        }
    }

    private static void loadBttv(String body, String unused, Map<String, Emote> into) {
        if (body == null) return;
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) return;
        for (JsonElement el : (JsonArray) root) {
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            String code = str(o, "code");
            if (id.isEmpty() || code.isEmpty()) continue;
            String type = str(o, "imageType");
            String ext = type.equals("gif") ? "gif" : "png";
            String base = "https://cdn.betterttv.net/emote/" + id;
            into.putIfAbsent(code, new Emote(code, base + "/2x." + ext, base + "/2x", base + "/1x." + ext));
        }
    }

    private static void loadFfzGlobal(String body) {
        if (body == null) return;
        readFfzSets(JsonParser.parseString(body).getAsJsonObject(), G_FFZ, true);
    }

    private static void readFfzSets(JsonObject root, Map<String, Emote> into, boolean onlyDefault) {
        if (!root.has("sets") || !root.get("sets").isJsonObject()) return;
        JsonObject sets = root.getAsJsonObject("sets");
        for (Map.Entry<String, JsonElement> s : sets.entrySet()) {
            if (onlyDefault && root.has("default_sets") && root.get("default_sets").isJsonArray()) {
                boolean ok = false;
                for (JsonElement d : root.getAsJsonArray("default_sets")) ok |= d.getAsString().equals(s.getKey());
                if (!ok) continue;
            }
            JsonObject set = s.getValue().getAsJsonObject();
            if (!set.has("emoticons")) continue;
            for (JsonElement el : set.getAsJsonArray("emoticons")) {
                JsonObject o = el.getAsJsonObject();
                String name = str(o, "name");
                if (name.isEmpty() || !o.has("urls") || !o.get("urls").isJsonObject()) continue;
                JsonObject urls = o.getAsJsonObject("urls");
                String u = urls.has("2") ? str(urls, "2") : urls.has("1") ? str(urls, "1") : "";
                if (u.isEmpty()) continue;
                into.putIfAbsent(name, new Emote(name, u.startsWith("//") ? "https:" + u : u));
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    // ---- images --------------------------------------------------------------------------------------------------

    private static String getString(String url) throws Exception {
        byte[] b = getBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Text fetch with retries: Russian DPI sometimes resets 7TV API connections mid-handshake. */
    private static String getStringRetry(String url) {
        for (int i = 0; i < 3; i++) {
            try {
                String body = getString(url);
                if (body != null && !body.isEmpty()) return body;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Public image proxy (wsrv.nl) used for 7TV's CDN, which is unreachable in Russia. {@code n=-1} keeps
     * every frame of animated GIFs instead of flattening them to the first frame.
     */
    private static final String IMAGE_PROXY = "https://wsrv.nl/?n=-1&url=";

    /** Image downloads use a shorter connect timeout so a blocked host does not stall the (2-thread) pool. */
    private static final HttpClient IMAGE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static String proxied(String url) {
        return IMAGE_PROXY + URLEncoder.encode(url, StandardCharsets.UTF_8);
    }

    /** Hosts that recently failed to connect (blocked by the ISP); skipped for a while to avoid long stalls. */
    private static final Map<String, Long> DEAD_HOSTS = new ConcurrentHashMap<>();
    private static final long DEAD_TTL_MS = 5 * 60_000L;

    private static boolean hostIsDead(String host) {
        if (host == null) return false;
        Long until = DEAD_HOSTS.get(host);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            DEAD_HOSTS.remove(host, until);
            return false;
        }
        return true;
    }

    /** First URL that answers 200; each URL is retried once (Russian DPI drops connections randomly). */
    private static byte[] getBytes(String... urls) throws Exception {
        for (String u : urls) {
            String host;
            try {
                host = URI.create(u).getHost();
            } catch (IllegalArgumentException bad) {
                continue;
            }
            if (hostIsDead(host)) continue;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "Blockly").GET().build();
                    HttpResponse<byte[]> resp = IMAGE_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200 && resp.body().length > 0) return resp.body();
                    break; // a real HTTP error (404 etc.) will not change on retry
                } catch (java.net.http.HttpConnectTimeoutException timeout) {
                    DEAD_HOSTS.put(host, System.currentTimeMillis() + DEAD_TTL_MS);
                    break;
                } catch (java.net.http.HttpTimeoutException timeout) {
                    break; // host is blocked/unreachable; retrying will not help
                } catch (java.io.IOException ignored) {
                    if (attempt == 0) Thread.sleep(200);
                }
            }
        }
        return null;
    }

    /** Animated emotes are played frame by frame; set to false to show only the first frame. */
    private static final boolean ANIMATE = true;
    private static final int MAX_FRAMES = 48;

    private static void loadImage(Emote e) {
        try {
            byte[] raw = getBytes(e.urls);
            if (raw == null) {
                e.failed = true;
                return;
            }
            java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
            java.util.List<Integer> delays = new java.util.ArrayList<>();
            boolean gif = raw.length > 6 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F';
            if (gif) {
                try {
                    readGif(raw, frames, delays);
                } catch (Exception ex) {
                    frames.clear();
                    delays.clear();
                }
            }
            if (frames.isEmpty()) {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
                if (src == null) {
                    e.failed = true;
                    return;
                }
                frames.add(src);
                delays.add(100);
            }
            BufferedImage f0 = frames.get(0);
            int tw = Math.max(8, Math.min(TEX_MAX_W, Math.round(f0.getWidth() * TEX_H / (float) f0.getHeight())));
            java.util.List<NativeImage> images = new java.util.ArrayList<>();
            long[] ends = new long[frames.size()];
            long acc = 0;
            for (int i = 0; i < frames.size(); i++) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(scale(frames.get(i), tw, TEX_H), "png", bos);
                images.add(NativeImage.read(new ByteArrayInputStream(bos.toByteArray())));
                acc += delays.get(i);
                ends[i] = acc;
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                Identifier[] ids = new Identifier[images.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = Identifier.fromNamespaceAndPath("eblochat", "emote/e" + TEX.incrementAndGet());
                    mc.getTextureManager().register(ids[i], new DynamicTexture(() -> "eblochat emote", images.get(i)));
                }
                e.img = new Img(ids, ends, tw, TEX_H);
                VERSION.incrementAndGet();
            });
        } catch (Throwable t) {
            e.failed = true;
            EbloChatClient.LOGGER.warn("Emote image '{}' failed: {}", e.code, t.toString());
        }
    }

    /** Decodes every GIF frame onto a canvas (handles offsets and disposal), up to MAX_FRAMES frames. */
    private static void readGif(byte[] raw, java.util.List<BufferedImage> out, java.util.List<Integer> delays) throws Exception {
        javax.imageio.ImageReader r = ImageIO.getImageReadersByFormatName("gif").next();
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            r.setInput(in, false);
            int n = r.getNumImages(true);
            int cw = 0, ch = 0;
            try {
                org.w3c.dom.Node root = r.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                org.w3c.dom.Node lsd = ((org.w3c.dom.Element) root).getElementsByTagName("LogicalScreenDescriptor").item(0);
                cw = Integer.parseInt(lsd.getAttributes().getNamedItem("logicalScreenWidth").getNodeValue());
                ch = Integer.parseInt(lsd.getAttributes().getNamedItem("logicalScreenHeight").getNodeValue());
            } catch (Exception ignored) {
            }
            if (cw <= 0 || ch <= 0) {
                BufferedImage f = r.read(0);
                cw = f.getWidth();
                ch = f.getHeight();
            }
            BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            int step = Math.max(1, (int) Math.ceil(n / (double) MAX_FRAMES));
            int pendingDelay = 0;
            for (int i = 0; i < n; i++) {
                BufferedImage f = r.read(i);
                int left = 0, top = 0, delay = 10;
                String disposal = "none";
                org.w3c.dom.Element root = (org.w3c.dom.Element) r.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                org.w3c.dom.Node d = root.getElementsByTagName("ImageDescriptor").item(0);
                if (d != null) {
                    left = Integer.parseInt(d.getAttributes().getNamedItem("imageLeftPosition").getNodeValue());
                    top = Integer.parseInt(d.getAttributes().getNamedItem("imageTopPosition").getNodeValue());
                }
                org.w3c.dom.Node g = root.getElementsByTagName("GraphicControlExtension").item(0);
                if (g != null) {
                    disposal = g.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                    delay = Integer.parseInt(g.getAttributes().getNamedItem("delayTime").getNodeValue());
                }
                BufferedImage before = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                Graphics2D gg = canvas.createGraphics();
                gg.drawImage(f, left, top, null);
                gg.dispose();
                int ms = delay <= 1 ? 100 : delay * 10;
                pendingDelay += ms;
                if (i % step == 0) {
                    out.add(copy(canvas));
                    delays.add(pendingDelay);
                    pendingDelay = 0;
                    if (!ANIMATE) break;
                }
                if (disposal.equals("restoreToBackgroundColor")) {
                    Graphics2D c = canvas.createGraphics();
                    c.setComposite(java.awt.AlphaComposite.Clear);
                    c.fillRect(left, top, f.getWidth(), f.getHeight());
                    c.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
        } finally {
            r.dispose();
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    /** Smooth down-scaling (repeated halving, then a final bilinear step). */
    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() >= w * 2 && cur.getHeight() >= h * 2) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
