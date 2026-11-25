package it.reno.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML/JSON search over the Tor SOCKS tunnel, without JavaScript. */
final class WebSearch {
    static final class Result {
        final String title;
        final String url;
        final String snippet;
        final String host;

        Result(String title, String url, String snippet) {
            this(title, url, snippet, hostOf(url));
        }

        Result(String title, String url, String snippet, String host) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.host = host == null || host.isEmpty() ? hostOf(url) : host;
        }
    }

    interface Fetcher {
        String get(String host, String path) throws Exception;
    }

    private static final Pattern LINK_TAG = Pattern.compile(
            "<a\\b[^>]*class='result-link'[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern SNIPPET = Pattern.compile(
            "<td class='result-snippet'>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern UDDG = Pattern.compile("[?&]uddg=([^&]+)");

    // Brave Search: server-rendered snippets, no JavaScript needed.
    private static final Pattern BRAVE_BLOCK = Pattern.compile(
            "<div class=\"snippet [^\"]*\" data-pos=\"\\d+\" data-type=\"web\"", Pattern.DOTALL);
    private static final Pattern BRAVE_HREF = Pattern.compile("href=\"(/go/v1/[^\"]+)\"");
    private static final Pattern BRAVE_TITLE = Pattern.compile(
            "class=\"title[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern BRAVE_CITE = Pattern.compile(
            "<cite class=\"snippet-url[^\"]*\"[^>]*>(.*?)</cite>", Pattern.DOTALL);
    private static final Pattern BRAVE_SNIPPET = Pattern.compile(
            "class=\"generic-snippet[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern BRAVE_DEST = Pattern.compile(
            "<a[^>]+class=\"destination[^\"]*\"[^>]+href=\"(https?://[^\"]+)\"");

    /** Filled by {@link #search}: explains which sources answered and which refused. */
    static volatile String lastNote = "";
    /** Brave answers 429 to non-browser TLS fingerprints: stop retrying for a while. */
    private static volatile long braveBlockedUntil = 0;

    private WebSearch() { }

    static List<Result> search(String query, Fetcher fetcher) throws Exception {
        lastNote = "";
        Exception lastFailure = null;
        List<Result> found = new ArrayList<>();
        if (System.currentTimeMillis() >= braveBlockedUntil) {
            try {
                found = brave(query, fetcher);
            } catch (Exception failure) {
                lastFailure = failure;
                if (String.valueOf(failure.getMessage()).contains("429")) {
                    braveBlockedUntil = System.currentTimeMillis() + 30 * 60 * 1000L;
                }
            }
        } else {
            lastFailure = new IOException("HTTP 429 (gia' rifiutato negli ultimi 30 minuti)");
        }
        if (!found.isEmpty()) return found;
        String braveProblem = lastFailure == null ? "nessun risultato"
                : String.valueOf(lastFailure.getMessage());
        try {
            found = wikipedia("it.wikipedia.org", query, fetcher);
        } catch (Exception failure) {
            lastFailure = failure;
        }
        if (found.size() < 3) {
            try {
                for (Result extra : wikipedia("en.wikipedia.org", query, fetcher)) {
                    boolean seen = false;
                    for (Result already : found) {
                        if (already.title.equalsIgnoreCase(extra.title)) seen = true;
                    }
                    if (!seen) found.add(extra);
                }
            } catch (Exception ignored) {
            }
        }
        if (!found.isEmpty()) {
            lastNote = "Brave Search non ha risposto da Tor (" + braveProblem
                    + "): risultati da Wikipedia.";
            return found;
        }
        if (lastFailure != null) throw lastFailure;
        lastNote = "Brave Search non ha risposto da Tor (" + braveProblem
                + ") e Wikipedia non ha trovato voci.";
        return new ArrayList<>();
    }

    /**
     * Brave Search works from Tor exits where DuckDuckGo, Bing and SearXNG reject
     * automated queries. Result links are cloaked behind /go/v1 tokens, so the real
     * destination is read from the interstitial page before the link is shown.
     */
    static List<Result> brave(String query, Fetcher fetcher) throws Exception {
        String body = fetcher.get("search.brave.com",
                "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"));
        List<Result> results = new ArrayList<>();
        Matcher block = BRAVE_BLOCK.matcher(body);
        List<int[]> spans = new ArrayList<>();
        while (block.find()) spans.add(new int[]{block.start(), block.end()});
        for (int i = 0; i < spans.size() && results.size() < 6; i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            String part = body.substring(spans.get(i)[1], Math.min(end, spans.get(i)[1] + 8000));
            String title = first(BRAVE_TITLE, part);
            String cloaked = first(BRAVE_HREF, part);
            String cite = first(BRAVE_CITE, part);
            if (title.isEmpty() || cloaked.isEmpty()) continue;
            String snippet = clean(first(BRAVE_SNIPPET, part));
            String host = hostFromCite(cite);
            String url = resolveBrave(cloaked, fetcher);
            if (url.isEmpty()) url = fromCite(cite);
            if (url.isEmpty()) url = "https://search.brave.com" + cloaked;
            results.add(new Result(title, url, snippet, host));
        }
        return results;
    }

    /** Reads the real destination from Brave's "check this link" interstitial. */
    static String resolveBrave(String cloaked, Fetcher fetcher) {
        if (cloaked.isEmpty()) return "";
        try {
            Matcher dest = BRAVE_DEST.matcher(fetcher.get("search.brave.com", cloaked));
            if (dest.find()) return dest.group(1).replace("&amp;", "&").trim();
        } catch (Exception ignored) {
        }
        return "";
    }

    /** "https://it.wikipedia.org › wiki › Divina_Commedia" -> real URL, when unambiguous. */
    static String fromCite(String cite) {
        String text = clean(cite);
        if (text.isEmpty()) return "";
        int scheme = text.indexOf("://");
        String host = scheme >= 0 ? text.substring(0, scheme) : "https://" + text;
        String rest = scheme >= 0 ? text.substring(scheme + 3) : text;
        int cut = rest.indexOf(" › ");
        String path = cut >= 0 ? rest.substring(cut + 3).replace(" › ", "/") : "";
        if (path.contains(" ")) return "";
        return host + (path.isEmpty() ? "" : "/" + path);
    }

    static String hostFromCite(String cite) {
        String text = clean(cite);
        if (text.isEmpty()) return "";
        int scheme = text.indexOf("://");
        String rest = scheme >= 0 ? text.substring(scheme + 3) : text;
        int cut = rest.indexOf(" › ");
        String host = cut >= 0 ? rest.substring(0, cut) : rest;
        return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    static List<Result> duckDuckGoLite(String query, Fetcher fetcher) throws Exception {
        String body = fetcher.get("lite.duckduckgo.com",
                "/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8"));
        List<String> titles = new ArrayList<>();
        List<String> links = new ArrayList<>();
        Matcher link = LINK_TAG.matcher(body);
        while (link.find() && titles.size() < 6) {
            titles.add(clean(link.group(1)));
            Matcher href = HREF.matcher(link.group(0));
            links.add(href.find() ? unwrap(href.group(1)) : "");
        }
        List<String> snippets = new ArrayList<>();
        Matcher snippet = SNIPPET.matcher(body);
        while (snippet.find() && snippets.size() < 6) snippets.add(clean(snippet.group(1)));
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            String url = links.get(i);
            if (url.isEmpty() || titles.get(i).isEmpty()) continue;
            results.add(new Result(titles.get(i), url,
                    i < snippets.size() ? snippets.get(i) : ""));
        }
        return results;
    }

    static List<Result> wikipedia(String query, Fetcher fetcher) throws Exception {
        return wikipedia("it.wikipedia.org", query, fetcher);
    }

    static List<Result> wikipedia(String lang, String query, Fetcher fetcher) throws Exception {
        String body = fetcher.get(lang, "/w/api.php?action=query&list=search"
                + "&format=json&srlimit=4&srsearch=" + java.net.URLEncoder.encode(query, "UTF-8"));
        JSONArray hits = new JSONObject(body).getJSONObject("query").getJSONArray("search");
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.getJSONObject(i);
            String title = hit.optString("title");
            if (title.isEmpty()) continue;
            results.add(new Result(title,
                    "https://" + lang + "/wiki/" + title.replace(' ', '_'),
                    clean(hit.optString("snippet"))));
        }
        return results;
    }

    static List<Result> unmodifiable(List<Result> results) {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    private static String unwrap(String href) {
        String value = href.startsWith("//") ? "https:" + href : href;
        Matcher redirect = UDDG.matcher(value);
        if (redirect.find()) {
            try {
                return URLDecoder.decode(redirect.group(1), "UTF-8");
            } catch (Exception ignored) {
                return "";
            }
        }
        return value;
    }

    private static String clean(String html) {
        String text = TAG.matcher(html).replaceAll(" ");
        text = text.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception invalid) {
            return "";
        }
    }
}
