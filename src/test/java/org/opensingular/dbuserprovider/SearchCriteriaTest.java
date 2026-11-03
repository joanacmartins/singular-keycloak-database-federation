package org.opensingular.dbuserprovider;

import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchCriteriaTest {

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void assertCriteria(SearchCriteria.Type type, String value, SearchCriteria actual) {
        assertEquals(type, actual.getType());
        assertEquals(value, actual.getValue());
    }

    @Test
    void emailWithFlagsIsExactEmail() {
        // GET /users?email=Fulano@X.com&exact=true (KC 26.6.1 UsersResource)
        SearchCriteria c = SearchCriteria.from(params(
                UserModel.EMAIL, "Fulano@X.com ",
                UserModel.EXACT, "true",
                UserModel.INCLUDE_SERVICE_ACCOUNT, "true"));
        assertCriteria(SearchCriteria.Type.EMAIL, "fulano@x.com", c);
    }

    @Test
    void numericUsernameIsId() {
        assertCriteria(SearchCriteria.Type.ID, "123456", SearchCriteria.from(params(UserModel.USERNAME, "123456")));
    }

    @Test
    void usernameWithAtIsEmail() {
        assertCriteria(SearchCriteria.Type.EMAIL, "fulano@x.com", SearchCriteria.from(params(UserModel.USERNAME, "fulano@x.com")));
    }

    @Test
    void searchWithEmailIsExactEmail() {
        assertCriteria(SearchCriteria.Type.EMAIL, "fulano@x.com", SearchCriteria.from(params(UserModel.SEARCH, "fulano@x.com")));
        assertCriteria(SearchCriteria.Type.EMAIL, "fulano@x.com", SearchCriteria.from(params(UserModel.SEARCH, "\"fulano@x.com\"")));
    }

    @Test
    void numericSearchIsId() {
        assertCriteria(SearchCriteria.Type.ID, "42", SearchCriteria.from(params(UserModel.SEARCH, "42")));
        assertCriteria(SearchCriteria.Type.ID, "42", SearchCriteria.from(" 42 "));
    }

    @Test
    void trailingWildcardIsPrefix() {
        assertCriteria(SearchCriteria.Type.PREFIX, "meu-email%", SearchCriteria.from(params(UserModel.SEARCH, "Meu-Email%")));
        assertCriteria(SearchCriteria.Type.PREFIX, "meu-email@%", SearchCriteria.from(params(UserModel.SEARCH, "meu-email@*")));
        assertCriteria(SearchCriteria.Type.PREFIX, "joao%", SearchCriteria.from("joao**"));
        assertCriteria(SearchCriteria.Type.PREFIX, "123%", SearchCriteria.from("123%"));
        assertCriteria(SearchCriteria.Type.PREFIX, "joa_silva%", SearchCriteria.from("joa_silva%"));
        assertCriteria(SearchCriteria.Type.PREFIX, "fulano@x.com%", SearchCriteria.from(params(UserModel.EMAIL, "fulano@x.com%")));
    }

    @Test
    void wildcardOutsideTheEndOrShortPrefixIsNone() {
        for (String term : new String[]{"%", "*", "ab%", "a*", "%meu-email%", "*@x.com", "meu%email%", "me*u%", "jo_ao%", "_abc%"}) {
            assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.SEARCH, term)), term);
        }
    }

    @Test
    void partialSearchWithoutWildcardIsNone() {
        for (String term : new String[]{"", " ", "gmail", "meu-email", "%@x.com", "@x.com", "a b@x.com", "a b%", "\"\""}) {
            assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.SEARCH, term)), term);
            assertEquals(SearchCriteria.none(), SearchCriteria.from(term), term);
        }
    }

    @Test
    void flagsOrNamesOnlyAreNone() {
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.INCLUDE_SERVICE_ACCOUNT, "true")));
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.EXACT, "true", UserModel.ENABLED, "true")));
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.FIRST_NAME, "Fulano", UserModel.LAST_NAME, "Silva")));
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.IDP_ALIAS, "google")));
        assertEquals(SearchCriteria.none(), SearchCriteria.from(Collections.emptyMap()));
        assertEquals(SearchCriteria.none(), SearchCriteria.from((Map<String, String>) null));
        assertEquals(SearchCriteria.none(), SearchCriteria.from((String) null));
    }

    @Test
    void nonEmailUsernameOrEmailIsNone() {
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.EMAIL, "gmail")));
        assertEquals(SearchCriteria.none(), SearchCriteria.from(params(UserModel.USERNAME, "fulano")));
    }

    @Test
    void toStringDoesNotExposeValue() {
        assertEquals("SearchCriteria{EMAIL}", SearchCriteria.from("fulano@x.com").toString());
    }
}
