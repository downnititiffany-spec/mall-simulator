package com.graduation.analytics.metric;

import com.graduation.analytics.metric.entity.MetricValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlMetricStoreActivationRollbackTest {

    @Test
    @DisplayName("激活更新 0 行仍返回 0，但回滚指标写入和旧快照归档")
    void zeroActivationRowsRollBackTheWholePublishTransaction() {
        JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
        JdbcTemplate readJdbc = mock(JdbcTemplate.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<String> statements = new ArrayList<>();

        when(publishJdbc.batchUpdate(anyString(), any(List.class))).thenReturn(new int[] {1});
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            statements.add(sql);
            return sql.startsWith("UPDATE metric_snapshot SET status = ?, active_flag = ?, published_at = ?")
                    ? 0 : 1;
        }).when(publishJdbc).update(anyString(), any(Object[].class));

        MySqlMetricStore target = new MySqlMetricStore(publishJdbc, readJdbc);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(MetricStore.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        MetricStore store = (MetricStore) proxyFactory.getProxy();

        MetricValue value = new MetricValue();
        value.setSnapshotId("S_NOOP");
        value.setMetricCode("gmv");
        value.setMetricValue(new BigDecimal("12.34"));
        value.setUnit("元");
        value.setPeriod("day:2026-09-23");
        value.setDefinitionVersion("v1");

        int switched = store.publish(new MetricStore.SnapshotRef("S_NOOP", 7L, "day:2026-09-23"),
                List.of(value));

        assertThat(switched).isZero();
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).startsWith("DELETE FROM metric_value");
        assertThat(statements.get(1)).startsWith("UPDATE metric_snapshot SET status = ?, active_flag = NULL");
        assertThat(statements.get(2)).startsWith("UPDATE metric_snapshot SET status = ?, active_flag = ?, published_at = ?");
        assertThat(transactionManager.commitCount).isZero();
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
    }

    @Test
    @DisplayName("激活后核验失败时事务内撤销新快照并恢复旧 ACTIVE")
    void postActivationFailureRestoresPreviousActiveAtomically() {
        JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
        JdbcTemplate readJdbc = mock(JdbcTemplate.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<String> statements = new ArrayList<>();

        when(publishJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    statements.add(sql);
                    return sql.contains("active_flag = 1") ? List.of("S_FAILED") : List.of();
                });
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            statements.add(sql);
            return 1;
        }).when(publishJdbc).update(anyString(), any(Object[].class));

        MetricStore store = transactionalStore(publishJdbc, readJdbc, transactionManager);
        String restored = store.failActivationAndRestore(
                new MetricStore.SnapshotRef("S_FAILED", 7L, "day:2026-09-23"), "S_PREVIOUS", "read mismatch");

        assertThat(restored).isEqualTo("S_PREVIOUS");
        assertThat(statements).hasSize(4);
        assertThat(statements.get(0)).contains("SELECT snapshot_id").contains("FOR UPDATE");
        assertThat(statements.get(1)).startsWith("UPDATE metric_snapshot SET status = ?, active_flag = NULL");
        assertThat(statements.get(2)).startsWith("UPDATE metric_snapshot SET status = ?, active_flag = ?");
        assertThat(statements.get(3)).isEqualTo("DELETE FROM metric_value WHERE snapshot_id = ?");
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    @DisplayName("更晚快照已成为 ACTIVE 时，后置失败不覆盖它")
    void postActivationFailureDoesNotReplaceNewerActiveSnapshot() {
        JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
        JdbcTemplate readJdbc = mock(JdbcTemplate.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        when(publishJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenReturn(List.of("S_NEWER"));
        when(publishJdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        MetricStore store = transactionalStore(publishJdbc, readJdbc, transactionManager);
        String active = store.failActivationAndRestore(
                new MetricStore.SnapshotRef("S_FAILED", 7L, "day:2026-09-23"), "S_PREVIOUS", "read mismatch");

        assertThat(active).isEqualTo("S_NEWER");
        org.mockito.Mockito.verify(publishJdbc, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.contains("failure_reason = NULL"), any(Object[].class));
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    @DisplayName("当前没有 ACTIVE 指针时仍恢复可用的发布前快照")
    void postActivationFailureRestoresPreviousWhenNoSnapshotIsActive() {
        JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
        JdbcTemplate readJdbc = mock(JdbcTemplate.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<String> statements = new ArrayList<>();

        when(publishJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenReturn(List.of());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            statements.add(sql);
            return 1;
        }).when(publishJdbc).update(anyString(), any(Object[].class));

        MetricStore store = transactionalStore(publishJdbc, readJdbc, transactionManager);
        String restored = store.failActivationAndRestore(
                new MetricStore.SnapshotRef("S_FAILED", 7L, "day:2026-09-23"), "S_PREVIOUS", "read mismatch");

        assertThat(restored).isEqualTo("S_PREVIOUS");
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("active_flag IS NULL");
        assertThat(statements.get(1)).contains("status = ?").contains("active_flag = ?");
        assertThat(statements.get(2)).isEqualTo("DELETE FROM metric_value WHERE snapshot_id = ?");
        assertThat(transactionManager.commitCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    @DisplayName("旧快照已不存在时整笔回退事务回滚，不留下无 ACTIVE 状态")
    void missingPreviousSnapshotRollsBackRecoveryTransaction() {
        JdbcTemplate publishJdbc = mock(JdbcTemplate.class);
        JdbcTemplate readJdbc = mock(JdbcTemplate.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        when(publishJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenReturn(List.of("S_FAILED"), List.of());
        when(publishJdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("status = ? AND active_flag IS NULL") ? 0 : 1;
        });

        MetricStore store = transactionalStore(publishJdbc, readJdbc, transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.failActivationAndRestore(
                        new MetricStore.SnapshotRef("S_FAILED", 7L, "day:2026-09-23"), "S_MISSING", "read mismatch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("旧 ACTIVE 不存在");

        assertThat(transactionManager.commitCount).isZero();
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
    }

    private static MetricStore transactionalStore(JdbcTemplate publishJdbc, JdbcTemplate readJdbc,
                                                   RecordingTransactionManager transactionManager) {
        MySqlMetricStore target = new MySqlMetricStore(publishJdbc, readJdbc);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(MetricStore.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (MetricStore) proxyFactory.getProxy();
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commitCount;
        private int rollbackCount;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return false;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // In-memory transaction boundary: test observes only commit versus rollback.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
        }
    }
}
