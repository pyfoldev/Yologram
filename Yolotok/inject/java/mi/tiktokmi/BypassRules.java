package mi.tiktokmi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The part of the block bypass that is only Java: reading a proxy address, and
 * pointing the process's own networking at it.
 *
 * Two things are changed, and both are the standard places a Java process looks
 * for a proxy: the default {@link ProxySelector} (what HttpURLConnection and
 * OkHttp ask) and the http/https/socks system properties (what Chromium's
 * Android network stack reads through its proxy-config service). The first
 * values are remembered and put back when the bypass is switched off.
 *
 * Nothing here knows about Android, so it is tested on a plain JVM.
 */
final class BypassRules {

    private BypassRules() {}

    /** A proxy somebody typed in. */
    static final class Spec {
        final boolean socks;
        final String host;
        final int port;

        Spec(boolean socks, String host, int port) {
            this.socks = socks;
            this.host = host;
            this.port = port;
        }

        Proxy proxy() {
            return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(host, port));
        }

        /** The form it is stored in and shown in: scheme, host and port. */
        String text() {
            return (socks ? "socks5://" : "http://")
                    + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /**
     * "host:port", "http://host:port", "socks5://host:port" and friends; IPv6
     * addresses in brackets. Null for anything that is not one of those, which
     * is better than a half-understood address silently sending traffic nowhere.
     */
    static Spec parse(String text) {
        if (text == null) return null;
        String rest = text.trim();
        boolean socks = false;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            String name = rest.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (name.equals("socks") || name.equals("socks5") || name.equals("socks5h")) socks = true;
            else if (!name.equals("http") && !name.equals("https")) return null;
            rest = rest.substring(scheme + 3);
        }
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty() || rest.indexOf('/') >= 0 || rest.indexOf('@') >= 0
                || rest.indexOf(' ') >= 0) {
            return null;
        }
        String host;
        String port;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0 || close + 1 >= rest.length() || rest.charAt(close + 1) != ':') return null;
            host = rest.substring(1, close);
            port = rest.substring(close + 2);
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon <= 0 || rest.indexOf(':') != colon) return null;
            host = rest.substring(0, colon);
            port = rest.substring(colon + 1);
        }
        if (!validHost(host)) return null;
        int number;
        try {
            number = Integer.parseInt(port);
        } catch (NumberFormatException error) {
            return null;
        }
        if (number < 1 || number > 65535) return null;
        return new Spec(socks, host, number);
    }

    private static boolean validHost(String host) {
        if (host.isEmpty() || host.length() > 253) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == ':';
            if (!ok) return false;
        }
        return true;
    }

    /** Traffic to the phone itself never goes through a proxy somewhere else. */
    static boolean local(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.startsWith("127.") || lower.equals("::1")
                || lower.equals("[::1]");
    }

    /** The selector that sends everything but the phone itself through the proxy. */
    static ProxySelector selector(final Spec spec) {
        final List<Proxy> through = Collections.singletonList(spec.proxy());
        final List<Proxy> direct = Collections.singletonList(Proxy.NO_PROXY);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri == null ? null : uri.getHost();
                return local(host) ? direct : new ArrayList<Proxy>(through);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
                // the proxy being down is the user's to notice; falling back to a
                // direct connection would defeat what it is for
            }
        };
    }

    private static final String[] KEYS = {
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts", "socksProxyHost", "socksProxyPort"};

    private static boolean remembered;
    private static ProxySelector originalSelector;
    private static final String[] originalProperties = new String[KEYS.length];

    /** Point the process at the proxy. Safe to call again with another one. */
    static synchronized void install(Spec spec) {
        remember();
        clearProperties();
        if (spec.socks) {
            System.setProperty("socksProxyHost", spec.host);
            System.setProperty("socksProxyPort", String.valueOf(spec.port));
        } else {
            System.setProperty("http.proxyHost", spec.host);
            System.setProperty("http.proxyPort", String.valueOf(spec.port));
            System.setProperty("https.proxyHost", spec.host);
            System.setProperty("https.proxyPort", String.valueOf(spec.port));
        }
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");
        ProxySelector.setDefault(selector(spec));
    }

    /** Put back what was there before the first install. A no-op if nothing was installed. */
    static synchronized void uninstall() {
        if (!remembered) return;
        ProxySelector.setDefault(originalSelector);
        for (int i = 0; i < KEYS.length; i++) {
            if (originalProperties[i] == null) System.clearProperty(KEYS[i]);
            else System.setProperty(KEYS[i], originalProperties[i]);
        }
        remembered = false;
    }

    static synchronized boolean installed() {
        return remembered;
    }

    private static void remember() {
        if (remembered) return;
        originalSelector = ProxySelector.getDefault();
        for (int i = 0; i < KEYS.length; i++) originalProperties[i] = System.getProperty(KEYS[i]);
        remembered = true;
    }

    private static void clearProperties() {
        for (String key : KEYS) System.clearProperty(key);
    }
}
