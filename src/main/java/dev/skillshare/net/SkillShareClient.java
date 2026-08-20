package dev.skillshare.net;

import com.google.gson.JsonObject;
import com.neovisionaries.ws.client.*;

import dev.skillshare.skill.TeamSkillTracker;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 客户端（独立版）：负责连接、重连、发送/接收。
 * 逻辑与主 MICx-toolkit TeamSyncClient 一致，仅保留技能共享所需的消息子集：
 * join / state(skill+lr) / lr_release / heartbeat / leave；不实现 ping 标点。
 */
public class SkillShareClient {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int INBOUND_CAP = 512;

    private final WebSocketFactory factory = new WebSocketFactory();
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<WebSocket>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectDelay = new AtomicInteger(1);
    private final AtomicBoolean reconnected = new AtomicBoolean(false);
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);
    private final AtomicInteger activeConnectionGeneration = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<InboundMessage> inbound = new ConcurrentLinkedQueue<InboundMessage>();
    private final AtomicInteger inboundSize = new AtomicInteger(0);
    private final AtomicInteger epoch = new AtomicInteger(0);

    private String url;
    private TokenProvider tokenProvider;
    private SSLSocketFactory baseSslSocketFactory;
    private volatile long connectedSinceMs = 0;

    public void start(String url, TokenProvider provider) {
        this.url = url;
        this.tokenProvider = provider;
        factory.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        configureTlsServerName(url);
        final int myEpoch = epoch.incrementAndGet();
        running.set(true);
        reconnecting.set(false);
        reconnectDelay.set(1);
        reconnected.set(false);
        activeConnectionGeneration.set(0);
        clearInbound();
        if (provider != null) provider.refreshAsync();   // 预取 token，避免 join 时才拉
        connect(myEpoch);
    }

    public void stop() {
        epoch.incrementAndGet();
        running.set(false);
        reconnecting.set(false);
        reconnected.set(false);
        connectedSinceMs = 0;
        activeConnectionGeneration.set(0);
        clearInbound();
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try { ws.disconnect(); } catch (Exception ignored) { }
        }
    }

    public boolean isConnected() {
        WebSocket ws = wsRef.get();
        return ws != null && ws.isOpen();
    }

    /** 取出一条收到的消息；无消息返回 null。主线程消费，过滤不属当前代际的消息。 */
    public String poll() {
        InboundMessage message;
        while ((message = inbound.poll()) != null) {
            decrementInboundSize();
            if (running.get() && message.epoch == epoch.get()
                    && message.connectionGeneration == activeConnectionGeneration.get()) return message.text;
        }
        return null;
    }

    /** 连接（重）建立后返回一次 true——调用方需要重新 join。 */
    public boolean consumeReconnected() {
        return reconnected.getAndSet(false);
    }

    private void connect(final int myEpoch) {
        if (!running.get() || epoch.get() != myEpoch) return;
        WebSocket created = null;
        try {
            final WebSocket ws = factory.createSocket(url);
            created = ws;
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    if (!isCurrent(websocket, myEpoch)) {
                        try { websocket.disconnect(); } catch (Exception ignored) { }
                        return;
                    }
                    reconnectDelay.set(1);
                    int generation = connectionGeneration.incrementAndGet();
                    activeConnectionGeneration.set(generation);
                    clearInbound();
                    reconnected.set(true);
                    connectedSinceMs = System.currentTimeMillis();
                }
                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    if (epoch.get() == myEpoch && wsRef.compareAndSet(websocket, null)) {
                        scheduleReconnect(myEpoch);
                    }
                }
                @Override
                public void onConnectError(WebSocket websocket, WebSocketException cause) {
                    if (epoch.get() == myEpoch && wsRef.compareAndSet(websocket, null)) {
                        scheduleReconnect(myEpoch);
                    }
                }
                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    if (!isCurrent(websocket, myEpoch)) return;
                    inbound.offer(new InboundMessage(myEpoch, activeConnectionGeneration.get(), text));
                    if (inboundSize.incrementAndGet() > INBOUND_CAP) {
                        if (inbound.poll() != null) decrementInboundSize();
                    }
                }
                @Override
                public void onError(WebSocket websocket, WebSocketException cause) {
                    // 连接中错误交给 onDisconnected/onConnectError 处理重连
                }
            });
            if (!running.get() || epoch.get() != myEpoch || !wsRef.compareAndSet(null, ws)) {
                try { ws.disconnect(); } catch (Exception ignored) { }
                return;
            }
            ws.connectAsynchronously();
        } catch (Exception e) {
            if (created != null) {
                wsRef.compareAndSet(created, null);
                try { created.disconnect(); } catch (Exception ignored) { }
            }
            scheduleReconnect(myEpoch);
        }
    }

    private boolean isCurrent(WebSocket websocket, int myEpoch) {
        return running.get() && epoch.get() == myEpoch && wsRef.get() == websocket;
    }

    private void clearInbound() {
        inbound.clear();
        inboundSize.set(0);
    }

    void discardInbound() {
        clearInbound();
    }

    private void decrementInboundSize() {
        inboundSize.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    private static final class InboundMessage {
        final int epoch;
        final int connectionGeneration;
        final String text;

        InboundMessage(int epoch, int connectionGeneration, String text) {
            this.epoch = epoch;
            this.connectionGeneration = connectionGeneration;
            this.text = text;
        }
    }

    private void configureTlsServerName(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            if ("wss".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                String hostname = uri.getHost();
                if (baseSslSocketFactory == null) {
                    baseSslSocketFactory = factory.getSSLSocketFactory();
                    if (baseSslSocketFactory == null) {
                        baseSslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    }
                }
                // nv-websocket-client 2.14 mutates an SSLParameters copy without writing it
                // back on Java 8. The wrapper applies SNI to every created TLS socket itself.
                factory.setSSLSocketFactory(new SniSocketFactory(baseSslSocketFactory, hostname));
                factory.setServerName(hostname);
            }
        } catch (IllegalArgumentException ignored) { }
    }

    private void scheduleReconnect(final int myEpoch) {
        if (!running.get() || epoch.get() != myEpoch || !reconnecting.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                int delay = Math.min(reconnectDelay.getAndUpdate(d -> Math.min(d * 2, 30)), 30);
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException ignored) { }
            if (!running.get() || epoch.get() != myEpoch) return;
            reconnecting.set(false);
            connect(myEpoch);
        }, "SkillShare-Reconnect");
        t.setDaemon(true);
        t.start();
    }

    static void applyTlsServerName(SSLSocket socket, String hostname) {
        SSLParameters parameters = socket.getSSLParameters();
        List<SNIServerName> names = Collections.<SNIServerName>singletonList(new SNIHostName(hostname));
        parameters.setServerNames(names);
        socket.setSSLParameters(parameters);
    }

    static final class SniSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String hostname;

        SniSocketFactory(SSLSocketFactory delegate, String hostname) {
            this.delegate = delegate;
            this.hostname = hostname;
        }

        private Socket configure(Socket socket) throws IOException {
            if (socket instanceof SSLSocket) {
                try {
                    applyTlsServerName((SSLSocket) socket, hostname);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid TLS server name: " + hostname, e);
                }
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException {
            return configure(delegate.createSocket());
        }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return configure(delegate.createSocket(address, port, local, localPort));
        }
    }

    private boolean send(String json) {
        WebSocket ws = wsRef.get();
        if (ws == null || !ws.isOpen()) return false;
        try {
            ws.sendText(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean sendJoin(String selfName, java.util.Collection<String> roster, String map, int round) {
        if (!isConnected()) return false;
        // 非阻塞：token 还在后台刷新时返回 null，本轮放弃，下个周期重试。绝不卡主线程。
        JsonObject token = tokenProvider != null ? tokenProvider.getTokenNonBlocking() : null;
        if (token == null) {
            return false;
        }
        String tokenJson = new com.google.gson.Gson().toJson(token);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"join\",\"token\":").append(tokenJson)
          .append(",\"self\":\"").append(escapeJson(selfName)).append("\",\"roster\":[");
        boolean first = true;
        for (String r : roster) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(r)).append('"');
            first = false;
        }
        sb.append("],\"map\":\"").append(escapeJson(map)).append("\",\"round\":").append(round).append('}');
        return send(sb.toString());
    }

    /** 上报自身技能状态（skill + lr 释放通道），字段与主 mod buildStatePayload 一致以保证互通。 */
    public boolean sendState(String name, double x, double y, double z,
                          TeamSkillTracker.Snapshot skill, long stateSeq,
                          long lrReleasedAtMs, long lrReadyAtMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"state\",\"name\":\"").append(escapeJson(name)).append("\"")
          .append(",\"state_seq\":").append(stateSeq)
          .append(",\"pos\":{\"x\":").append(fmt(x))
          .append(",\"y\":").append(fmt(y)).append(",\"z\":").append(fmt(z)).append("}");
        if (skill != null && skill.known && skill.skillName != null) {
            sb.append(",\"skill\":{\"slot\":").append(TeamSkillTracker.SLOT_INDEX)
              .append(",\"state\":\"").append(skill.state.name()).append("\"")
              .append(",\"name\":\"").append(escapeJson(skill.skillName)).append("\"")
              .append(",\"remaining_s\":").append(skill.remainingSeconds).append('}');
        }
        // LR 释放通道：仅当本地技能是 Lightning Rod 时携带；ready_at>0=冷却中，0=已就绪(清队友残留)。
        if ("Lightning Rod".equals(skill != null ? skill.skillName : null)) {
            sb.append(",\"lr\":{\"released\":").append(lrReleasedAtMs)
              .append(",\"ready_at\":").append(lrReadyAtMs).append('}');
        }
        sb.append('}');
        return send(sb.toString());
    }

    /** 上报自己释放 LR 的即时事件。struckCount>=0 = N 个敌人；struckName!=null = 单个怪。 */
    public void sendLrRelease(String name, int struckCount, String struckName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"lr_release\",\"name\":\"").append(escapeJson(name)).append("\"");
        if (struckCount >= 0) sb.append(",\"struck_count\":").append(struckCount);
        if (struckName != null) sb.append(",\"struck_name\":\"").append(escapeJson(struckName)).append("\"");
        sb.append('}');
        send(sb.toString());
    }

    /**
     * 上报一次 Heal 释放（释放者或被奶者代报均可；服务端负责按 healer+时间窗去重）。
     * {@code releasedMs} 为释放发生时刻的本地毫秒时间戳，供服务端聚合去重。
     */
    public void sendHealRelease(String healer, long releasedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"heal_release\",\"healer\":\"").append(escapeJson(healer)).append("\"")
          .append(",\"released\":").append(releasedMs).append('}');
        send(sb.toString());
    }

    public void sendLeave() {
        send("{\"type\":\"leave\"}");
    }

    public void sendHeartbeat() {
        send("{\"type\":\"heartbeat\"}");
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format(java.util.Locale.US, "%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}