# singular-keycloak-database-federation (Keycloak 26 port)

Fork of the original `opensingular/singular-keycloak-database-federation` plugin to **Keycloak 26**, following the approach recommended by the [official Keycloak documentation for the User Storage SPI](https://www.keycloak.org/docs/latest/server_development/index.html#_user-storage-spi).

The plugin lets Keycloak 26 federate users from an external relational database (PostgreSQL, MySQL, Oracle or MS SQL Server through jTDS), authenticate them against a password column and expose the resulting users to any Keycloak realm. SQL queries, JDBC connection details and the password hashing algorithm are configured per provider instance from the Keycloak admin console.

## Features

- User federation from PostgreSQL, MySQL, Oracle or MS SQL Server (jTDS driver shipped) via fully customizable SQL queries.
- Lookup by id, username and e-mail. Admin searches only run exact lookups or, optionally, prefix searches such as `meu-email%` (see [Search behaviour](#search-behaviour)); other partial searches and full listings return no users.
- Password validation against either BCrypt (Blowfish) or any `MessageDigest` hash digest in hex (MD2, MD5, SHA-1, SHA-256, SHA3-224/256/384/512, SHA-384, SHA-512/224, SHA-512/256, SHA-512), plus PBKDF2-SHA256.
- Optional flag to allow Keycloak to delete its local copy of a federated user (the row in the external database is never touched).
- Optional flag to let attributes returned by the SQL query overwrite the values cached in Keycloak.
- HikariCP-backed JDBC pooling (5 s connection timeout), with pool reconfiguration on demand when the provider configuration changes.
- Per-query timeout (`queryTimeout`, default 5 s).

## Build architecture

As recommended by the official documentation, Keycloak 26 providers are deployed as plain JARs in `/opt/keycloak/providers/`. There is no fat-jar (no shade plugin). The Maven build produces, in `dist/`:

- `singular-user-storage-provider.jar` - the provider itself (only the plugin classes).
- HikariCP, BCrypt, the JDBC drivers (PostgreSQL, MySQL, Oracle, jTDS), commons-codec, commons-io, commons-lang3 - each as its own JAR.

All of them are copied together into `/opt/keycloak/providers/`. Keycloak loads them through a flat classloader.

## Changes compared to the previous (Keycloak 17) version

| Item | Before | Now |
| --- | --- | --- |
| Java | 1.8 | 17 |
| Keycloak SPI | 17.0.1 | 26.0.5 |
| `UserLookupProvider` | `getUserById(String, RealmModel)` | `getUserById(RealmModel, String)` |
| `UserQueryProvider` | returned `List<UserModel>` | returns `Stream<UserModel>` |
| Hibernate Dialects | used in `RDBMS` / `PagingUtil` | removed (gone in Hibernate 6) |
| Provider SPI classes | from `keycloak-server-spi-private` | now in the new `keycloak-model-storage` module |
| JDBC drivers | mysql-connector-java 8.0.12, ojdbc8 | mysql-connector-j 8.4.0, ojdbc11 |
| HikariCP | 4.0.3 | 5.1.0 |

## Prerequisites

- JDK 17 or newer
- Maven 3.8 or newer

## How to build

```bash
mvn clean package
```

Output in `dist/`: the provider JAR plus ~10 runtime-dependency JARs.

There are also two helper scripts at the repository root:

- `deployment.sh` - prompts for the absolute path of a local Keycloak installation and copies `dist/*` into `<keycloak>/providers`.
- `deployment-to-docker.sh` - prompts for the name of a running Keycloak container and copies `dist/*` into `/opt/keycloak/providers` inside it.

## How to install on Keycloak 26

1. Stop Keycloak.
2. Copy **all** JARs from `dist/` to `/opt/keycloak/providers/`:

   ```bash
   cp dist/*.jar /opt/keycloak/providers/
   ```

3. Re-run the Keycloak build step:

   ```bash
   /opt/keycloak/bin/kc.sh build
   ```

4. Start Keycloak. Under **User Federation -> Add provider**, the entry `singular-db-user-provider` will appear.

## Quick test with Docker

```bash
docker build -t keycloak-singular .
docker run --rm -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  keycloak-singular start-dev
```

Open http://localhost:8080 (admin/admin).

## Full test stack (Keycloak + PostgreSQL)

```bash
docker compose up --build
```

This brings up a PostgreSQL instance with a sample `users` table (see `docker-compose/init.sql`) and a Keycloak instance with the provider already installed.

In the provider configuration, use:

- **JDBC URL**: `jdbc:postgresql://userdb:5432/userdb`
- **User**: `userdb`
- **Password**: `userdb`
- **RDBMS**: `PostgreSQL`

Sample users (all hashed with BCrypt - see `docker-compose/init.sql`):

| Username | Password |
| --- | --- |
| `alice` | `secret123` |
| `bob` | `secret456` |
| `charlie` | `secret123` |

## Provider configuration reference

When you add the provider in the Keycloak admin console you will see, besides the JDBC connection fields, the following options:

### Connection

- **JDBC URL** - JDBC connection string (default placeholder targets jTDS / SQL Server).
- **JDBC Connection User** / **JDBC Connection Password**.
- **RDBMS** - selects the JDBC driver and the SQL dialect used for pagination. Available values:
  - `PostgreSQL` (driver bundled)
  - `MySQL` (driver bundled)
  - `Oracle` (driver bundled)
  - `MS SQL Server (jtds)` (driver bundled)
  - `MS SQL Server (microsoft)` and `IBM DB2` are exposed in the UI for completeness, but their JDBC drivers are **not** shipped with this build. Drop the corresponding driver JAR into `/opt/keycloak/providers/` if you want to use them.

### Behaviour flags

- **Allow Keycloak's User Delete** - by default, deleting a federated user from Keycloak is rejected. When enabled, Keycloak removes its local representation of the user (the row in the external database is never touched), which is useful to clear sync issues.
- **Allow DB Attributes to Overwrite Keycloak** - by default, once a user is loaded into Keycloak, its cached attributes are kept even when the SQL query starts returning different values. When enabled, attributes from the SQL query always overwrite the cached ones. Behaves best with a `NO_CACHE` cache policy; with a cached policy, attribute reload happens only after the cache entry is older than 500 ms and the username/e-mail no longer match.
- **Allow prefix search** (`allowPrefixSearch`) - disabled by default. When enabled, searches ending with `%` or `*` run the `findBySearchTerm` query. See [Search behaviour](#search-behaviour).
- **Query timeout (seconds)** (`queryTimeout`) - maximum time for each SQL query (`Statement.setQueryTimeout`). Default `5`; values `<= 0` fall back to the default.

### SQL queries

All queries support the `?` placeholder (replaced once per `?`); for queries with a single user-provided parameter but multiple `?` placeholders, the value is automatically expanded to fill every placeholder.

The query result must include at least the columns `id` and `username`; `email`, `firstName` and `lastName` are optional. Any additional column is mapped as a realm-scoped attribute on the federated user.

- **User count SQL query** (`count`) - not used (kept for configuration compatibility).
- **List All Users SQL query** (`listAll`) - not used (kept for configuration compatibility).
- **Find user by id SQL query** (`findById`) - receives the user id.
- **Find user by username SQL query** (`findByUsername`) - receives the username, or the e-mail on login by e-mail and on e-mail searches.
- **Find user by search term SQL query** (`findBySearchTerm`) - used only by prefix searches (`allowPrefixSearch`). Receives the lower-cased prefix followed by `%` (e.g. `meu-email%`); compare a single indexed column, e.g. `email like ?`. Functions on the column (`upper(email)`) or extra `OR` conditions disable the index.
- **Find password hash SQL query** (`findPasswordHash`) - must return the hash string for the given username (BCrypt hash, hex digest, or PBKDF2-SHA256 `$iterations$salt$hash`).

### Search behaviour

A `LIKE '%term%'` search or a `count(*)` over a large legacy table cannot use an index and turns into a full table scan. Admin API searches and counts are therefore translated into a single exact lookup:

| Keycloak parameter | Lookup |
|---|---|
| `email` containing `@` | `findByUsername` with the trimmed, lower-cased e-mail |
| `username` numeric | `findById` |
| `username` containing `@` | `findByUsername` |
| `search` numeric | `findById` |
| `search` containing `@` (optionally between double quotes) | `findByUsername` |
| `search`, `email` or `username` ending with `%` or `*`, with at least 3 fixed characters before it (e.g. `meu-email%`, `meu-email@*`) | `findBySearchTerm` with `meu-email%`, only when `allowPrefixSearch` is enabled, with `LIMIT` (at most 100 per page) |
| anything else (`*`, `%gmail%`, `*@x.com`, `ab%`, partial terms without wildcard, `firstName`/`lastName`, flags only, no parameters) | none: returns no users without querying the database |

A trailing wildcard (`email LIKE 'meu-email%'`) can use an index on the column; a leading one (`'%gmail'`) cannot, so it is never sent to the database.

Exact counts return `0` or `1`; prefix counts return the size of the first page (at most 100); total counts return `0`. For exact lookups, pages after the first (`first > 0`) are always empty. Lower-casing the e-mail assumes a case-insensitive column collation (the MySQL default).

### Password hashing

- **Password hash function** - one of `Blowfish (bcrypt)`, `MD2`, `MD5`, `SHA-1`, `SHA-256`, `SHA3-224`, `SHA3-256`, `SHA3-384`, `SHA3-512`, `SHA-384`, `SHA-512/224`, `SHA-512/256`, `SHA-512`, `PBKDF2-SHA256`. The default is `SHA-1`. The `md*` and `sha*` algorithms expect the database column to store a hexadecimal digest.

> **Note.** Password updates are not supported - `updateCredential` always throws `NotImplementedException`. The provider intentionally never registers new users (`addUser` returns `null`), so Keycloak falls back to the next provider in the chain when a user is created from the admin console.

## Technical notes

### Why `maven-compiler-plugin:3.11.0` and not 3.13?

There are two related issues that the build had to work around:

1. **Provider SPI classes moved.** In Keycloak 26 the classes `UserStorageProvider`, `UserStorageProviderFactory` and `AbstractUserAdapterFederatedStorage` were moved from `keycloak-server-spi-private` into the new `keycloak-model-storage` module. The build adds an explicit `provided`-scope dependency on `keycloak-model-storage`; without it, the compiler cannot find those classes even if `keycloak-server-spi-private` is on the classpath.
2. **Compiler 3.12+ activates the module path automatically.** When it sees a `module-info` in any dependency (HikariCP 5+ has one), it switches to the module path. On the module path, `keycloak-server-spi-private` is silently dropped because its name contains the Java reserved word `private`, which makes the `Automatic-Module-Name` derivation fail. The result is fake "cannot find symbol" errors for classes that are demonstrably on the classpath. Pinning the plugin to `3.11.0` and setting `useModulePath=false` keeps the classic classpath behaviour.

### Pagination without Hibernate

Keycloak 26 uses Hibernate 6, which removed the versioned Dialects (`PostgreSQL10Dialect`, `MySQL57Dialect`, `Oracle12cDialect`, `SQLServer2012Dialect`, etc.) that the original code relied on. `PagingUtil` was rewritten to emit pagination clauses by hand:

- PostgreSQL and MySQL: `LIMIT ... OFFSET ...`.
- Oracle 12c+, IBM DB2 and MS SQL Server 2012+: `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` (ANSI SQL).
- For SQL Server, which requires an `ORDER BY` whenever `OFFSET ... FETCH` is used, an `ORDER BY 1` clause is appended automatically when the user query does not already contain one.

## License

Apache 2.0 (same as the original project).
