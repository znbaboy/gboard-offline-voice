package app.template.extension.gboard;

import android.net.Uri;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.Locale;

/**
 * Network allowlist for Gboard. Every outgoing request Gboard makes passes through here first.
 * With the "GIF search" switch off, nothing gets out. With it on, only:
 *  - Tenor GIF search (tenor.googleapis.com/v2/search), cut down to the search words plus the few
 *    parameters needed to get results back (Gboard's built-in key, page size/position, format,
 *    content filter). Locale, country, client id etc. are dropped.
 *  - Tenor's GIF category list (no user input; key + content filter only), which the GIF panel needs.
 *  - Downloading GIF images from Tenor's media servers.
 * Everything else is pointed at a dead local address, so it fails like a network error.
 */
@SuppressWarnings("unused")
public final class NetGuard {
    private static final String BLOCKED = "https://127.0.0.1:9/blocked";
    private static final String[] SEARCH_PARAMS = {"q", "key", "limit", "pos", "media_filter", "contentfilter"};
    private static final String[] CATEGORY_PARAMS = {"key", "type", "contentfilter"};
    private static volatile long lastNote;
    /**
     * Tenor answers 403 when the request is stripped, so GIF requests go out as Gboard builds them
     * (search words, built-in key, language, country, Gboard client id).
     */
    private static final boolean TEST_UNCHANGED = true;

    private NetGuard() {
    }

    /** Hook: URL string given to Cronet / HttpEngine. */
    public static String url(String url) {
        try {
            String out = filter(url);
            if (out == null) {
                note(url);
                return BLOCKED;
            }
            return out;
        } catch (Throwable t) {
            return BLOCKED;
        }
    }

    /** Hook: URL object about to be opened. */
    public static URL url(URL url) {
        try {
            if (url == null) return null;
            String out = url(url.toString());
            return out.equals(url.toString()) ? url : new URL(out);
        } catch (Throwable t) {
            try {
                return new URL(BLOCKED);
            } catch (Throwable ignored) {
                return url;
            }
        }
    }

    /** Hook: raw socket connect. Only loopback is allowed. */
    public static SocketAddress address(SocketAddress a) {
        try {
            if (a instanceof InetSocketAddress) {
                InetSocketAddress s = (InetSocketAddress) a;
                InetAddress ip = s.getAddress();
                if (ip != null && ip.isLoopbackAddress()) return a;
                String h = s.getHostString();
                if ("localhost".equals(h)) return a;
            }
            VoiceDiagnostics.note("net blocked: socket");
        } catch (Throwable ignored) {
        }
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 9);
    }

    /** Hook: host name given to SocketFactory.createSocket. Raw sockets may only reach loopback. */
    public static String host(String h) {
        if (h == null || "localhost".equals(h) || h.startsWith("127.") || "::1".equals(h)) return h;
        VoiceDiagnostics.note("net blocked: socket " + h);
        return "127.0.0.1";
    }

    /** Hook: address given to SocketFactory.createSocket. Raw sockets may only reach loopback. */
    public static InetAddress addr(InetAddress a) {
        if (a == null || a.isLoopbackAddress()) return a;
        VoiceDiagnostics.note("net blocked: socket " + a.getHostAddress());
        return InetAddress.getLoopbackAddress();
    }

    /** Returns the URL to use, or null to block. */
    static String filter(String url) {
        if (url == null) return null;
        // GIF search switch off: Gboard has no network access at all.
        if (!OnlineFeatures.gifEnabled()) return null;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        String host = u.getHost();
        if (scheme == null || host == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;

        // GIF images.
        if (host.endsWith(".tenor.com") || host.endsWith(".tenor.co")) {
            if (host.startsWith("media") || host.startsWith("c.")) return url;
            return null;
        }
        // GIF search only, stripped to the needed parameters.
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (host.equals("tenor.googleapis.com")) {
            // GIF search: only the search words + what's needed to get results back.
            if (path.equals("/v2/search")) return allowed(path, TEST_UNCHANGED ? url : rebuild(u, path, SEARCH_PARAMS));
            // Category list for the GIF panel (it shows an error without it). Carries no user input:
            // only the built-in key and content filter are kept (no language/country/IDs).
            if (path.equals("/v2/categories")) return allowed(path, TEST_UNCHANGED ? url : rebuild(u, path, CATEGORY_PARAMS));
        }
        return null;
    }

    private static String rebuild(Uri u, String path, String[] keep) {
        Uri.Builder b = new Uri.Builder().scheme("https").authority("tenor.googleapis.com").path(path);
        for (String p : keep) {
            String v = u.getQueryParameter(p);
            if (v != null) b.appendQueryParameter(p, v);
        }
        return b.build().toString();
    }

    private static String allowed(String path, String url) {
        VoiceDiagnostics.note("net allowed: tenor " + path); // never the query
        return url;
    }

    private static void note(String url) {
        long now = System.currentTimeMillis();
        if (now - lastNote < 500) return;
        lastNote = now;
        try {
            // Host and path only, never the query (which may hold search words).
            Uri u = Uri.parse(url);
            VoiceDiagnostics.note("net blocked: " + u.getScheme() + "://" + u.getHost() + u.getPath());
        } catch (Throwable ignored) {
        }
    }
}
