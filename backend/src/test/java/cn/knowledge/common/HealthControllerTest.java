package cn.knowledge.common;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import cn.knowledge.rag.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private final DataSource dataSource = mock(DataSource.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var model = mock(ModelGateway.class);
        when(model.modelName()).thenReturn("");
        var environment = new MockEnvironment();
        environment.setActiveProfiles("mysql");
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(model, environment, dataSource)).build();
    }

    @Test void aWorkingConnectionReportsReadyAndIsReturnedToThePool() throws Exception {
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("MySQL"))
                .andExpect(jsonPath("$.databaseReady").value(true));
        verify(connection).close();
    }

    @Test void anUnavailableDatabaseReports503WithoutConnectionDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("private-password and jdbc address"));
        mvc.perform(get("/api/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.databaseReady").value(false))
                .andExpect(content().string(not(containsString("private-password"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test void aBrokenConnectionAlsoReportsUnavailableAndIsClosed() throws Exception {
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);
        mvc.perform(get("/api/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.databaseReady").value(false));
        verify(connection).close();
    }
}
