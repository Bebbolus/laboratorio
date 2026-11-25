package it.reno.lab;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Interprets HTTPS response headers, preserving the source and redirect chain. */
final class OnionLookup {
    private static final Pattern DOMAIN = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    static final int MAX_HOPS = 4;

    interface Transport {
        Response get(URI url) throws Exception;
    }

    static final class Response {
        final int status;
        final String location;
        final String onionLocation;

        Response(int status, String location, String onionLocation) {
            this.status = status;
            this.location = location;
            this.onionLocation = onionLocation;
        }
    }

    static final class Result {
        final String onion;
        final URI source;
        final List<URI> chain;

        Result(String onion, URI source, List<URI> chain) {
            this.onion = onion;
            this.source = source;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        }
    }

    static Result find(String domain, Transport transport) throws Exception {
        URI current = requireHttps(new URI("https://" + domain + "/"));
        Set<URI> visited = new LinkedHashSet<>();
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!visited.add(current)) throw new IOException("redirect loop");
            Response response = transport.get(current);
            if (response.status >= 300 && response.status < 400) {
                if (response.location == null || response.location.length() > 2048) {
                    throw new IOException("redirect without a valid location");
                }
                current = requireHttps(current.resolve(response.location));
                continue;
            }
            if (response.status < 200 || response.status >= 300) throw new IOException("HTTP " + response.status);
            String onion = "";
            if (response.onionLocation != null) {
                onion = response.onionLocation.trim();
                if (!OnionAddress.isValidUrl(onion)) throw new IOException("invalid Onion-Location");
            }
            return new Result(onion, current, new ArrayList<>(visited));
        }
        throw new IOException("too many redirects");
    }

    private static URI requireHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null || uri.toASCIIString().length() > 2048) {
            throw new IOException("unsupported HTTPS destination");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.length() > 253 || !DOMAIN.matcher(host).matches() || host.endsWith(".onion")) {
            throw new IOException("unsupported HTTPS hostname");
        }
        return uri.normalize();
    }
}
