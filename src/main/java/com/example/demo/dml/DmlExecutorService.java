package com.example.demo.dml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes DML queries with transactional and non-transactional behavior.
 */
public class DmlExecutorService {
    private static final Logger log = LoggerFactory.getLogger(DmlExecutorService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DmlExecutorService(JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    public void execute(QueriesConfig cfg) {
        if (cfg == null) {
            log.warn("No queries config provided; nothing to do.");
            return;
        }
        QueriesConfig.Queries q = cfg.getQueries();
        if (q == null) {
            log.warn("No 'queries' section found; nothing to do.");
            return;
        }

        // 1) Validate all queries before execution. If validation fails, do not execute anything.
        if (!validateQueries(cfg)) {
            log.error("DML validation failed. Skipping execution of all queries.");
            return;
        }

        if (cfg.getResults() == null) {
            cfg.setResults(new QueriesConfig.Results());
        }

        // Prefer executing using named groups if available
        if (q.getGroups() != null && !q.getGroups().isEmpty()) {
            executeByGroups(cfg);
            return;
        }

        // Fallback to legacy behavior
        List<QueriesConfig.StatementResult> nonTxResults = executeNonTransactional(q.getNonTransactional());
        List<QueriesConfig.BatchResult> batchResults = executeTransactionalBatches(q.getTransactional());
        cfg.getResults().setNonTransactional(nonTxResults);
        cfg.getResults().setTransactional(batchResults);
    }

    /**
     * Public entry for validation-only mode. Populates cfg.results with validation failures (if any)
     * and returns without executing any statements.
     */
    public void validateOnly(QueriesConfig cfg) {
        if (cfg == null) {
            log.warn("No queries config provided; nothing to validate.");
            return;
        }
        boolean ok = validateQueries(cfg);
        if (ok) {
            log.info("DML validation succeeded. All statements are syntactically valid.");
        } else {
            log.error("DML validation found errors. See results for details.");
        }
    }

    /**
     * Validates SQL syntax by preparing each statement on the JDBC connection without executing it.
     * If any statement fails to prepare, this method populates cfg.results with failures and returns false.
     */
    private boolean validateQueries(QueriesConfig cfg) {
        QueriesConfig.Queries q = cfg.getQueries();
        if (q == null) return true;

        List<List<String>> transactional = q.getTransactional();
        List<String> nonTransactional = q.getNonTransactional();

        List<QueriesConfig.BatchResult> txResultsOnError = new ArrayList<>();
        List<QueriesConfig.StatementResult> nonTxResultsOnError = new ArrayList<>();

        Boolean hasError = jdbcTemplate.execute((ConnectionCallback<Boolean>) con -> {
            boolean anyError = false;
            // Validate transactional batches: if any statement in a batch is invalid, mark the batch as failed
            if (transactional != null) {
                for (int b = 0; b < transactional.size(); b++) {
                    List<String> batch = transactional.get(b);
                    if (batch == null || batch.isEmpty()) {
                        continue;
                    }
                    String firstErr = null;
                    for (int i = 0; i < batch.size(); i++) {
                        String sql = batch.get(i);
                        if (sql == null || sql.isBlank()) continue;
                        try (PreparedStatement ps = con.prepareStatement(sql)) {
                            // parsing happens on prepare for most drivers
                        } catch (Exception e) {
                            String msg = String.format("Syntax error in tx batch #%d stmt #%d: %s", b + 1, i + 1, e.getMessage());
                            log.error(msg, e);
                            if (firstErr == null) firstErr = msg;
                            anyError = true;
                            // continue checking others to report as much as possible for non-tx; for tx we record per-batch
                        }
                    }
                    if (firstErr != null) {
                        txResultsOnError.add(new QueriesConfig.BatchResult(b + 1, false, firstErr));
                    }
                }
            }
            // Validate non-transactional statements individually
            if (nonTransactional != null) {
                for (int i = 0; i < nonTransactional.size(); i++) {
                    String sql = nonTransactional.get(i);
                    if (sql == null || sql.isBlank()) continue;
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        // ok
                    } catch (Exception e) {
                        String msg = String.format("Syntax error in non-tx statement #%d: %s", i + 1, e.getMessage());
                        log.error(msg, e);
                        nonTxResultsOnError.add(new QueriesConfig.StatementResult(i + 1, false, msg));
                        anyError = true;
                    }
                }
            }
            return anyError;
        });

        if (Boolean.TRUE.equals(hasError)) {
            if (cfg.getResults() == null) cfg.setResults(new QueriesConfig.Results());
            // Only set the error entries we collected so Demo summary reflects validation failures
            if (!nonTxResultsOnError.isEmpty()) {
                cfg.getResults().setNonTransactional(nonTxResultsOnError);
            }
            if (!txResultsOnError.isEmpty()) {
                cfg.getResults().setTransactional(txResultsOnError);
            }
            return false;
        }
        return true;
    }

    private void executeByGroups(QueriesConfig cfg) {
        List<QueriesConfig.Group> groups = cfg.getQueries().getGroups();
        List<QueriesConfig.GroupResult> namedResults = new ArrayList<>();
        List<QueriesConfig.BatchResult> txResults = new ArrayList<>();
        List<QueriesConfig.StatementResult> nonTxResults = new ArrayList<>();

        int txBatchCounter = 0;
        int nonTxStmtCounter = 0;

        for (QueriesConfig.Group g : groups) {
            if (g == null) continue;
            String type = g.getType() != null ? g.getType().toLowerCase().replace('_', '-') : "non-transactional";
            List<String> stmts = g.getQueries();
            QueriesConfig.GroupResult gr = new QueriesConfig.GroupResult(g.getName(), type);
            gr.setTotal(stmts != null ? stmts.size() : 0);
            List<QueriesConfig.StatementOutcome> outcomes = new ArrayList<>();

            if ("transactional".equals(type)) {
                txBatchCounter++;
                final int batchNo = txBatchCounter;
                final int[] failIndexHolder = new int[] { -1 };
                Exception execEx = null;
                try {
                    transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                        @Override
                        protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                            for (int i = 0; i < stmts.size(); i++) {
                                String sql = stmts.get(i);
                                if (sql == null || sql.isBlank()) continue;
                                try {
                                    int updated = jdbcTemplate.update(sql);
                                    log.info("[group:{} tx stmt #{}] OK (updated={}): {}", g.getName(), i + 1, updated, trimSql(sql));
                                    outcomes.add(new QueriesConfig.StatementOutcome(sql, true, null));
                                } catch (Exception e) {
                                    log.error("[group:{} tx stmt #{}] FAILED: {} -> {}", g.getName(), i + 1, trimSql(sql), e.getMessage(), e);
                                    outcomes.add(new QueriesConfig.StatementOutcome(sql, false, e.getMessage()));
                                    failIndexHolder[0] = i;
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    });
                    // commit success
                    gr.setSuccessCount(outcomes.size());
                    gr.setFailureCount(0);
                    txResults.add(new QueriesConfig.BatchResult(batchNo, true, null));
                    log.info("[group:{}] TRANSACTION COMMITTED", g.getName());
                } catch (Exception e) {
                    execEx = e;
                    int failIdx = failIndexHolder[0];
                    // Mark remaining statements as skipped due to rollback
                    for (int i = failIdx + 1; i < stmts.size(); i++) {
                        String sql = stmts.get(i);
                        if (sql == null || sql.isBlank()) continue;
                        outcomes.add(new QueriesConfig.StatementOutcome(sql, false, "Skipped due to rollback"));
                    }
                    int failures = 0;
                    for (QueriesConfig.StatementOutcome o : outcomes) if (!o.isSuccess()) failures++;
                    gr.setSuccessCount(outcomes.size() - failures);
                    gr.setFailureCount(failures);
                    String errMsg = execEx != null ? execEx.getMessage() : "Transaction failed";
                    txResults.add(new QueriesConfig.BatchResult(batchNo, false, errMsg));
                    log.error("[group:{}] TRANSACTION ROLLED BACK: {}", g.getName(), errMsg);
                }
            } else {
                // non-transactional
                for (int i = 0; i < stmts.size(); i++) {
                    final String sql = stmts.get(i);
                    if (sql == null || sql.isBlank()) continue;
                    nonTxStmtCounter++;
                    final int idx = nonTxStmtCounter;
                    try {
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            boolean originalAutoCommit = con.getAutoCommit();
                            try {
                                if (!originalAutoCommit) con.setAutoCommit(true);
                                jdbcTemplate.update(sql);
                                return null;
                            } finally {
                                try { con.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                            }
                        });
                        log.info("[group:{} non-tx #{}] OK: {}", g.getName(), i + 1, trimSql(sql));
                        outcomes.add(new QueriesConfig.StatementOutcome(sql, true, null));
                        nonTxResults.add(new QueriesConfig.StatementResult(idx, true, null));
                    } catch (Exception e) {
                        log.error("[group:{} non-tx #{}] FAILED: {} -> {}", g.getName(), i + 1, trimSql(sql), e.getMessage(), e);
                        outcomes.add(new QueriesConfig.StatementOutcome(sql, false, e.getMessage()));
                        nonTxResults.add(new QueriesConfig.StatementResult(idx, false, e.getMessage()));
                    }
                }
                int failures = 0;
                for (QueriesConfig.StatementOutcome o : outcomes) if (!o.isSuccess()) failures++;
                gr.setSuccessCount(outcomes.size() - failures);
                gr.setFailureCount(failures);
            }

            gr.setOutcomes(outcomes);
            namedResults.add(gr);
        }

        cfg.getResults().setNamed(namedResults);
        cfg.getResults().setTransactional(txResults);
        cfg.getResults().setNonTransactional(nonTxResults);
    }

