package it.reno.lab;

import org.json.JSONArray;
import org.json.JSONObject;

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

        Result(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
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

    private WebSearch() { }

    static List<Result> search(String query, Fetcher fetcher) throws Exception {
        List<Result> found = duckDuckGoLite(query, fetcher);
        if (found.isEmpty()) found = wikipedia(query, fetcher);
        return found;
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
        String body = fetcher.get("it.wikipedia.org", "/w/api.php?action=query&list=search"
                + "&format=json&srlimit=4&srsearch=" + java.net.URLEncoder.encode(query, "UTF-8"));
        JSONArray hits = new JSONObject(body).getJSONObject("query").getJSONArray("search");
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.getJSONObject(i);
            String title = hit.optString("title");
            if (title.isEmpty()) continue;
            results.add(new Result(title,
                    "https://it.wikipedia.org/wiki/" + title.replace(' ', '_'),
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
