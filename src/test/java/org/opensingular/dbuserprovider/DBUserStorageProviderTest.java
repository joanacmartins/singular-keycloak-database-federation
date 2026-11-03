package org.opensingular.dbuserprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.ArgumentCaptor;
import org.opensingular.dbuserprovider.persistence.UserRepository;
import org.opensingular.dbuserprovider.util.PagingUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DBUserStorageProviderTest {

    private UserRepository        repository;
    private RealmModel            realm;
    private DBUserStorageProvider provider;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        realm = mock(RealmModel.class);
        provider = newProvider(true);
    }

    private DBUserStorageProvider newProvider(boolean allowPrefixSearch) {
        ComponentModel model = new ComponentModel();
        model.setId("component-id");
        return new DBUserStorageProvider(mock(KeycloakSession.class), model, repository, false, allowPrefixSearch);
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> user(String id) {
        Map<String, String> u = new HashMap<>();
        u.put("id", id);
        u.put("username", id);
        return u;
    }

    private List<UserModel> search(Map<String, String> params, Integer first, Integer max) {
        return provider.searchForUserStream(realm, params, first, max).collect(Collectors.toList());
    }

    @Test
    void exactEmailSearchUsesFindByUsername() {
        when(repository.findUserByUsername("fulano@x.com")).thenReturn(Optional.of(user("123")));

        // UsersResource sends firstResult=-1 when "first" is not informed
        List<UserModel> users = search(params(UserModel.EMAIL, "Fulano@X.com", UserModel.EXACT, "true",
                UserModel.INCLUDE_SERVICE_ACCOUNT, "true"), -1, 1);

        assertEquals(1, users.size());
        assertEquals("f:component-id:123", users.get(0).getId());
        verify(repository).findUserByUsername("fulano@x.com");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void numericUsernameSearchUsesFindById() {
        when(repository.findUserById("123")).thenReturn(user("123"));

        assertEquals(1, search(params(UserModel.USERNAME, "123", UserModel.EXACT, "true"), 0, 100).size());
        verify(repository).findUserById("123");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void notFoundReturnsEmpty() {
        when(repository.findUserByUsername("nobody@x.com")).thenReturn(Optional.empty());
        assertTrue(search(params(UserModel.SEARCH, "nobody@x.com"), null, null).isEmpty());
    }

    @Test
    void searchesWithoutExactCriteriaDoNotQueryTheDatabase() {
        assertTrue(search(params(UserModel.SEARCH, "*", UserModel.INCLUDE_SERVICE_ACCOUNT, "false"), 0, 20).isEmpty());
        assertTrue(search(params(UserModel.SEARCH, "gmail"), 0, 20).isEmpty());
        assertTrue(search(params(UserModel.INCLUDE_SERVICE_ACCOUNT, "true"), 0, 20).isEmpty());
        assertTrue(search(params(UserModel.FIRST_NAME, "Fulano"), 0, 20).isEmpty());
        assertTrue(search(Collections.emptyMap(), null, null).isEmpty());
        assertTrue(provider.searchForUserStream(realm, "gmail").findAny().isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void prefixSearchIsPagedAndCapped() {
        when(repository.findUsersByPrefix(eq("meu-email%"), any())).thenReturn(List.of(user("1"), user("2")));

        assertEquals(2, search(params(UserModel.SEARCH, "Meu-Email%"), -1, 1000).size());
        assertEquals(2, search(params(UserModel.SEARCH, "meu-email*"), 20, 10).size());

        ArgumentCaptor<PagingUtil.Pageable> pages = ArgumentCaptor.forClass(PagingUtil.Pageable.class);
        verify(repository, times(2)).findUsersByPrefix(eq("meu-email%"), pages.capture());
        assertEquals(0, pages.getAllValues().get(0).getFirstResult());
        assertEquals(DBUserStorageProvider.MAX_PREFIX_RESULTS, pages.getAllValues().get(0).getMaxResults());
        assertEquals(20, pages.getAllValues().get(1).getFirstResult());
        assertEquals(10, pages.getAllValues().get(1).getMaxResults());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void prefixCountIsBoundedByTheFirstPage() {
        when(repository.findUsersByPrefix(eq("meu-email%"), any())).thenReturn(List.of(user("1"), user("2")));

        assertEquals(2, provider.getUsersCount(realm, "meu-email%"));
        assertEquals(2, provider.getUsersCount(realm, params(UserModel.SEARCH, "meu-email%")));
    }

    @Test
    void prefixSearchIsDisabledByDefault() {
        DBUserStorageProvider disabled = newProvider(false);

        assertTrue(disabled.searchForUserStream(realm, params(UserModel.SEARCH, "meu-email%"), 0, 20).findAny().isEmpty());
        assertEquals(0, disabled.getUsersCount(realm, "meu-email%"));
        verifyNoInteractions(repository);
    }

    @Test
    void wildcardsAtTheStartNeverQueryTheDatabase() {
        assertTrue(search(params(UserModel.SEARCH, "%meu-email%"), 0, 20).isEmpty());
        assertTrue(search(params(UserModel.SEARCH, "*@x.com"), 0, 20).isEmpty());
        assertEquals(0, provider.getUsersCount(realm, "%gmail%"));
        verifyNoInteractions(repository);
    }

    @Test
    void pagesAfterTheFirstAreEmpty() {
        assertTrue(search(params(UserModel.EMAIL, "fulano@x.com"), 1, 20).isEmpty());
        assertTrue(search(params(UserModel.EMAIL, "fulano@x.com"), 0, 0).isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void totalCountsDoNotQueryTheDatabase() {
        assertEquals(0, provider.getUsersCount(realm));
        assertEquals(0, provider.getUsersCount(realm, true));
        assertEquals(0, provider.getUsersCount(realm, false));
        assertEquals(0, provider.getUsersCount(realm, Set.of("group")));
        assertEquals(0, provider.getUsersCount(realm, params(UserModel.INCLUDE_SERVICE_ACCOUNT, "false")));
        assertEquals(0, provider.getUsersCount(realm, "gmail"));
        assertEquals(0, provider.getUsersCount(realm, "*", Set.of("group")));
        verifyNoInteractions(repository);
    }

    @Test
    void exactCountsUseExactLookups() {
        when(repository.findUserByUsername("fulano@x.com")).thenReturn(Optional.of(user("123")));
        when(repository.findUserByUsername("nobody@x.com")).thenReturn(Optional.empty());
        when(repository.findUserById("123")).thenReturn(user("123"));

        assertEquals(1, provider.getUsersCount(realm, params(UserModel.EMAIL, "fulano@x.com", UserModel.INCLUDE_SERVICE_ACCOUNT, "true")));
        assertEquals(0, provider.getUsersCount(realm, params(UserModel.EMAIL, "nobody@x.com")));
        assertEquals(1, provider.getUsersCount(realm, "fulano@x.com"));
        assertEquals(1, provider.getUsersCount(realm, "123", Set.of("group")));
        assertEquals(1, provider.getUsersCount(realm, params(UserModel.SEARCH, "123"), Set.of("group")));
    }
}