    private List<QueriesConfig.StatementResult> executeNonTransactional(List<String> statements) {
        List<QueriesConfig.StatementResult> results = new java.util.ArrayList<>();
        if (statements == null || statements.isEmpty()) {
            log.info("No non-transactional statements to execute.");
            return results;
        }
        log.info("Executing {} non-transactional statements...", statements.size());
        for (int i = 0; i < statements.size(); i++) {
            final String sql = statements.get(i);
            if (sql == null || sql.isBlank()) continue;
            final int idx = i + 1;
            try {
                jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                    boolean originalAutoCommit = con.getAutoCommit();
                    try {
                        // Ensure each statement auto-commits individually
                        if (!originalAutoCommit) con.setAutoCommit(true);
                        jdbcTemplate.update(sql);
                        return null;
                    } finally {
                        try {
                            con.setAutoCommit(originalAutoCommit);
                        } catch (SQLException ignored) {}
                    }
                });
                log.info("[non-tx #{}] OK: {}", idx, trimSql(sql));
                results.add(new QueriesConfig.StatementResult(idx, true, null));
            } catch (Exception e) {
                log.error("[non-tx #{}] FAILED: {} -> {}", idx, trimSql(sql), e.getMessage(), e);
                results.add(new QueriesConfig.StatementResult(idx, false, e.getMessage()));
                // Continue with next statements as these are non-transactional
            }
        }
        return results;
    }

    private List<QueriesConfig.BatchResult> executeTransactionalBatches(List<List<String>> batches) {
        List<QueriesConfig.BatchResult> results = new java.util.ArrayList<>();
        if (batches == null || batches.isEmpty()) {
            log.info("No transactional batches to execute.");
            return results;
        }
        log.info("Executing {} transactional batch(es)...", batches.size());
        for (int b = 0; b < batches.size(); b++) {
            final int batchNo = b + 1;
            final List<String> statements = batches.get(b);
            if (statements == null || statements.isEmpty()) {
                log.info("[tx batch #{}] Skipping empty batch.", batchNo);
                results.add(new QueriesConfig.BatchResult(batchNo, true, null));
                continue;
            }
            try {
                transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                        for (int i = 0; i < statements.size(); i++) {
                            String sql = statements.get(i);
                            if (sql == null || sql.isBlank()) continue;
                            int updated = jdbcTemplate.update(sql);
                            log.info("[tx batch #{} stmt #{}] OK (updated={}): {}", batchNo, i + 1, updated, trimSql(sql));
                        }
                    }
                });
                log.info("[tx batch #{}] COMMITTED", batchNo);
                results.add(new QueriesConfig.BatchResult(batchNo, true, null));
            } catch (Exception e) {
                // TransactionTemplate will roll back automatically on exception
                log.error("[tx batch #{}] FAILED and ROLLED BACK: {}", batchNo, e.getMessage(), e);
                results.add(new QueriesConfig.BatchResult(batchNo, false, e.getMessage()));
                // Continue with next batch
            }
        }
        return results;
    }

    private String trimSql(String sql) {
        String s = sql.trim().replaceAll("\n+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
