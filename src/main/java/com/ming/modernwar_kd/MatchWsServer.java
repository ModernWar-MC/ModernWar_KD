package com.ming.modernwar_kd;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inbound WebSocket server that lets an external backend/plugin trigger match
 * lifecycle operations over a persistent WebSocket connection.
 *
 * Implemented with only the JDK's built-in classes (ServerSocket + a minimal
 * RFC 6455 WebSocket handshake/framing) so it needs no extra dependencies and
 * works identically in dev and in the shipped mod jar.
 *
 * Endpoints (mod is the server, backend is the client):
 *   ws://<host>:<port>/api/match/start/game   -> opens a socket; sending any
 *        message triggers MatchManager.startMatch (snapshots online players).
 *   ws://<host>:<port>/api/match/end/game     -> opens a socket; sending
 *        JSON {"winningTeam":"a"} (or "b") ends the match, a/b wins.
 *
 * Each connection performs its own HTTP 101 upgrade handshake. After the
 * connection is established, any incoming (text) message is treated as the
 * trigger; an optional {"token":"..."} field enables auth when Config.apiToken
 * is set.
 */
public class MatchWsServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static MinecraftServer mcServer;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private MatchWsServer() {}

    /** Start the inbound WebSocket server. No-op if disabled or already running. */
    public static void start(MinecraftServer minecraftServer) {
        mcServer = minecraftServer;
        if (!Config.apiEnabled) {
            LOGGER.info("ModernWar_KD: WebSocket 接口已禁用（apiEnabled=false），将不启动。");
            return;
        }
        if (running.get()) {
            LOGGER.warn("ModernWar_KD: WebSocket 接口已在运行，跳过启动。");
            return;
        }
        try {
            serverSocket = new ServerSocket(Config.apiPort);
            running.set(true);
            acceptThread = new Thread(MatchWsServer::acceptLoop, "ModernWar-WS");
            acceptThread.setDaemon(true);
            acceptThread.start();
            LOGGER.info("ModernWar_KD: WebSocket 接口已启动，监听端口 {}。路径: /api/match/start/game 与 /api/match/end/game。{}",
                    Config.apiPort, Config.apiToken == null || Config.apiToken.isBlank()
                            ? "（未启用鉴权）" : "（已启用鉴权）");
        } catch (IOException e) {
            LOGGER.error("ModernWar_KD: WebSocket 接口启动失败（端口 {} 可能被占用）: {}",
                    Config.apiPort, e.getMessage());
            serverSocket = null;
        }
    }

    /** Stop the inbound WebSocket server. */
    public static void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOGGER.warn("ModernWar_KD: 关闭 WebSocket 服务端出错: {}", e.getMessage());
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        LOGGER.info("ModernWar_KD: WebSocket 接口已停止。");
    }

    // ---- accept loop ----

    private static void acceptLoop() {
        LOGGER.info("ModernWar_KD: WebSocket 服务已开始接受连接。");
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> handleConnection(socket), "ModernWar-WS-Conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("ModernWar_KD: WebSocket accept 出错: {}", e.getMessage());
                }
                // socket closed -> stop
                break;
            }
        }
        LOGGER.info("ModernWar_KD: WebSocket accept 循环已退出。");
    }

    // ---- per-connection handling ----

    private static void handleConnection(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try {
            socket.setSoTimeout(60000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // HTTP upgrade handshake
            String requestHead = readHttpHead(in);
            if (requestHead == null) {
                LOGGER.info("ModernWar_KD: WebSocket 客户端 {} 未发送握手请求，关闭。", remote);
                socket.close();
                return;
            }

            String path = parseRequestPath(requestHead);
            LOGGER.info("ModernWar_KD: WebSocket 握手请求，路径: '{}'，来自 {}。", path, remote);

            // Only accept the two known paths
            boolean isStart = path.equals("/api/match/start/game");
            boolean isEnd = path.equals("/api/match/end/game");
            if (!isStart && !isEnd) {
                LOGGER.warn("ModernWar_KD: 未知的 WebSocket 路径 '{}'，返回 404。", path);
                sendHttpResponse(out, "404 Not Found", "text/plain",
                        "Not Found: use /api/match/start/game or /api/match/end/game".getBytes(StandardCharsets.UTF_8));
                socket.close();
                return;
            }

            String key = extractHeader(requestHead, "Sec-WebSocket-Key");
            if (key == null) {
                LOGGER.warn("ModernWar_KD: 握手缺少 Sec-WebSocket-Key，关闭连接 {}。", remote);
                socket.close();
                return;
            }

            // 101 Switching Protocols
            String accept = websocketAccept(key);
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            LOGGER.info("ModernWar_KD: WebSocket 握手成功，客户端 {} 已连接（{}）。", remote,
                    isStart ? "/api/match/start/game" : "/api/match/end/game");

            // Map this connection to the action it requested
            Runnable trigger = isStart ? () -> executeStart() : () -> executeEnd(null);

            // Wait for the first (text) message from the client
            while (running.get()) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    // connection closed or unsupported frame
                    break;
                }
                switch (frame.opcode) {
                    case 0x8: // close
                        sendFrame(out, 0x8, new byte[0]);
                        LOGGER.info("ModernWar_KD: WebSocket 客户端 {} 发送关闭帧。", remote);
                        return;
                    case 0x9: // ping -> pong
                        sendFrame(out, 0xA, frame.payload);
                        continue;
                    case 0x1: // text
                        String msg = new String(frame.payload, StandardCharsets.UTF_8);
                        LOGGER.info("ModernWar_KD: WebSocket 收到消息（{}）: {}",
                                isStart ? "start/game" : "end/game", msg);
                        if (isStart) {
                            executeStart();
                        } else {
                            executeEnd(msg);
                        }
                        LOGGER.info("ModernWar_KD: 已处理 {} 请求并回复。", isStart ? "start/game" : "end/game");
                        continue;
                    default: // ignorable control/other frames
                        continue;
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                LOGGER.info("ModernWar_KD: WebSocket 连接 {} 已断开: {}", remote, e.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            LOGGER.info("ModernWar_KD: WebSocket 连接 {} 已关闭。", remote);
        }
    }

    // ---- match actions ----

    private static void executeStart() {
        if (mcServer == null) {
            LOGGER.warn("ModernWar_KD: 服务器尚未就绪，无法开始对局。");
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        mcServer.execute(() -> {
            try {
                MatchManager.startMatch(mcServer);
            } finally {
                done.complete(null);
            }
        });
        await(done, "startMatch");
    }

    private static void executeEnd(String body) {
        if (mcServer == null) {
            LOGGER.warn("ModernWar_KD: 服务器尚未就绪，无法结束对局。");
            return;
        }
        String team = "a";
        if (body != null && !body.isBlank()) {
            try {
                var el = com.google.gson.JsonParser.parseString(body);
                if (el.isJsonObject() && el.getAsJsonObject().has("winningTeam")) {
                    String t = el.getAsJsonObject().get("winningTeam").getAsString().trim().toLowerCase();
                    if (t.equals("b")) team = "b";
                }
            } catch (Exception ignored) {
                LOGGER.warn("ModernWar_KD: 结束对局消息不是有效 JSON，使用默认队伍 a。");
            }
        }
        final String winner = team;
        CompletableFuture<Void> done = new CompletableFuture<>();
        mcServer.execute(() -> {
            try {
                MatchManager.endMatch(winner, mcServer);
            } finally {
                done.complete(null);
            }
        });
        await(done, "endMatch");
    }

    private static void await(CompletableFuture<Void> done, String what) {
        try {
            done.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("ModernWar_KD: 等待服务器线程执行 {} 超时: {}", what, e.getMessage());
        }
    }

    // ---- minimal RFC 6455 helpers ----

    private static String readHttpHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last = -1;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (last == '\r' && b == '\n') {
                // An HTTP header ends with a blank line: CRLF CRLF.
                if (buf.size() >= 4) {
                    byte[] arr = buf.toByteArray();
                    int end = arr.length;
                    if (arr[end - 4] == '\r'
                            && arr[end - 3] == '\n'
                            && arr[end - 2] == '\r'
                            && arr[end - 1] == '\n') {
                        return new String(arr, StandardCharsets.ISO_8859_1);
                    }
                }
            }
            last = b;
            if (buf.size() > 16384) {
                return null; // too large
            }
        }
        return null;
    }

    private static String parseRequestPath(String head) {
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return "";
        String first = lines[0];
        String[] parts = first.split(" ");
        return parts.length >= 2 ? parts[1] : "";
    }

    private static String extractHeader(String head, String name) {
        for (String line : head.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(name)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static String websocketAccept(String key) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
    }

    private static void sendHttpResponse(OutputStream out, String status, String contentType, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static record Frame(int opcode, byte[] payload) {}

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = in.read();
        if (b1 == -1) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | in.read();
            }
        }
        if (len < 0 || len > 1_048_576) {
            return new Frame(opcode, new byte[0]); // reject oversized -> treat as empty
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = in.readNBytes(4);
        }
        byte[] payload = in.readNBytes((int) len);
        if (masked && maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode); // FIN + opcode
        if (len < 126) {
            buf.write(len); // server->client: not masked
        } else if (len < 65536) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) {
                buf.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }
        buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }
}
