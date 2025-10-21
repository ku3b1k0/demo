package com.mastercard.dgc.bizops.dml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

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

        // List planned queries and proceed to execution
        logPlannedQueries(cfg, "execution");

        if (cfg.getResults() == null) {
            cfg.setResults(new QueriesConfig.Results());
        }

        // Pre-execution check: allow only INSERT, UPDATE, DELETE
        if (!precheckOnlyDml(cfg)) {
            log.error("Aborting execution: Found statements that are not INSERT/UPDATE/DELETE.");
            return;
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
        logPlannedQueries(cfg, "validation");
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

        // Step 1: lightweight static sanity checks to catch obvious typos before hitting JDBC
        boolean staticError = false;
        if (transactional != null) {
            for (int b = 0; b < transactional.size(); b++) {
                List<String> batch = transactional.get(b);
                if (batch == null || batch.isEmpty()) continue;
                String firstErr = null;
                for (int i = 0; i < batch.size(); i++) {
                    String sql = batch.get(i);
                    if (sql == null || sql.isBlank()) continue;
                    String err = staticSyntaxError(sql);
                    if (err != null) {
                        if (firstErr == null) firstErr = String.format("Syntax error in tx batch #%d stmt #%d: %s", b + 1, i + 1, err);
                    }
                }
                if (firstErr != null) {
                    txResultsOnError.add(new QueriesConfig.BatchResult(b + 1, false, firstErr));
                    staticError = true;
                }
            }
        }
        if (nonTransactional != null) {
            for (int i = 0; i < nonTransactional.size(); i++) {
                String sql = nonTransactional.get(i);
                if (sql == null || sql.isBlank()) continue;
                String err = staticSyntaxError(sql);
                if (err != null) {
                    nonTxResultsOnError.add(new QueriesConfig.StatementResult(i + 1, false, err));
                    staticError = true;
                }
            }
        }
        if (staticError) {
            if (cfg.getResults() == null) cfg.setResults(new QueriesConfig.Results());
            if (!nonTxResultsOnError.isEmpty()) cfg.getResults().setNonTransactional(nonTxResultsOnError);
            if (!txResultsOnError.isEmpty()) cfg.getResults().setTransactional(txResultsOnError);
            return false;
        }

        // Step 2: JDBC prepare-based validation for deeper checks
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

    // Very lightweight static validator to catch obvious keyword typos before JDBC-level checks
    private String staticSyntaxError(String sql) {
        if (sql == null) return null;
        String s = sql.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();
        // Remove trailing semicolon for analysis
        if (lower.endsWith(";")) lower = lower.substring(0, lower.length() - 1).trim();
        // Catch 'creat' instead of 'create'
        if (lower.matches("^creat(\\b|\\s|\\().*")) {
            return "Unknown keyword 'CREAT' at start of statement. Did you mean 'CREATE'?";
        }
        return null;
    }

    private void logPlannedQueries(QueriesConfig cfg, String phase) {
        if (cfg == null || cfg.getQueries() == null) {
            log.info("No queries to list for {}.", phase);
            return;
        }
        QueriesConfig.Queries q = cfg.getQueries();
        List<QueriesConfig.Group> groups = q.getGroups();
        log.info("--------------------");
        log.info("Listing queries for {}...", phase);
        if (groups != null && !groups.isEmpty()) {
            for (QueriesConfig.Group g : groups) {
                if (g == null) continue;
                List<String> stmts = g.getQueries();
                String type = g.getType();
                int count = stmts != null ? stmts.size() : 0;
                log.info("Group: {} [{}] ({} statements)", g.getName(), type, count);
                if (stmts != null) {
                    for (int i = 0; i < stmts.size(); i++) {
                        String sql = stmts.get(i);
                        if (sql == null || sql.isBlank()) continue;
                        log.info("  {}. {}", i + 1, trimSql(sql));
                    }
                }
            }
        } else {
            // Legacy shape
            List<List<String>> tx = q.getTransactional();
            List<String> nonTx = q.getNonTransactional();
            if (tx != null && !tx.isEmpty()) {
                log.info("Transactional batches: {}", tx.size());
                for (int b = 0; b < tx.size(); b++) {
                    List<String> batch = tx.get(b);
                    int count = batch != null ? batch.size() : 0;
                    log.info("  Batch #{} ({} statements)", b + 1, count);
                    if (batch != null) {
                        for (int i = 0; i < batch.size(); i++) {
                            String sql = batch.get(i);
                            if (sql == null || sql.isBlank()) continue;
                            log.info("    {}. {}", i + 1, trimSql(sql));
                        }
                    }
                }
            }
            if (nonTx != null && !nonTx.isEmpty()) {
                log.info("Non-transactional statements: {}", nonTx.size());
                for (int i = 0; i < nonTx.size(); i++) {
                    String sql = nonTx.get(i);
                    if (sql == null || sql.isBlank()) continue;
                    log.info("  {}. {}", i + 1, trimSql(sql));
                }
            }
        }
        log.info("--------------------");
    }

    private boolean precheckOnlyDml(QueriesConfig cfg) {
        if (cfg == null || cfg.getQueries() == null) return true;
        boolean anyOffending = false;
        QueriesConfig.Queries q = cfg.getQueries();

        // Prepare results container
        if (cfg.getResults() == null) cfg.setResults(new QueriesConfig.Results());

        // If groups exist, report per group
        if (q.getGroups() != null && !q.getGroups().isEmpty()) {
            List<QueriesConfig.GroupResult> named = new ArrayList<>();
            for (QueriesConfig.Group g : q.getGroups()) {
                if (g == null) continue;
                List<String> stmts = g.getQueries();
                if (stmts == null || stmts.isEmpty()) continue;
                List<QueriesConfig.StatementOutcome> bad = new ArrayList<>();
                for (String sql : stmts) {
                    if (sql == null || sql.isBlank()) continue;
                    if (!isAllowedDml(sql)) {
                        anyOffending = true;
                        bad.add(new QueriesConfig.StatementOutcome(sql, false, "Only INSERT, UPDATE, DELETE or SELECT statements are allowed"));
                        log.error("Disallowed statement in group '{}': {}", g.getName(), trimSql(sql));
                    }
                }
                if (!bad.isEmpty()) {
                    QueriesConfig.GroupResult gr = new QueriesConfig.GroupResult(g.getName(), g.getType());
                    gr.setTotal(stmts.size());
                    gr.setSuccessCount(0);
                    gr.setFailureCount(bad.size());
                    gr.setOutcomes(bad);
                    named.add(gr);
                }
            }
            if (!named.isEmpty()) {
                cfg.getResults().setNamed(named);
            }
        } else {
            // Legacy lists: transactional and non-transactional
            List<QueriesConfig.BatchResult> tx = new ArrayList<>();
            List<QueriesConfig.StatementResult> nonTx = new ArrayList<>();
            List<List<String>> batches = q.getTransactional();
            if (batches != null) {
                for (int b = 0; b < batches.size(); b++) {
                    List<String> batch = batches.get(b);
                    if (batch == null || batch.isEmpty()) continue;
                    boolean badBatch = false;
                    for (String sql : batch) {
                        if (sql == null || sql.isBlank()) continue;
                        if (!isAllowedDml(sql)) { badBatch = true; break; }
                    }
                    if (badBatch) {
                        anyOffending = true;
                        tx.add(new QueriesConfig.BatchResult(b + 1, false, "Only INSERT, UPDATE, DELETE or SELECT statements are allowed (batch contains disallowed statement)"));
                    }
                }
            }
            List<String> nonTransactional = q.getNonTransactional();
            if (nonTransactional != null) {
                for (int i = 0; i < nonTransactional.size(); i++) {
                    String sql = nonTransactional.get(i);
                    if (sql == null || sql.isBlank()) continue;
                    if (!isAllowedDml(sql)) {
                        anyOffending = true;
                        nonTx.add(new QueriesConfig.StatementResult(i + 1, false, "Only INSERT, UPDATE, DELETE or SELECT statements are allowed"));
                        log.error("Disallowed statement (non-tx #{}): {}", i + 1, trimSql(sql));
                    }
                }
            }
            if (!tx.isEmpty()) cfg.getResults().setTransactional(tx);
            if (!nonTx.isEmpty()) cfg.getResults().setNonTransactional(nonTx);
        }
        return !anyOffending;
    }

    private boolean isAllowedDml(String sql) {
        if (sql == null) return false;
        String s = sql;
        s = s.replace('\r', '\n');
        String t = s.trim();
        // Strip leading line comments and block comments (shallow)
        boolean stripped = true;
        int guard = 0;
        while (stripped && guard++ < 3) { // avoid infinite loop
            stripped = false;
            if (t.startsWith("--")) {
                int nl = t.indexOf('\n');
                if (nl >= 0) { t = t.substring(nl + 1).trim(); stripped = true; continue; }
            }
            if (t.startsWith("/*")) {
                int end = t.indexOf("*/");
                if (end >= 0) { t = t.substring(end + 2).trim(); stripped = true; }
            }
        }
        if (t.isEmpty()) return false;
        String lower = t.toLowerCase();
        // remove leading parentheses if any
        while (lower.startsWith("(")) {
            lower = lower.substring(1).trim();
        }
        return lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete") || lower.startsWith("select");
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
                                    String norm = trimLeadingKeyword(sql);
                                    boolean isSelect = norm != null && norm.toLowerCase().startsWith("select");
                                    if (isSelect) {
                                        final int[] countHolder = new int[] { 0 };
                                        jdbcTemplate.query(sql, rs -> { countHolder[0]++; });
                                        log.info("[group:{} tx stmt #{}] OK (returned={}): {}", g.getName(), i + 1, countHolder[0], trimSql(sql));
                                        QueriesConfig.StatementOutcome so = new QueriesConfig.StatementOutcome(sql, true, null);
                                        so.setRowsReturned(countHolder[0]);
                                        outcomes.add(so);
                                    } else {
                                        int updated = jdbcTemplate.update(sql);
                                        log.info("[group:{} tx stmt #{}] OK (updated={}): {}", g.getName(), i + 1, updated, trimSql(sql));
                                        QueriesConfig.StatementOutcome so = new QueriesConfig.StatementOutcome(sql, true, null);
                                        so.setRowsUpdated(updated);
                                        outcomes.add(so);
                                    }
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
                    // Convert all previously marked successes to failed because the transaction was rolled back
                    String rolledBackMsg = "Rolled back due to transaction failure at statement #" + (failIdx + 1);
                    for (int i = 0; i < outcomes.size(); i++) {
                        QueriesConfig.StatementOutcome o = outcomes.get(i);
                        if (o != null && o.isSuccess()) {
                            o.setSuccess(false);
                            o.setError(rolledBackMsg);
                            o.setRowsUpdated(null);
                            o.setRowsReturned(null);
                        }
                    }
                    // Mark remaining statements (not attempted) as failed due to rollback
                    for (int i = failIdx + 1; i < stmts.size(); i++) {
                        String sql = stmts.get(i);
                        if (sql == null || sql.isBlank()) continue;
                        outcomes.add(new QueriesConfig.StatementOutcome(sql, false, rolledBackMsg + " (not executed)"));
                    }
                    // After rollback, the whole group failed
                    gr.setSuccessCount(0);
                    gr.setFailureCount(outcomes.size());
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
                        final int[] updatedHolder = new int[] { 0 };
                        final int[] returnedHolder = new int[] { 0 };
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            boolean originalAutoCommit = con.getAutoCommit();
                            try {
                                if (!originalAutoCommit) con.setAutoCommit(true);
                                String norm = trimLeadingKeyword(sql);
                                boolean isSelect = norm != null && norm.toLowerCase().startsWith("select");
                                if (isSelect) {
                                    jdbcTemplate.query(sql, rs -> { returnedHolder[0]++; });
                                } else {
                                    updatedHolder[0] = jdbcTemplate.update(sql);
                                }
                                return null;
                            } finally {
                                try { con.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                            }
                        });
                        String norm = trimLeadingKeyword(sql);
                        boolean isSelect = norm != null && norm.toLowerCase().startsWith("select");
                        if (isSelect) {
                            log.info("[group:{} non-tx #{}] OK (returned={}): {}", g.getName(), i + 1, returnedHolder[0], trimSql(sql));
                        } else {
                            log.info("[group:{} non-tx #{}] OK (updated={}): {}", g.getName(), i + 1, updatedHolder[0], trimSql(sql));
                        }
                        QueriesConfig.StatementOutcome so = new QueriesConfig.StatementOutcome(sql, true, null);
                        if (isSelect) so.setRowsReturned(returnedHolder[0]); else so.setRowsUpdated(updatedHolder[0]);
                        outcomes.add(so);
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
                        String norm = trimLeadingKeyword(sql);
                        boolean isSelect = norm != null && norm.toLowerCase().startsWith("select");
                        if (isSelect) {
                            jdbcTemplate.query(sql, rs -> { /* count-only via size in log below */ });
                        } else {
                            jdbcTemplate.update(sql);
                        }
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
                            String norm = trimLeadingKeyword(sql);
                            boolean isSelect = norm != null && norm.toLowerCase().startsWith("select");
                            if (isSelect) {
                                final int[] countHolder = new int[] { 0 };
                                jdbcTemplate.query(sql, rs -> { countHolder[0]++; });
                                log.info("[tx batch #{} stmt #{}] OK (returned={}): {}", batchNo, i + 1, countHolder[0], trimSql(sql));
                            } else {
                                int updated = jdbcTemplate.update(sql);
                                log.info("[tx batch #{} stmt #{}] OK (updated={}): {}", batchNo, i + 1, updated, trimSql(sql));
                            }
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

    // Helper: normalize by removing leading comments and parentheses, return trimmed SQL
    private String trimLeadingKeyword(String sql) {
        if (sql == null) return null;
        String s = sql.replace('\r', '\n').trim();
        boolean stripped = true;
        int guard = 0;
        while (stripped && guard++ < 5) {
            stripped = false;
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl >= 0) { s = s.substring(nl + 1).trim(); stripped = true; continue; }
            }
            if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end >= 0) { s = s.substring(end + 2).trim(); stripped = true; continue; }
            }
            while (s.startsWith("(")) { s = s.substring(1).trim(); stripped = true; }
        }
        return s;
    }
}
