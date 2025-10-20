package com.example.demo.dml;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the structure of the YAML file with DML statements and results.
 * Supports both legacy structure and new named groups structure.
 */
public class QueriesConfig {
    private Queries queries = new Queries();
    private Results results = new Results();

    public Queries getQueries() {
        return queries;
    }

    public void setQueries(Queries queries) {
        this.queries = queries != null ? queries : new Queries();
    }

    public Results getResults() {
        return results;
    }

    public void setResults(Results results) {
        this.results = results != null ? results : new Results();
    }

    public static class Queries {
        private List<List<String>> transactional = new ArrayList<>();
        private List<String> nonTransactional = new ArrayList<>();
        // New: preserve named groups from YAML (for new structure)
        private List<Group> groups = new ArrayList<>();

        public List<List<String>> getTransactional() {
            return transactional;
        }

        public void setTransactional(List<List<String>> transactional) {
            this.transactional = transactional != null ? transactional : new ArrayList<>();
        }

        public List<String> getNonTransactional() {
            return nonTransactional;
        }

        public void setNonTransactional(List<String> nonTransactional) {
            this.nonTransactional = nonTransactional != null ? nonTransactional : new ArrayList<>();
        }

        public List<Group> getGroups() {
            return groups;
        }

        public void setGroups(List<Group> groups) {
            this.groups = groups != null ? groups : new ArrayList<>();
        }
    }

    public static class Results {
        private List<BatchResult> transactional = new ArrayList<>();
        private List<StatementResult> nonTransactional = new ArrayList<>();
        // New: results grouped by name
        private List<GroupResult> named = new ArrayList<>();

        public List<BatchResult> getTransactional() {
            return transactional;
        }

        public void setTransactional(List<BatchResult> transactional) {
            this.transactional = transactional != null ? transactional : new ArrayList<>();
        }

        public List<StatementResult> getNonTransactional() {
            return nonTransactional;
        }

        public void setNonTransactional(List<StatementResult> nonTransactional) {
            this.nonTransactional = nonTransactional != null ? nonTransactional : new ArrayList<>();
        }

        public List<GroupResult> getNamed() {
            return named;
        }

        public void setNamed(List<GroupResult> named) {
            this.named = named != null ? named : new ArrayList<>();
        }
    }

    public static class BatchResult {
        private int batchNumber;
        private boolean success;
        private String error;

        public BatchResult() {}

        public BatchResult(int batchNumber, boolean success, String error) {
            this.batchNumber = batchNumber;
            this.success = success;
            this.error = error;
        }

        public int getBatchNumber() {
            return batchNumber;
        }

        public void setBatchNumber(int batchNumber) {
            this.batchNumber = batchNumber;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    public static class StatementResult {
        private int statementNumber;
        private boolean success;
        private String error;

        public StatementResult() {}

        public StatementResult(int statementNumber, boolean success, String error) {
            this.statementNumber = statementNumber;
            this.success = success;
            this.error = error;
        }

        public int getStatementNumber() {
            return statementNumber;
        }

        public void setStatementNumber(int statementNumber) {
            this.statementNumber = statementNumber;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    // New: YAML group entity
    public static class Group {
        private String name;
        private String type; // transactional or non-transactional
        private List<String> queries = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<String> getQueries() { return queries; }
        public void setQueries(List<String> queries) { this.queries = queries != null ? queries : new ArrayList<>(); }
    }

    // New: per-group execution result
    public static class GroupResult {
        private String name;
        private String type; // transactional or non-transactional
        private int total;
        private int successCount;
        private int failureCount;
        private List<StatementOutcome> outcomes = new ArrayList<>();

        public GroupResult() {}

        public GroupResult(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailureCount() { return failureCount; }
        public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
        public List<StatementOutcome> getOutcomes() { return outcomes; }
        public void setOutcomes(List<StatementOutcome> outcomes) { this.outcomes = outcomes != null ? outcomes : new ArrayList<>(); }
    }

    // New: outcome per individual statement within a group
    public static class StatementOutcome {
        private String sql;
        private boolean success;
        private String error;

        public StatementOutcome() {}
        public StatementOutcome(String sql, boolean success, String error) {
            this.sql = sql;
            this.success = success;
            this.error = error;
        }
        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
