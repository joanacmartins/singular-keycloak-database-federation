package org.opensingular.dbuserprovider;

import org.keycloak.models.UserModel;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Translates the search parameters sent by Keycloak into a single lookup that
 * can be answered by an indexed query on the legacy database.
 *
 * Supported lookups:
 * - {@link Type#EMAIL}: answered by the "findByUsername" query (e.g. {@code email = ?})
 * - {@link Type#ID}: answered by the "findById" query (e.g. {@code id = ?})
 * - {@link Type#PREFIX}: a term ending with '%' or '*' (e.g. {@code meu-email%}), answered by
 *   the "findBySearchTerm" query (e.g. {@code email like ?}) with the value {@code meu-email%}.
 *   A trailing wildcard can still use an index.
 *
 * Anything else (wildcards at the start or in the middle, short prefixes, name filters,
 * flags only) resolves to {@link Type#NONE} and must not reach the database, because a
 * {@code LIKE '%term%'} cannot use an index and forces a full table scan.
 */
public final class SearchCriteria {

    public enum Type { NONE, EMAIL, ID, PREFIX }

    private static final Pattern NUMERIC = Pattern.compile("\\d{1,19}");

    /** Minimum number of fixed characters before the first wildcard in a prefix search. */
    static final int MIN_PREFIX_LENGTH = 3;

    private static final SearchCriteria NONE = new SearchCriteria(Type.NONE, null);

    private final Type   type;
    private final String value;

    private SearchCriteria(Type type, String value) {
        this.type  = type;
        this.value = value;
    }

    public static SearchCriteria none() {
        return NONE;
    }

    /**
     * Builds the criteria from the parameter map passed to
     * {@code searchForUserStream}/{@code getUsersCount}. Only {@link UserModel#EMAIL},
     * {@link UserModel#USERNAME} and {@link UserModel#SEARCH} are considered; flags such as
     * {@link UserModel#EXACT} or {@link UserModel#INCLUDE_SERVICE_ACCOUNT} are ignored.
     */
    public static SearchCriteria from(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return NONE;
        }
        String email = params.get(UserModel.EMAIL);
        if (isNotBlank(email)) {
            return email(email);
        }
        String username = params.get(UserModel.USERNAME);
        if (isNotBlank(username)) {
            return from(username);
        }
        String search = params.get(UserModel.SEARCH);
        if (isNotBlank(search)) {
            return from(search);
        }
        return NONE;
    }

    /**
     * Builds the criteria from a free search term: a number is a user id, a term with
     * '@' is an email and a term ending with '%' or '*' is a prefix. Surrounding double
     * quotes (Keycloak's exact search syntax) are removed.
     */
    public static SearchCriteria from(String search) {
        String term = normalize(search);
        if (term == null) {
            return NONE;
        }
        if (isPrefix(term)) {
            return prefix(term);
        }
        if (hasWildcard(term)) {
            return NONE;
        }
        if (NUMERIC.matcher(term).matches()) {
            return new SearchCriteria(Type.ID, term);
        }
        return email(term);
    }

    private static SearchCriteria email(String email) {
        String term = normalize(email);
        if (term != null && isPrefix(term)) {
            return prefix(term);
        }
        if (term == null || hasWildcard(term) || term.indexOf('@') <= 0) {
            return NONE;
        }
        return new SearchCriteria(Type.EMAIL, term.toLowerCase(Locale.ROOT));
    }

    private static boolean isPrefix(String term) {
        return term.endsWith("%") || term.endsWith("*");
    }

    /**
     * Only a wildcard at the end is allowed, after at least {@link #MIN_PREFIX_LENGTH} fixed
     * characters ('_' is also a LIKE wildcard, so it ends the fixed part).
     */
    private static SearchCriteria prefix(String term) {
        String fixed = term.replaceAll("[%*]+$", "");
        if (hasWildcard(fixed)) {
            return NONE;
        }
        int underscore = fixed.indexOf('_');
        int fixedLength = underscore < 0 ? fixed.length() : underscore;
        if (fixedLength < MIN_PREFIX_LENGTH) {
            return NONE;
        }
        return new SearchCriteria(Type.PREFIX, fixed.toLowerCase(Locale.ROOT) + "%");
    }

    private static boolean hasWildcard(String term) {
        return term.contains("*") || term.contains("%");
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String term = raw.trim();
        if (term.length() >= 2 && term.startsWith("\"") && term.endsWith("\"")) {
            term = term.substring(1, term.length() - 1).trim();
        }
        if (term.isEmpty() || term.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return term;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public Type getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SearchCriteria)) return false;
        SearchCriteria that = (SearchCriteria) o;
        return type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        // Never print the value: it may be a customer's email.
        return "SearchCriteria{" + type + "}";
    }
}
