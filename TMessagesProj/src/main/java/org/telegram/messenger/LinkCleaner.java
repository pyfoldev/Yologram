package org.telegram.messenger;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LinkCleaner {

    private static final Set<String> TRACKERS = new HashSet<>(Arrays.asList(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name",
        "fbclid", "gclid", "dclid", "yclid", "msclkid", "mc_cid", "mc_eid", "igshid", "igsh",
        "_hsenc", "_hsmi", "vero_id", "ref_src", "ref_url", "spm", "scid", "twclid"));

    public static Uri clean(Uri uri) {
        if (uri == null || !NullgramConfig.cleanLinks()) {
            return uri;
        }
        final String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || !uri.isHierarchical()) {
            return uri;
        }
        final Set<String> names = uri.getQueryParameterNames();
        boolean found = false;
        for (String n : names) {
            if (TRACKERS.contains(n.toLowerCase())) {
                found = true;
                break;
            }
        }
        if (!found) {
            return uri;
        }
        final Uri.Builder b = uri.buildUpon().clearQuery();
        for (String n : names) {
            if (!TRACKERS.contains(n.toLowerCase())) {
                for (String v : uri.getQueryParameters(n)) {
                    b.appendQueryParameter(n, v);
                }
            }
        }
        return b.build();
    }
}
