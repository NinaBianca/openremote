/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.security;

import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.security.IdentityProvider;
import org.openremote.model.event.shared.RealmFilter;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.security.*;
import org.openremote.model.util.TextUtil;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.openremote.manager.asset.AssetStorageService.buildMatchFilter;
import static org.openremote.model.Constants.MASTER_REALM;

// TODO: Normalise interface for Basic and Keycloak providers and add client CRUD
/**
 * SPI for implementations used by {@link ManagerIdentityService}, provides CRUD of
 * {@link User} and {@link Realm}.
 */
public interface ManagerIdentityProvider extends IdentityProvider {

    User[] queryUsers(UserQuery userQuery);

    User getUser(String userId);

    User getUserByUsername(String realm, String username);

    User createUpdateUser(String realm, User user, String password, boolean allowUpdate);

    void deleteUser(String realm, String userId);

    void resetPassword(String realm, String userId, Credential credential);

    String resetSecret(String realm, String userId, String secret);

    void updateUserAttributes(String realm, String userId, Map<String, List<String>> attributes);

    Map<String, List<String>> getUserAttributes(String realm, String userId);

    Role[] getRoles(String realm, String client);

    void updateClientRoles(String realm, String client, Role[] roles);

    Role[] getUserRoles(String realm, String userId, String client);

    Role[] getUserRealmRoles(String realm, String userId);

    void updateUserRoles(String realm, String userId, String client, String...roles);

    void updateUserRealmRoles(String realm, String userId, String...roles);

    boolean isMasterRealmAdmin(String userId);

    boolean isRestrictedUser(AuthContext authContext);

    boolean isUserInRealm(String userId, String realm);

    Realm[] getRealms();

    Realm getRealm(String realm);

    void updateRealm(Realm realm);

    Realm createRealm(Realm realm);

    void deleteRealm(String realm);

    boolean isRealmActiveAndAccessible(AuthContext authContext, Realm realm);

    boolean isRealmActiveAndAccessible(AuthContext authContext, String realm);

    boolean realmExists(String realm);

    /**
     * Superusers can subscribe to all events, regular users must be in the same realm as the filter and any
     * required roles must match. If the authenticated party is a restricted user, this returns <code>false.</code>
     *
     * @return <code>true</code> if the authenticated party can subscribe to events with the given filter.
     */
    boolean canSubscribeWith(AuthContext auth, RealmFilter<?> filter, ClientRole... requiredRoles);

    /**
     * Returns the frontend URL to be used for frontend apps to authenticate
     */
    String getFrontendUrl();

    /*
     * BELOW ARE STATIC HELPER METHODS
     */

