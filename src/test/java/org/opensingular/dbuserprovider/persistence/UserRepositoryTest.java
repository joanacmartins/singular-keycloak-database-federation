package org.opensingular.dbuserprovider.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensingular.dbuserprovider.model.QueryConfigurations;
import org.opensingular.dbuserprovider.util.PagingUtil;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLTimeoutException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRepositoryTest {

    private PreparedStatement statement;
    private ResultSet         resultSet;
    private UserRepository    repository;
    private Connection        connectionMock;

    @BeforeEach
    void setUp() throws Exception {
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        Connection connection = mock(Connection.class);
        connectionMock = connection;
        DataSource dataSource = mock(DataSource.class);
        DataSourceProvider dataSourceProvider = mock(DataSourceProvider.class);

        when(dataSourceProvider.getDataSource()).thenReturn(Optional.of(dataSource));
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        QueryConfigurations config = new QueryConfigurations(
                "select count(*) from users", "select * from users",
                "select id, username from users where id = ?",
                "select id, username from users where email = ?",
                "select id, username from users where email like ?",
                "select hash from users where email = ?",
                "SHA-256", RDBMS.MYSQL, false, false, 7, true);
        repository = new UserRepository(dataSourceProvider, config);
    }

    @Test
    void queriesHaveTimeout() throws Exception {
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("123");

        Optional<Map<String, String>> user = repository.findUserByUsername("fulano@x.com");

        assertEquals("123", user.orElseThrow().get("id"));
        verify(statement).setQueryTimeout(7);
        verify(statement).setObject(1, "fulano@x.com");
    }

    @Test
    void prefixSearchAlwaysHasLimit() throws Exception {
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(0);

        repository.findUsersByPrefix("meu-email%", new PagingUtil.Pageable(20, 10));

        verify(connectionMock).prepareStatement("select id, username from users where email like ? LIMIT 10 OFFSET 20");
        verify(statement).setObject(1, "meu-email%");
        verify(statement).setQueryTimeout(7);
    }

    @Test
    void timeoutIsTreatedAsNotFound() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLTimeoutException("Statement cancelled due to timeout"));

        assertTrue(repository.findUserByUsername("fulano@x.com").isEmpty());
        verify(statement).setQueryTimeout(7);
    }
}
