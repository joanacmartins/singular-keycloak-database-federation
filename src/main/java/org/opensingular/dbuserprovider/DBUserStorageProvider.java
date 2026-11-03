package org.opensingular.dbuserprovider;

import lombok.extern.jbosslog.JBossLog;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.keycloak.storage.user.UserRegistrationProvider;
import org.opensingular.dbuserprovider.model.QueryConfigurations;
import org.opensingular.dbuserprovider.model.UserAdapter;
import org.opensingular.dbuserprovider.persistence.DataSourceProvider;
import org.opensingular.dbuserprovider.persistence.UserRepository;
import org.opensingular.dbuserprovider.util.PagingUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * User Storage Provider adapted for Keycloak 26.
 *
 * Changes from previous version:
 * - {@link UserLookupProvider}: since Keycloak 19, methods were renamed to
 *   getUserById(RealmModel, String), getUserByUsername(RealmModel, String) and
 *   getUserByEmail(RealmModel, String), with realm as the first parameter.
 * - {@link UserQueryProvider}: getUsers and several overloads were removed;
 *   search now returns Stream and the consolidated API is searchForUserStream(RealmModel, Map, ...).
 * - {@code getDisableableCredentialTypes} returns {@code Stream<String>}.
 */
@JBossLog
public class DBUserStorageProvider implements UserStorageProvider,
                                              UserLookupProvider,
                                              UserQueryProvider,
                                              CredentialInputUpdater,
                                              CredentialInputValidator,
                                              UserRegistrationProvider {

    private final KeycloakSession session;
    private final ComponentModel  model;
    private final UserRepository  repository;
    private final boolean         allowDatabaseToOverwriteKeycloak;
    private final boolean         allowPrefixSearch;

    /** Upper bound for the page size of a prefix search. */
    static final int MAX_PREFIX_RESULTS = 100;

    DBUserStorageProvider(KeycloakSession session, ComponentModel model, DataSourceProvider dataSourceProvider, QueryConfigurations queryConfigurations) {
        this(session, model, new UserRepository(dataSourceProvider, queryConfigurations),
             queryConfigurations.getAllowDatabaseToOverwriteKeycloak(), queryConfigurations.getAllowPrefixSearch());
    }

    DBUserStorageProvider(KeycloakSession session, ComponentModel model, UserRepository repository,
                          boolean allowDatabaseToOverwriteKeycloak, boolean allowPrefixSearch) {
        this.session    = session;
        this.model      = model;
        this.repository = repository;
        this.allowDatabaseToOverwriteKeycloak = allowDatabaseToOverwriteKeycloak;
        this.allowPrefixSearch = allowPrefixSearch;
    }

    private UserModel toUserModel(RealmModel realm, Map<String, String> user) {
        return new UserAdapter(session, realm, model, user, allowDatabaseToOverwriteKeycloak);
    }


    // ------------------------------------------------------------------
    // CredentialInputValidator
    // ------------------------------------------------------------------

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType);
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        log.infov("isValid user credential: userId={0}", user.getId());

        if (!supportsCredentialType(input.getType())) {
            return false;
        }

        String challengeResponse = input.getChallengeResponse();
        if (challengeResponse == null) {
            return false;
        }

        // In Keycloak 26, CachedUserModel was moved to an internal module (keycloak-model-infinispan)
        // not accessible to external providers via Maven. Cache invalidation when
        // allowDatabaseToOverwriteKeycloak is active is managed by Keycloak itself.
        // Here we only validate the credential directly against the repository.
        return repository.validateCredentials(user.getUsername(), challengeResponse);
    }

    // ------------------------------------------------------------------
    // CredentialInputUpdater
    // ------------------------------------------------------------------

    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        log.infov("updating credential: realm={0} user={1}", realm.getId(), user.getUsername());

        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel)) {
            return false;
        }

        UserCredentialModel cred = (UserCredentialModel) input;
        return repository.updateCredentials(user.getUsername(), cred.getChallengeResponse());
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
    }

    @Override
    public Stream<String> getDisableableCredentialTypesStream(RealmModel realm, UserModel user) {
        return Stream.empty();
    }

    // ------------------------------------------------------------------
    // UserStorageProvider
    // ------------------------------------------------------------------

    @Override
    public void preRemove(RealmModel realm) {
        log.infov("pre-remove realm");
    }

    @Override
    public void preRemove(RealmModel realm, GroupModel group) {
        log.infov("pre-remove group");
    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {
        log.infov("pre-remove role");
    }

    @Override
    public void close() {
        log.debugv("closing");
    }

    // ------------------------------------------------------------------
    // UserLookupProvider (Keycloak 19+)
    // ------------------------------------------------------------------

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        log.infov("lookup user by id: realm={0} userId={1}", realm.getId(), id);

        String externalId = StorageId.externalId(id);
        Map<String, String> user = repository.findUserById(externalId);

        if (user == null) {
            log.debugv("findUserById returned null, skipping creation of UserAdapter, expect login error");
            return null;
        }
        return toUserModel(realm, user);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        log.infov("lookup user by username: realm={0}", realm.getId());

        return repository.findUserByUsername(username)
                .map(u -> toUserModel(realm, u))
                .orElse(null);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        log.infov("lookup user by email: realm={0}", realm.getId());
        return getUserByUsername(realm, email);
    }

    // ------------------------------------------------------------------
    // UserQueryProvider (Keycloak 19+) 
    // ------------------------------------------------------------------

    // Counting is only used for pagination in the admin console. The legacy table is too
    // large to count, so only exact lookups and the first page of a prefix search are counted.

    @Override
    public int getUsersCount(RealmModel realm) {
        return 0;
    }

    @Override
    public int getUsersCount(RealmModel realm, boolean includeServiceAccount) {
        return 0;
    }

    @Override
    public int getUsersCount(RealmModel realm, Set<String> groupIds) {
        return 0;
    }

    @Override
    public int getUsersCount(RealmModel realm, String search) {
        return count(SearchCriteria.from(search));
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params) {
        return count(SearchCriteria.from(params));
    }

    @Override
    public int getUsersCount(RealmModel realm, String search, Set<String> groupIds) {
        return count(SearchCriteria.from(search));
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params, Set<String> groupIds) {
        return count(SearchCriteria.from(params));
    }

    /**
     * User search for Keycloak 19+. Only exact lookups by email or id and, when enabled,
     * prefix searches reach the database (see {@link SearchCriteria}); any other search
     * returns no users.
     */
    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        SearchCriteria criteria = SearchCriteria.from(params);
        log.infov("search for users: realm={0} keys={1} criteria={2} firstResult={3} maxResults={4}",
                realm.getId(), params == null ? null : params.keySet(), criteria, firstResult, maxResults);

        if (maxResults != null && maxResults <= 0) {
            return Stream.empty();
        }
        // Keycloak sends -1 when the admin API call has no "first" parameter.
        int first = firstResult == null ? 0 : Math.max(firstResult, 0);
        if (criteria.getType() == SearchCriteria.Type.PREFIX) {
            int max = maxResults == null ? MAX_PREFIX_RESULTS : Math.min(maxResults, MAX_PREFIX_RESULTS);
            return findByPrefix(criteria, first, max).stream().map(u -> toUserModel(realm, u));
        }
        // An exact lookup has at most one result, so any page after the first is empty.
        if (first > 0) {
            return Stream.empty();
        }
        return findExact(criteria).map(u -> toUserModel(realm, u)).stream();
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        log.infov("search for users by attribute: realm={0} attrName={1} attrValue={2}", realm.getId(), attrName, attrValue);
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        log.infov("get group members: realm={0} groupId={1} firstResult={2} maxResults={3}",
                realm.getId(), group.getId(), firstResult, maxResults);
        return Stream.empty();
    }

    // ------------------------------------------------------------------
    // UserRegistrationProvider
    // ------------------------------------------------------------------

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        // According to the docs: "If your provider has a configuration switch to turn off
        // adding a user, returning null from this method will skip the provider and call the next one."
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        boolean userRemoved = repository.removeUser();
        if (userRemoved) {
            log.infov("deleted keycloak user: realm={0} userId={1} username={2}",
                    realm.getId(), user.getId(), user.getUsername());
        }
        return userRemoved;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Optional<Map<String, String>> findExact(SearchCriteria criteria) {
        switch (criteria.getType()) {
            case EMAIL:
                return repository.findUserByUsername(criteria.getValue());
            case ID:
                return Optional.ofNullable(repository.findUserById(criteria.getValue()));
            default:
                return Optional.empty();
        }
    }

    private List<Map<String, String>> findByPrefix(SearchCriteria criteria, int first, int max) {
        if (!allowPrefixSearch) {
            return Collections.emptyList();
        }
        return repository.findUsersByPrefix(criteria.getValue(), new PagingUtil.Pageable(first, max));
    }

    private int count(SearchCriteria criteria) {
        if (criteria.getType() == SearchCriteria.Type.PREFIX) {
            // Bounded by the first page: a count(*) over a short prefix could still read many rows.
            return findByPrefix(criteria, 0, MAX_PREFIX_RESULTS).size();
        }
        return findExact(criteria).isPresent() ? 1 : 0;
    }
}
