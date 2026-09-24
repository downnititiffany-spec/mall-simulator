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
