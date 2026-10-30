package org.opensingular.dbuserprovider.persistence;

import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.NotImplementedException;
import at.favre.lib.crypto.bcrypt.BCrypt;
import org.opensingular.dbuserprovider.DBUserStorageException;
import org.opensingular.dbuserprovider.model.QueryConfigurations;
import org.opensingular.dbuserprovider.util.PBKDF2SHA256HashingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil.Pageable;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;


@JBossLog
public class UserRepository {
    
    
    private DataSourceProvider  dataSourceProvider;
    private QueryConfigurations queryConfigurations;
    
    public UserRepository(DataSourceProvider dataSourceProvider, QueryConfigurations queryConfigurations) {
        this.dataSourceProvider  = dataSourceProvider;
        this.queryConfigurations = queryConfigurations;
    }
    
    
    private <T> T doQuery(String query, Pageable pageable, Function<ResultSet, T> resultTransformer, Object... params) {
        Optional<DataSource> dataSourceOpt = dataSourceProvider.getDataSource();
        if (dataSourceOpt.isPresent()) {
            DataSource dataSource = dataSourceOpt.get();
            try (Connection c = dataSource.getConnection()) {
                if (pageable != null) {
                    query = PagingUtil.formatScriptWithPageable(query, pageable, queryConfigurations.getRDBMS());
                }
                // Convenience: if the query has N '?' placeholders but only 1 parameter was
                // provided (e.g. the same value compared against multiple columns),
                // we replicate that parameter N times automatically.
                Object[] effectiveParams = params;
                if (params != null && params.length == 1) {
                    int placeholders = countPlaceholders(query);
                    if (placeholders > 1) {
                        effectiveParams = new Object[placeholders];
                        Arrays.fill(effectiveParams, params[0]);
                    }
                }
                // Parameters are not logged: they hold customer data (e.g. email).
                log.debugv("Query: {0}", query);
                try (PreparedStatement statement = c.prepareStatement(query)) {
                    statement.setQueryTimeout(queryConfigurations.getQueryTimeoutSeconds());
                    if (effectiveParams != null) {
                        for (int i = 1; i <= effectiveParams.length; i++) {
                            statement.setObject(i, effectiveParams[i - 1]);
                        }
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        return resultTransformer.apply(rs);
                    }
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return null;
        }
        return null;
    }

    /**
     * Counts the number of '?' placeholders in a SQL query, ignoring those
     * that appear inside string literals (delimited by single quotes).
     */
    private static int countPlaceholders(String sql) {
        int count = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
            } else if (ch == '?' && !inString) {
                count++;
            }
        }
        return count;
    }
    
    private List<Map<String, String>> readMap(ResultSet rs) {
        try {
            List<Map<String, String>> data         = new ArrayList<>();
            Set<String>               columnsFound = new HashSet<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String columnLabel = rs.getMetaData().getColumnLabel(i);
                columnsFound.add(columnLabel);
            }
            while (rs.next()) {
                Map<String, String> result = new HashMap<>();
                for (String col : columnsFound) {
                    result.put(col, rs.getString(col));
                }
                data.add(result);
            }
            return data;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }
    
    
    private Boolean readBoolean(ResultSet rs) {
        try {
            return rs.next() ? rs.getBoolean(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }
    
    private String readString(ResultSet rs) {
        try {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }
    
    public Map<String, String> findUserById(String id) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindById(), null, this::readMap, id))
                       .orElse(Collections.emptyList())
                       .stream().findFirst().orElse(null);
    }
    
    public Optional<Map<String, String>> findUserByUsername(String username) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindByUsername(), null, this::readMap, username))
                       .orElse(Collections.emptyList())
                       .stream().findFirst();
    }
    
    /**
     * Runs the "findBySearchTerm" query with a prefix pattern such as {@code meu-email%}.
     * The pageable is required, so the query always has a row limit.
     */
    public List<Map<String, String>> findUsersByPrefix(String prefix, Pageable pageable) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindBySearchTerm(), pageable, this::readMap, prefix))
                       .orElse(Collections.emptyList());
    }

    public boolean validateCredentials(String username, String password) {
        String hash = Optional.ofNullable(doQuery(queryConfigurations.getFindPasswordHash(), null, this::readString, username)).orElse("");
        if (queryConfigurations.isBlowfish()) {
            return !hash.isEmpty() && BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } else {
            String hashFunction = queryConfigurations.getHashFunction();

            if(hashFunction.equals("PBKDF2-SHA256")){
                String[] components = hash.split("\\$");
                return new PBKDF2SHA256HashingUtil(password, components[2], Integer.valueOf(components[1])).validatePassword(components[3]);
            }

            MessageDigest digest   = DigestUtils.getDigest(hashFunction);
            byte[]        pwdBytes = StringUtils.getBytesUtf8(password);
            return Objects.equals(Hex.encodeHexString(digest.digest(pwdBytes)), hash);
        }
    }
    
    public boolean updateCredentials(String username, String password) {
        throw new NotImplementedException("Password update not supported");
    }
    
    public boolean removeUser() {
        return queryConfigurations.getAllowKeycloakDelete();
    }
}
