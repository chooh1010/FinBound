package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * 워커 데이터베이스 초기화 스크립트(infrastructure/postgres-init/20-alert-worker.sh)를 실제 PostgreSQL에서 돌린다.
 *
 * <p>워커 역할은 자기 데이터베이스에만 접속하고 Core 데이터베이스에는 접속하지 못해야 한다 — 소비자는 Core 원천 표를 직접 읽지
 * 않는다(docs/04 §18)는 규칙을 권한으로 막는다.
 */
@Testcontainers
class AlertDatabaseIsolationTest {

    private static final String WORKER_PASSWORD = "worker-test-password";
    private static final String SCRIPT = "/docker-entrypoint-initdb.d/20-alert-worker.sh";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("finguard")
            .withEnv("ALERT_WORKER_DB_PASSWORD", WORKER_PASSWORD)
            // 실행 권한 없이 넣는다. postgres 진입 스크립트가 source하는 실제 경로를 그대로 탄다.
            .withCopyFileToContainer(script(), SCRIPT);

    @Test
    void workerReachesOnlyItsOwnDatabase() throws SQLException {
        try (Connection own = connect("finguard_alerts", "finguard_alerts", WORKER_PASSWORD)) {
            assertThat(own.isValid(2)).isTrue();
        }

        assertThatThrownBy(() -> connect("finguard", "finguard_alerts", WORKER_PASSWORD).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> connect("postgres", "finguard_alerts", WORKER_PASSWORD).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> connect("template1", "finguard_alerts", WORKER_PASSWORD).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void freshDatabaseWithoutAWorkerPasswordStartsAndRunsTheLaterInitScripts() throws SQLException {
        // 건너뛰는 경로가 진입 스크립트를 끝내 버리면 기동이 멈추고, 뒤의 초기화 파일도 돌지 않는다.
        try (PostgreSQLContainer<?> plain = new PostgreSQLContainer<>("postgres:17.10-alpine")
                .withDatabaseName("finguard")
                .withCopyFileToContainer(script(), SCRIPT)
                .withCopyToContainer(Transferable.of("create table init_canary (id integer);"),
                        "/docker-entrypoint-initdb.d/30-canary.sql")) {
            plain.start();

            try (Connection core = DriverManager.getConnection(
                            plain.getJdbcUrl(), plain.getUsername(), plain.getPassword());
                    var result = core.createStatement().executeQuery(
                            "select (select count(*) from pg_tables where tablename = 'init_canary'),"
                                    + " (select count(*) from pg_roles where rolname = 'finguard_alerts')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
                assertThat(result.getInt(2)).isZero();
            }
        }
    }

    @Test
    void workerRoleIsNotASuperuserAndTheScriptCanRunAgain() throws Exception {
        ExecResult again = POSTGRES.execInContainer("sh", SCRIPT);
        assertThat(again.getExitCode()).isZero();

        try (Connection own = connect("finguard_alerts", "finguard_alerts", WORKER_PASSWORD);
                var result = own.createStatement().executeQuery(
                        "select rolsuper, rolcreatedb, rolcreaterole from pg_roles where rolname = current_user")) {
            result.next();
            assertThat(result.getBoolean(1)).isFalse();
            assertThat(result.getBoolean(2)).isFalse();
            assertThat(result.getBoolean(3)).isFalse();
        }
    }

    @Test
    void withoutAPasswordTheScriptLeavesTheDatabaseAlone() throws Exception {
        ExecResult skipped = POSTGRES.execInContainer("sh", "-c", "ALERT_WORKER_DB_PASSWORD= sh " + SCRIPT);

        assertThat(skipped.getExitCode()).isZero();
        assertThat(skipped.getStdout()).contains("skipping");
    }

    private static MountableFile script() {
        return MountableFile.forHostPath(Path.of(System.getProperty("finguard.repository.root"),
                "infrastructure", "postgres-init", "20-alert-worker.sh"), 0644);
    }

    private static Connection connect(String database, String user, String password) throws SQLException {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        return DriverManager.getConnection(url, user, password);
    }
}
