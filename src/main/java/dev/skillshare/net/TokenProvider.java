package dev.skillshare.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务端签名 token 的获取与缓存（独立版，逻辑与主 MICx-toolkit SignedTokenProvider 一致）。
 *
 * <p>jar 内只嵌入 RSA 公钥，私钥留在服务端。token 短期有效（默认 1h），
 * 客户端每次使用前验证签名与过期时间，避免硬编码共享密钥。</p>
 */
public class TokenProvider {

    /** 服务端公钥（PEM），与 /opt/zombies/keys/server_public.pem 对应。 */
    private static final String PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1Mq147intdgg6rL2x4P/\n" +
        "pJxmkWHl1x8GUME7khtrA+/dLp+N0FeXnSfyg06JWvRgX3uW7t9A/GU481YKph8V\n" +
        "yviHmRJtgbYkT9LnXazlKR7uEnvkH5J8lVrYfvqzaMneb+bWndqPuGzR8c5563em\n" +
        "XnVBZgI2YjLtoabrlZi01z+C2HsrngP8yxH8xTIdOswajpFMU2HbVPTvMO3QOHE5\n" +
        "dFVOevnbH/q3QdDujmD0qkgJtflbthJoKTRe2FD0I9do600uoxUXELaSdd9v9JNP\n" +
        "d8xddF9Mv90fSIM+D58Zl5PEW7Uz4XeYYcsAl1eTweKONm3DIo2A3ZGwc4+wts0S\n" +
        "BwIDAQAB\n" +
        "-----END PUBLIC KEY-----";

    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L; // 过期前 5 分钟刷新
    private static final int TIMEOUT_MS = 8000;

    private final Gson gson = new Gson();
    private final String tokenUrl;

    // 缓存原始 JSON 串（非 JsonObject）：每次 getToken() 重新解析出独立副本，
    // 既避免调用方改到缓存，又绕开 Forge 自带 Gson 2.2.4 里 package-private 的 deepCopy()。
    private volatile String cachedTokenJson;
    private volatile long cachedExpMs;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public TokenProvider(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    /** 返回当前有效的签名 token（每次解析出新的 JSON 对象，调用方可安全改动）。 */
    public synchronized JsonObject getToken() {
        ensureFresh();
        String json = cachedTokenJson;
        return json == null ? null : gson.fromJson(json, JsonObject.class);
    }

    /**
     * 非阻塞取 token：缓存未过期立即返回；需要刷新时交给后台线程，本次可能返回 null。
     * 供游戏主线程调用——绝不在调用线程做网络 IO（fetch 超时 8s，主线程扛不起）。
     */
    public JsonObject getTokenNonBlocking() {
        long now = System.currentTimeMillis();
        String json = cachedTokenJson;
        if (json == null || cachedExpMs - now <= REFRESH_MARGIN_MS) {
            refreshAsync();
        }
        if (json != null && cachedExpMs > now) {
            return gson.fromJson(json, JsonObject.class);
        }
        return null;
    }

    /** 后台刷新 token，同一时刻只允许一个刷新线程。 */
    public void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { ensureFresh(); } finally { refreshing.set(false); }
            }
        }, "SkillShare-Token");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void ensureFresh() {
        long now = System.currentTimeMillis();
        if (cachedTokenJson != null && cachedExpMs - now > REFRESH_MARGIN_MS) {
            return;
        }
        if (!fetch()) {
            // 刷新失败但旧 token 仍未过期，则继续使用
            if (cachedTokenJson == null || cachedExpMs <= now) {
                cachedTokenJson = null;
            }
        }
    }

    private boolean fetch() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(tokenUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[SkillShare] token fetch HTTP " + code);
                return false;
            }

            StringBuilder sb = new StringBuilder();
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            String body = sb.toString();
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            if (obj == null || !verify(obj)) {
                System.err.println("[SkillShare] token signature invalid");
                return false;
            }

            long expSec = obj.get("exp").getAsLong();
            long expMs = expSec * 1000L;
            if (expMs <= System.currentTimeMillis()) {
                System.err.println("[SkillShare] token already expired");
                return false;
            }

            cachedTokenJson = body;
            cachedExpMs = expMs;
            return true;
        } catch (Exception e) {
            System.err.println("[SkillShare] token fetch failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean verify(JsonObject obj) {
        try {
            if (!obj.has("token") || !obj.has("exp") || !obj.has("sig")) return false;
            String token = obj.get("token").getAsString();
            long exp = obj.get("exp").getAsLong();
            String sigB64 = obj.get("sig").getAsString();

            String message = token + ":" + exp;
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(loadPublicKey());
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    private static PublicKey loadPublicKey() throws Exception {
        String base64 = PUBLIC_KEY_PEM
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }
}