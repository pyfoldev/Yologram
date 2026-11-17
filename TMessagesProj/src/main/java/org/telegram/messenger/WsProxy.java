package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class WsProxy {

    public static final int PORT = 1090;
    private static final String PREFS = "nullgram_wsproxy";

    private static final Object lock = new Object();
    private static Server server;

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean on) {
        prefs().edit().putBoolean("enabled", on).apply();
        if (on) {
            start();
            applyToTelegram(true);
        } else {
            applyToTelegram(false);
            stop();
        }
    }

    public static void startIfEnabled() {
        if (isEnabled()) {
            start();
        }
    }

    public static boolean isRunning() {
        synchronized (lock) {
            return server != null && server.running;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static void start() {
        synchronized (lock) {
            if (server != null && server.running) {
                return;
            }
            server = new Server();
            server.start();
        }
    }

    private static void stop() {
        synchronized (lock) {
            if (server != null) {
                server.stop();
                server = null;
            }
        }
    }

    private static void applyToTelegram(boolean on) {
        AndroidUtilities.runOnUIThread(() -> {
            if (on) {
                final ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.SOCKS5)
                    .setAddress("127.0.0.1")
                    .setPort(PORT)
                    .setUser("")
                    .setPassword("")
                    .build();
                SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                SharedConfig.currentProxy = info;
                final SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                info.settings.toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", true);
                editor.commit();
                ConnectionsManager.setProxySettings(true, info.settings);
            } else {
                final SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled", false);
                editor.commit();
                if (SharedConfig.currentProxy != null) {
                    ConnectionsManager.setProxySettings(false, SharedConfig.currentProxy.settings);
                }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        });
    }

    private static final long[][] TG_RANGES = {
        {ip("185.76.151.0"), ip("185.76.151.255")},
        {ip("149.154.160.0"), ip("149.154.175.255")},
        {ip("91.105.192.0"), ip("91.105.193.255")},
        {ip("91.108.0.0"), ip("91.108.255.255")},
    };

    private static final Map<String, int[]> IP_TO_DC = new HashMap<>();

    static {
        put(1, 0, "149.154.175.50", "149.154.175.51", "149.154.175.53", "149.154.175.54");
        put(1, 1, "149.154.175.52");
        put(2, 0, "149.154.167.41", "149.154.167.50", "149.154.167.51", "149.154.167.220", "95.161.76.100");
        put(2, 1, "149.154.167.151", "149.154.167.222", "149.154.167.223", "149.154.162.123");
        put(3, 0, "149.154.175.100", "149.154.175.101");
        put(3, 1, "149.154.175.102");
        put(4, 0, "149.154.167.91", "149.154.167.92");
        put(4, 1, "149.154.164.250", "149.154.166.120", "149.154.166.121", "149.154.167.118", "149.154.165.111");
        put(5, 0, "91.108.56.100", "91.108.56.101", "91.108.56.116", "91.108.56.126", "149.154.171.5");
        put(5, 1, "91.108.56.102", "91.108.56.128", "91.108.56.151");
        put(203, 0, "91.105.192.100");
    }

    private static void put(int dc, int media, String... ips) {
        for (String s : ips) {
            IP_TO_DC.put(s, new int[]{dc, media});
        }
    }

    private static final String RELAY_HOST = "yologram.duckdns.org";
    private static final String RELAY_IP = "103.45.247.151";
    private static final int RELAY_PORT = 8790;
    private static final Map<Integer, String> DC_OPT = new HashMap<>();

    static {
        DC_OPT.put(2, "149.154.167.220");
        DC_OPT.put(4, "149.154.167.220");
    }

    private static final int PROTO_ABRIDGED = 0xEFEFEFEF;
    private static final int PROTO_INTERMEDIATE = 0xEEEEEEEE;
    private static final int PROTO_PADDED_INTERMEDIATE = 0xDDDDDDDD;

    private static long ip(String s) {
        try {
            final byte[] b = InetAddress.getByName(s).getAddress();
            return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isTelegramIp(String s) {
        final long n = ip(s);
        for (long[] r : TG_RANGES) {
            if (n >= r[0] && n <= r[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpTransport(byte[] d) {
        if (d.length < 4) return false;
        final String p = new String(d, 0, Math.min(8, d.length), java.nio.charset.StandardCharsets.US_ASCII);
        return p.startsWith("POST ") || p.startsWith("GET ") || p.startsWith("HEAD ") || p.startsWith("OPTIONS ");
    }

    private static String[] wsDomains(int dc, boolean media) {
        final int d = dc == 203 ? 2 : dc;
        return media
            ? new String[]{"kws" + d + "-1.web.telegram.org", "kws" + d + ".web.telegram.org"}
            : new String[]{"kws" + d + ".web.telegram.org", "kws" + d + "-1.web.telegram.org"};
    }

    private static int[] dcFromInit(byte[] data) {
        if (data.length < 64) return null;
        try {
            final byte[] ks = keystream(data);
            final byte[] plain = new byte[8];
            for (int i = 0; i < 8; i++) {
                plain[i] = (byte) (data[56 + i] ^ ks[56 + i]);
            }
            final ByteBuffer buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
            final int proto = buf.getInt(0);
            final int dcRaw = buf.getShort(4);
            if (proto == PROTO_ABRIDGED || proto == PROTO_INTERMEDIATE || proto == PROTO_PADDED_INTERMEDIATE) {
                final int dc = Math.abs(dcRaw);
                if ((dc >= 1 && dc <= 5) || dc == 203) {
                    return new int[]{dc, dcRaw < 0 ? 1 : 0, proto};
                }
                return new int[]{0, 0, proto};
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static byte[] patchInitDc(byte[] data, int dc) {
        if (data.length < 64) return data;
        try {
            final byte[] ks = keystream(data);
            final byte[] patched = data.clone();
            final byte[] nb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) dc).array();
            patched[60] = (byte) (ks[60] ^ nb[0]);
            patched[61] = (byte) (ks[61] ^ nb[1]);
            return patched;
        } catch (Exception e) {
            return data;
        }
    }

    private static byte[] keystream(byte[] data) throws Exception {
        final byte[] key = new byte[32];
        final byte[] iv = new byte[16];
        System.arraycopy(data, 8, key, 0, 32);
        System.arraycopy(data, 40, iv, 0, 16);
        final Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.update(new byte[64]);
    }

    private static class WsHandshakeError extends IOException {
        final int status;

        WsHandshakeError(int status, String line) {
            super("HTTP " + status + ": " + line);
            this.status = status;
        }

        boolean isRedirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }

    private static class WebSocket {
        private static final int OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed;

        private WebSocket(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        static WebSocket connect(String ip, String domain, int timeoutMs) throws IOException {
            return connect(ip, domain, 443, "/apiws", timeoutMs);
        }

        static WebSocket connect(String ip, String domain, int port, String path, int timeoutMs) throws IOException {
            final Socket raw = new Socket();
            raw.connect(new InetSocketAddress(ip, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            raw.setTcpNoDelay(true);
            final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            final SSLSocket ssl = (SSLSocket) factory.createSocket(raw, domain, port, true);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, ssl.getSession())) {
                ssl.close();
                throw new IOException("certificate does not match " + domain);
            }
            final OutputStream out = ssl.getOutputStream();
            final InputStream in = ssl.getInputStream();

            final byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            final String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + domain + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(keyBytes, Base64.NO_WRAP) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: binary\r\n"
                + "Origin: https://web.telegram.org\r\n"
                + "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n"
                + "\r\n";
            out.write(req.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();

            final List<String> lines = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();
            while (true) {
                final int b = in.read();
                if (b == -1) break;
                if (b == '\n') {
                    final String line = sb.toString().replaceAll("\r$", "");
                    if (line.isEmpty()) break;
                    lines.add(line);
                    sb.setLength(0);
                } else {
                    sb.append((char) b);
                }
            }
            if (lines.isEmpty()) {
                ssl.close();
                throw new WsHandshakeError(0, "empty response");
            }
            final String first = lines.get(0);
            final String[] parts = first.split(" ", 3);
            int status = 0;
            try {
                status = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            } catch (NumberFormatException ignore) {
            }
            if (status == 101) {
                ssl.setSoTimeout(0);
                return new WebSocket(ssl, in, out);
            }
            ssl.close();
            throw new WsHandshakeError(status, first);
        }

        void send(byte[] data) throws IOException {
            if (closed) throw new IOException("WebSocket closed");
            final byte[] frame = frame(OP_BINARY, data);
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        }

        void sendBatch(List<byte[]> parts) throws IOException {
            if (closed) throw new IOException("WebSocket closed");
            synchronized (out) {
                for (byte[] p : parts) {
                    out.write(frame(OP_BINARY, p));
                }
                out.flush();
            }
        }

        byte[] recv() throws IOException {
            while (!closed) {
                final byte[] hdr = readExactly(2);
                final int opcode = hdr[0] & 0x0F;
                long length = hdr[1] & 0x7F;
                if (length == 126) {
                    final byte[] e = readExactly(2);
                    length = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF);
                } else if (length == 127) {
                    length = ByteBuffer.wrap(readExactly(8)).getLong();
                }
                final boolean masked = (hdr[1] & 0x80) != 0;
                byte[] maskKey = null;
                if (masked) maskKey = readExactly(4);
                if (length < 0 || length > (64 << 20)) throw new IOException("bad frame length");
                byte[] payload = readExactly((int) length);
                if (masked) payload = xor(payload, maskKey);

                switch (opcode) {
                    case OP_CLOSE:
                        closed = true;
                        try {
                            synchronized (out) {
                                out.write(frame(OP_CLOSE, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]));
                                out.flush();
                            }
                        } catch (Exception ignore) {
                        }
                        return null;
                    case OP_PING:
                        try {
                            synchronized (out) {
                                out.write(frame(OP_PONG, payload));
                                out.flush();
                            }
                        } catch (Exception ignore) {
                        }
                        break;
                    case OP_TEXT:
                    case OP_BINARY:
                        return payload;
                    default:
                        break;
                }
            }
            return null;
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                synchronized (out) {
                    out.write(frame(OP_CLOSE, new byte[0]));
                    out.flush();
                }
            } catch (Exception ignore) {
            }
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }

        private byte[] readExactly(int n) throws IOException {
            final byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                final int r = in.read(buf, off, n - off);
                if (r == -1) throw new EOFException("connection closed");
                off += r;
            }
            return buf;
        }

        private static byte[] frame(int opcode, byte[] data) {
            final int len = data.length;
            final byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            final int headerSize = len < 126 ? 6 : (len < 65536 ? 8 : 14);
            final byte[] f = new byte[headerSize + len];
            f[0] = (byte) (0x80 | opcode);
            if (len < 126) {
                f[1] = (byte) (0x80 | len);
                System.arraycopy(mask, 0, f, 2, 4);
            } else if (len < 65536) {
                f[1] = (byte) (0x80 | 126);
                f[2] = (byte) (len >> 8);
                f[3] = (byte) len;
                System.arraycopy(mask, 0, f, 4, 4);
            } else {
                f[1] = (byte) (0x80 | 127);
                ByteBuffer.wrap(f, 2, 8).putLong(len);
                System.arraycopy(mask, 0, f, 10, 4);
            }
            for (int i = 0; i < len; i++) {
                f[headerSize + i] = (byte) (data[i] ^ mask[i & 3]);
            }
            return f;
        }

        private static byte[] xor(byte[] d, byte[] mask) {
            final byte[] r = new byte[d.length];
            for (int i = 0; i < d.length; i++) {
                r[i] = (byte) (d[i] ^ mask[i & 3]);
            }
            return r;
        }
    }

    private static class MsgSplitter {
        private final Cipher cipher;
        private final int proto;
        private final ByteArrayOutputStream cipherBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream plainBuf = new ByteArrayOutputStream();
        private boolean disabled;

        MsgSplitter(byte[] init, int proto) throws Exception {
            this.proto = proto;
            final byte[] key = new byte[32];
            final byte[] iv = new byte[16];
            System.arraycopy(init, 8, key, 0, 32);
            System.arraycopy(init, 40, iv, 0, 16);
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            cipher.update(new byte[64]);
        }

        List<byte[]> split(byte[] chunk) {
            final List<byte[]> parts = new ArrayList<>();
            if (chunk.length == 0) return parts;
            if (disabled) {
                parts.add(chunk);
                return parts;
            }
            cipherBuf.write(chunk, 0, chunk.length);
            final byte[] plainChunk = cipher.update(chunk);
            plainBuf.write(plainChunk, 0, plainChunk.length);

            final byte[] cipherArr = cipherBuf.toByteArray();
            final byte[] plainArr = plainBuf.toByteArray();
            int consumed = 0;
            while (consumed < cipherArr.length) {
                final int remaining = plainArr.length - consumed;
                final int len = nextPacketLen(plainArr, consumed, remaining);
                if (len == -1) break;
                if (len <= 0) {
                    final byte[] rest = new byte[cipherArr.length - consumed];
                    System.arraycopy(cipherArr, consumed, rest, 0, rest.length);
                    parts.add(rest);
                    consumed = cipherArr.length;
                    disabled = true;
                    break;
                }
                final byte[] p = new byte[len];
                System.arraycopy(cipherArr, consumed, p, 0, len);
                parts.add(p);
                consumed += len;
            }
            cipherBuf.reset();
            plainBuf.reset();
            if (consumed < cipherArr.length) {
                cipherBuf.write(cipherArr, consumed, cipherArr.length - consumed);
                plainBuf.write(plainArr, consumed, plainArr.length - consumed);
            }
            return parts;
        }

        byte[] flush() {
            final byte[] d = cipherBuf.toByteArray();
            cipherBuf.reset();
            plainBuf.reset();
            return d.length > 0 ? d : null;
        }

        private int nextPacketLen(byte[] plain, int off, int remaining) {
            if (remaining == 0) return -1;
            if (proto == PROTO_ABRIDGED) {
                final int first = plain[off] & 0xFF;
                final int headerLen;
                final int payloadLen;
                if (first == 0x7F || first == 0xFF) {
                    if (remaining < 4) return -1;
                    payloadLen = ((plain[off + 1] & 0xFF) | ((plain[off + 2] & 0xFF) << 8) | ((plain[off + 3] & 0xFF) << 16)) * 4;
                    headerLen = 4;
                } else {
                    payloadLen = (first & 0x7F) * 4;
                    headerLen = 1;
                }
                if (payloadLen <= 0) return 0;
                final int total = headerLen + payloadLen;
                return remaining < total ? -1 : total;
            }
            if (proto == PROTO_INTERMEDIATE || proto == PROTO_PADDED_INTERMEDIATE) {
                if (remaining < 4) return -1;
                final int payloadLen = ByteBuffer.wrap(plain, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0x7FFFFFFF;
                if (payloadLen <= 0) return 0;
                final int total = 4 + payloadLen;
                return remaining < total ? -1 : total;
            }
            return 0;
        }
    }

    private static class Server {
        volatile boolean running;
        private ServerSocket serverSocket;
        private ExecutorService executor;
        private final Set<Integer> wsBlacklist = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
        private final Map<Integer, Long> failUntil = new ConcurrentHashMap<>();

        void start() {
            running = true;
            executor = Executors.newCachedThreadPool(r -> {
                final Thread t = new Thread(r, "ws-proxy");
                t.setDaemon(true);
                return t;
            });
            final Thread acceptor = new Thread(() -> {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                    while (running) {
                        final Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        executor.execute(() -> handle(client));
                    }
                } catch (Exception e) {
                    if (running) FileLog.e("ws-proxy accept", e);
                }
            }, "ws-proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignore) {
            }
            if (executor != null) executor.shutdownNow();
        }

        private static byte[] reply(int status) {
            return new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0};
        }

        private void handle(Socket client) {
            try {
                final InputStream in = client.getInputStream();
                final OutputStream out = client.getOutputStream();

                final byte[] hdr = readExactly(in, 2);
                if (hdr[0] != 5) {
                    client.close();
                    return;
                }
                readExactly(in, hdr[1] & 0xFF);
                out.write(new byte[]{5, 0});
                out.flush();

                final byte[] req = readExactly(in, 4);
                if ((req[1] & 0xFF) != 1) {
                    out.write(reply(7));
                    out.flush();
                    client.close();
                    return;
                }
                final int atyp = req[3] & 0xFF;
                final String dst;
                if (atyp == 1) {
                    dst = InetAddress.getByAddress(readExactly(in, 4)).getHostAddress();
                } else if (atyp == 3) {
                    final int dlen = readExactly(in, 1)[0] & 0xFF;
                    dst = new String(readExactly(in, dlen), java.nio.charset.StandardCharsets.US_ASCII);
                } else if (atyp == 4) {
                    dst = InetAddress.getByAddress(readExactly(in, 16)).getHostAddress();
                } else {
                    out.write(reply(8));
                    out.flush();
                    client.close();
                    return;
                }
                final byte[] pb = readExactly(in, 2);
                final int dstPort = ((pb[0] & 0xFF) << 8) | (pb[1] & 0xFF);

                if (dst.indexOf(':') >= 0) {
                    out.write(reply(5));
                    out.flush();
                    client.close();
                    return;
                }

                if (!isTelegramIp(dst)) {
                    try {
                        final Socket remote = new Socket();
                        remote.connect(new InetSocketAddress(dst, dstPort), 10000);
                        remote.setTcpNoDelay(true);
                        out.write(reply(0));
                        out.flush();
                        bridgeTcp(in, out, remote.getInputStream(), remote.getOutputStream(), client, remote);
                    } catch (Exception e) {
                        out.write(reply(5));
                        out.flush();
                        client.close();
                    }
                    return;
                }

                out.write(reply(0));
                out.flush();

                final byte[] init;
                try {
                    init = readExactly(in, 64);
                } catch (Exception e) {
                    client.close();
                    return;
                }
                if (isHttpTransport(init)) {
                    client.close();
                    return;
                }

                final int[] parsed = dcFromInit(init);
                int dc = parsed != null ? parsed[0] : 0;
                boolean media = parsed != null && parsed[1] == 1;
                final int proto = parsed != null ? parsed[2] : 0;
                byte[] initData = init;
                boolean patched = false;

                final int[] known = IP_TO_DC.get(dst);
                if (dc == 0 && known != null) {
                    dc = known[0];
                    media = known[1] == 1;
                    if (DC_OPT.containsKey(dc)) {
                        initData = patchInitDc(init, media ? -dc : dc);
                        patched = true;
                    }
                }

                if (dc == 0 || !DC_OPT.containsKey(dc)) {
                    tcpFallback(in, out, dst, dstPort, initData, client);
                    return;
                }

                final int key = dc * (media ? -1 : 1);
                final long now = System.currentTimeMillis();
                if (wsBlacklist.contains(key)) {
                    tcpFallback(in, out, dst, dstPort, initData, client);
                    return;
                }
                final Long until = failUntil.get(key);
                final int timeout = until != null && now < until ? 2000 : 10000;

                WebSocket ws = null;
                boolean allRedirects = true;
                boolean sawRedirect = false;
                for (String domain : wsDomains(dc, media)) {
                    try {
                        ws = WebSocket.connect(DC_OPT.get(dc), domain, timeout);
                        allRedirects = false;
                        break;
                    } catch (WsHandshakeError e) {
                        if (e.isRedirect()) {
                            sawRedirect = true;
                        } else {
                            allRedirects = false;
                        }
                    } catch (Exception e) {
                        allRedirects = false;
                    }
                }

                if (ws == null) {
                    // Telegram's own addresses are unreachable from this network: go through the Yologram relay
                    for (String domain : wsDomains(dc, media)) {
                        try {
                            ws = WebSocket.connect(RELAY_IP, RELAY_HOST, RELAY_PORT, "/tg/" + domain.replace(".web.telegram.org", "") + "/apiws", 10000);
                            allRedirects = false;
                            sawRedirect = false;
                            break;
                        } catch (Exception ignore) {
                        }
                    }
                }

                if (ws == null) {
                    if (sawRedirect && allRedirects) {
                        wsBlacklist.add(key);
                    } else {
                        failUntil.put(key, now + 30000);
                    }
                    tcpFallback(in, out, dst, dstPort, initData, client);
                    return;
                }
                failUntil.remove(key);

                MsgSplitter splitter = null;
                if (proto != 0 && (patched || media || proto != PROTO_INTERMEDIATE)) {
                    try {
                        splitter = new MsgSplitter(initData, proto);
                    } catch (Exception ignore) {
                    }
                }
                ws.send(initData);
                bridgeWs(in, out, ws, client, splitter);
            } catch (Exception ignore) {
            } finally {
                try {
                    client.close();
                } catch (Exception ignore) {
                }
            }
        }

        private void tcpFallback(InputStream in, OutputStream out, String dst, int port, byte[] init, Socket client) {
            try {
                final Socket remote = new Socket();
                remote.connect(new InetSocketAddress(dst, port), 10000);
                remote.setTcpNoDelay(true);
                remote.getOutputStream().write(init);
                remote.getOutputStream().flush();
                bridgeTcp(in, out, remote.getInputStream(), remote.getOutputStream(), client, remote);
            } catch (Exception ignore) {
            }
        }

        private void bridgeTcp(InputStream cIn, OutputStream cOut, InputStream rIn, OutputStream rOut, Socket client, Socket remote) {
            final Thread up = new Thread(() -> pipe(cIn, rOut, client, remote), "ws-proxy-up");
            final Thread down = new Thread(() -> pipe(rIn, cOut, client, remote), "ws-proxy-down");
            up.setDaemon(true);
            down.setDaemon(true);
            up.start();
            down.start();
            try {
                up.join();
                down.join();
            } catch (InterruptedException ignore) {
            }
        }

        private void pipe(InputStream src, OutputStream dst, Socket a, Socket b) {
            final byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = src.read(buf)) != -1) {
                    dst.write(buf, 0, n);
                    dst.flush();
                }
            } catch (Exception ignore) {
            } finally {
                try {
                    a.close();
                } catch (Exception ignore) {
                }
                try {
                    b.close();
                } catch (Exception ignore) {
                }
            }
        }

        private void bridgeWs(InputStream cIn, OutputStream cOut, WebSocket ws, Socket client, MsgSplitter splitter) {
            final Thread up = new Thread(() -> {
                try {
                    final byte[] buf = new byte[65536];
                    int n;
                    while ((n = cIn.read(buf)) != -1) {
                        final byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        if (splitter != null) {
                            final List<byte[]> parts = splitter.split(chunk);
                            if (parts.isEmpty()) continue;
                            if (parts.size() > 1) ws.sendBatch(parts);
                            else ws.send(parts.get(0));
                        } else {
                            ws.send(chunk);
                        }
                    }
                    if (splitter != null) {
                        final byte[] rest = splitter.flush();
                        if (rest != null) ws.send(rest);
                    }
                } catch (Exception ignore) {
                } finally {
                    ws.close();
                    try {
                        client.close();
                    } catch (Exception ignore) {
                    }
                }
            }, "ws-proxy-c2s");
            final Thread down = new Thread(() -> {
                try {
                    byte[] data;
                    while ((data = ws.recv()) != null) {
                        cOut.write(data);
                        cOut.flush();
                    }
                } catch (Exception ignore) {
                } finally {
                    try {
                        client.close();
                    } catch (Exception ignore) {
                    }
                    ws.close();
                }
            }, "ws-proxy-s2c");
            up.setDaemon(true);
            down.setDaemon(true);
            up.start();
            down.start();
            try {
                up.join();
                down.join();
            } catch (InterruptedException ignore) {
            }
        }

        private static byte[] readExactly(InputStream in, int n) throws IOException {
            final byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                final int r = in.read(buf, off, n - off);
                if (r == -1) throw new EOFException("closed");
                off += r;
            }
            return buf;
        }
    }
}
