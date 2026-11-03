package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.resourcemanager.model.FolderRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code folders.search} query: {@code field=value} or {@code field:value} terms joined by
 * {@code AND} (or whitespace), on {@code displayName}, {@code parent}, {@code state} and
 * {@code lifecycleState}. A trailing {@code *} makes a prefix match; values may be quoted, with
 * {@code \"} for spaces. Comparisons ignore case. {@code OR} and {@code NOT} are not supported.
 */
final class FolderQuery {

    private final List<Term> terms;

    private FolderQuery(List<Term> terms) {
        this.terms = terms;
    }

    static FolderQuery parse(String query) {
        List<Term> terms = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return new FolderQuery(terms);
        }
        for (String token : tokenize(query)) {
            if (token.equals("AND")) {
                continue;
            }
            if (token.equals("OR") || token.equals("NOT")) {
                throw GcpException.invalidArgument("Folder search query operator " + token + " is not supported: "
                        + query);
            }
            int separator = separatorIndex(token);
            if (separator <= 0) {
                throw GcpException.invalidArgument("Invalid folder search query: " + query);
            }
            String field = switch (token.substring(0, separator)) {
                case "displayName", "display_name" -> "displayName";
                case "parent" -> "parent";
                case "state", "lifecycleState", "lifecycle_state" -> "state";
                default -> throw GcpException.invalidArgument("Invalid folder search query field '"
                        + token.substring(0, separator) + "': " + query);
            };
            terms.add(new Term(field, token.substring(separator + 1).replace("\"", "")));
        }
        return new FolderQuery(terms);
    }

    boolean matches(FolderRecord folder) {
        return terms.stream().allMatch(term -> term.matches(folder));
    }

    /** Splits on whitespace outside double quotes; a backslash before a quote is dropped. */
    private static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\\' && i + 1 < query.length() && query.charAt(i + 1) == '"') {
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int separatorIndex(String token) {
        int colon = token.indexOf(':');
        int equals = token.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        return equals < 0 ? colon : Math.min(colon, equals);
    }

    private record Term(String field, String value) {
        boolean matches(FolderRecord folder) {
            String actual = switch (field) {
                case "displayName" -> folder.getDisplayName();
                case "parent" -> folder.getParent();
                default -> folder.getState();
            };
            String expected = value.toLowerCase(Locale.ROOT);
            String candidate = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
            if (expected.endsWith("*")) {
                return candidate.startsWith(expected.substring(0, expected.length() - 1));
            }
            return candidate.equals(expected);
        }
    }
}