    default String[] addRealmRoles(String realm, String userId, String...roles) {
        Set<String> realmRoles = Arrays.stream(getUserRealmRoles(realm, userId)).filter(role -> role.isAssigned() || Arrays.stream(roles).anyMatch(r -> role.getName().equals(r))).map(Role::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        realmRoles.addAll(Arrays.asList(roles));
        return realmRoles.toArray(new String[0]);
    }

    static User[] getUsersFromDb(PersistenceService persistenceService, UserQuery query) {
        StringBuilder sb = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        final UserQuery userQuery = query != null ? query : new UserQuery();

        // BUILD SELECT
        sb.append("SELECT u");

        // BUILD FROM
        sb.append(" FROM User u");
        if (userQuery.assetPredicate != null || userQuery.pathPredicate != null) {
            sb.append(" join UserAssetLink ua on ua.id.userId = u.id");
        }

        // BUILD WHERE
        sb.append(" WHERE 1=1");
        if (userQuery.realmPredicate != null && !TextUtil.isNullOrEmpty(userQuery.realmPredicate.name)) {
            sb.append(" AND u.realm = ?").append(parameters.size() + 1);
            parameters.add(userQuery.realmPredicate.name);
        }
        if (userQuery.assetPredicate != null) {
            sb.append(" AND ua.id.assetId = ?").append(parameters.size() + 1);
            parameters.add(userQuery.assetPredicate.id);
        }
        if (userQuery.pathPredicate != null) {
            sb.append(" AND ?").append(parameters.size() + 1).append(" <@ get_asset_tree_path(ua.asset_id)");
            parameters.add(userQuery.pathPredicate.path);
        }
        if (userQuery.ids != null && userQuery.ids.length > 0) {
            sb.append(" AND u.id IN (?").append(parameters.size() + 1);
            parameters.add(userQuery.ids[0]);

            for (int i = 1; i < userQuery.ids.length; i++) {
                sb.append(",?").append(parameters.size() + 1);
                parameters.add(userQuery.ids[i]);
            }
            sb.append(")");
        }
        if (userQuery.usernames != null && userQuery.usernames.length > 0) {
            sb.append(" and (");
            boolean isFirst = true;

            for (StringPredicate pred : userQuery.usernames) {
                if (!isFirst) {
                    sb.append(" or ");
                }
                isFirst = false;
                final int pos = parameters.size() + 1;
                sb.append("upper(u.username)"); // No case support for username
                sb.append(buildMatchFilter(pred, pos));
                parameters.add(pred.prepareValue());
            }
            sb.append(")");
        }
        if (userQuery.select != null && userQuery.select.excludeRegularUsers) {
            sb.append(" and u.secret IS NOT NULL");
        } else if (userQuery.select != null && userQuery.select.excludeServiceUsers) {
            sb.append(" and u.secret IS NULL");
        }

        // BUILD ORDER BY
        if (userQuery.orderBy != null) {
            if (userQuery.orderBy.property != null) {
                sb.append(" ORDER BY");
                switch(userQuery.orderBy.property) {
                    case CREATED_ON:
                        sb.append(" u.createdOn");
                        break;
                    case FIRST_NAME:
                        sb.append(" u.firstName");
                        break;
                    case LAST_NAME:
                        sb.append(" u.lastName");
                        break;
                    case USERNAME:
                        // Remove service user prefix
                        sb.append(" replace(u.username, '").append(User.SERVICE_ACCOUNT_PREFIX).append("', '')");
                        break;
                    case EMAIL:
                        sb.append(" u.email");
                        break;
                    default:
                        throw new UnsupportedOperationException("Unsupported order by value: " + userQuery.orderBy.property);
                }
                if (userQuery.orderBy.descending) {
                    sb.append(" DESC");
                }
            }
        }

        List<User> users = persistenceService.doReturningTransaction(entityManager -> {
            TypedQuery<User> sqlQuery = entityManager.createQuery(sb.toString(), User.class);
            IntStream.range(0, parameters.size()).forEach(i -> sqlQuery.setParameter(i + 1, parameters.get(i)));

            if (userQuery.limit != null && userQuery.limit > 0) {
                sqlQuery.setMaxResults(userQuery.limit);
            }

            if (userQuery.offset != null && userQuery.offset > 0) {
                sqlQuery.setFirstResult(query.offset);
            }

            return sqlQuery.getResultList();
        });

        if (userQuery.select != null && (userQuery.select.basic || userQuery.select.excludeSystemUsers)) {
            // TODO: Move this within the query
            return users.stream().filter(user -> {
                boolean keep = !userQuery.select.excludeSystemUsers || !user.isSystemAccount();
                if (keep && userQuery.select.basic) {
                    // Clear out data and leave only basic info
                    user.setAttributes(null);
                    user.setEmail(null);
                    user.setRealmId(null);
                    user.setSecret(null);
                }
                return keep;
            }).toArray(User[]::new);
        }
        return users.toArray(new User[0]);
    }

    static User getUserByUsernameFromDb(PersistenceService persistenceService, String realm, String username) {
        return persistenceService.doReturningTransaction(em -> {
            List<User> result =
                em.createQuery("select u from User u where u.realm = :realm and u.username = :username", User.class)
                    .setParameter("realm", realm)
                    .setParameter("username", username)
                    .getResultList();
            return result.size() > 0 ? result.get(0) : null;
        });
    }

    static User getUserByIdFromDb(PersistenceService persistenceService, String userId) {
        return persistenceService.doReturningTransaction(em -> {
            List<User> result =
                em.createQuery("select u from User u where u.id = :userId", User.class)
                    .setParameter("userId", userId)
                    .getResultList();
            return result.size() > 0 ? result.get(0) : null;
        });
    }

    static List<String> getUserIds(PersistenceService persistenceService, String realm, List<String> usernames) {
        return persistenceService.doReturningTransaction(em -> {
            Map<String, String> usernameIdMap = em.createQuery(
                "select u.username, u.id from User u join Realm r on r.id = u.realmId where u.username in :usernames and r.name = :realm", Tuple.class)
                    .setParameter("usernames", usernames)
                    .setParameter("realm", realm)
                    .getResultList()
                    .stream()
                    .collect(
                        Collectors.toMap(
                            tuple -> (String) tuple.get(0),
                            tuple -> (String) tuple.get(1)
                        )
                    );

            return usernames.stream().map(usernameIdMap::get).collect(Collectors.toList());
        });
    }

    static Realm[] getRealmsFromDb(PersistenceService persistenceService) {
        return persistenceService.doReturningTransaction(entityManager -> {
            List<Realm> realms = entityManager.createQuery(
                "select r from Realm r where r.notBefore is null or r.notBefore = 0 or to_timestamp(r.notBefore) <= now()"
                , Realm.class).getResultList();

            // Make sure the master realm is always on top
            realms.sort((o1, o2) -> {
                if (o1.getName().equals(MASTER_REALM))
                    return -1;
                if (o2.getName().equals(MASTER_REALM))
                    return 1;
                return o1.getName().compareTo(o2.getName());
            });

            return realms.toArray(new Realm[realms.size()]);
        });
    }

    static Realm getRealmFromDb(PersistenceService persistenceService, String realm) {
        return persistenceService.doReturningTransaction(em -> {
                List<Realm> realms = em.createQuery("select r from Realm r where r.name = :realm", Realm.class)
                    .setParameter("realm", realm).getResultList();
                return realms.size() == 1 ? realms.get(0) : null;
            }
        );
    }

    static boolean realmExistsFromDb(PersistenceService persistenceService, String realm) {
        return persistenceService.doReturningTransaction(em -> {

            long count = em.createQuery(
                "select count(r) from Realm r where r.name = :realm and r.enabled = true and (r.notBefore is null or r.notBefore = 0 or to_timestamp(r.notBefore) <= now())",
                Long.class).setParameter("realm", realm).getSingleResult();

            return count > 0;
        });
    }

    static boolean userInRealmFromDb(PersistenceService persistenceService, String userId, String realm) {
        return persistenceService.doReturningTransaction(em -> {
            User user = em.find(User.class, userId);
            return (user != null && realm.equals(user.getRealm()));
        });
    }
}
