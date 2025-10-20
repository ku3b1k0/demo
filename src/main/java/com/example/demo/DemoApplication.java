package com.example.demo;

import com.example.demo.dml.DmlExecutorService;
import com.example.demo.dml.DmlYamlLoader;
import com.example.demo.dml.QueriesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

@SpringBootApplication
public class DemoApplication {
    private static final Logger log = LoggerFactory.getLogger(DemoApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    DmlYamlLoader dmlYamlLoader(ResourceLoader resourceLoader) {
        return new DmlYamlLoader(resourceLoader);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
    DmlExecutorService dmlExecutorService(JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        return new DmlExecutorService(jdbcTemplate, txManager);
    }

    @Bean
    CommandLineRunner dmlRunner(
            ObjectProvider<Environment> envProvider,
            DmlYamlLoader loader,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DmlExecutorService> executorProvider,
            @Value("${dml.enabled:false}") boolean enabled
    ) {
        return args -> {
            if (!enabled) {
                log.info("DML runner disabled (set dml.enabled=true to enable).");
                return;
            }
            DataSource defaultDs = dataSourceProvider.getIfAvailable();
            DmlExecutorService defaultExecutor = executorProvider.getIfAvailable();
            Environment env = envProvider.getIfAvailable();

            // Determine a single YAML path exclusively from command line args
            String yamlPath = null;
            if (args != null) {
                for (String a : args) {
                    if (a == null) continue;
                    if (a.startsWith("--yamllocation=")) {
                        yamlPath = a.substring("--yamllocation=".length());
                        break;
                    } else if (a.startsWith("--yamlLocation=")) { // allow camelCase variant
                        yamlPath = a.substring("--yamlLocation=".length());
                        break;
                    }
                }
                // Fallback: first positional arg (non-flag, non key=value) is considered the YAML path
                if (yamlPath == null || yamlPath.isBlank()) {
                    for (String a : args) {
                        if (a == null) continue;
                        if (!a.startsWith("--") && !a.contains("=")) {
                            yamlPath = a;
                            break;
                        }
                    }
                    int positionalCount = 0;
                    for (String a : args) {
                        if (a != null && !a.startsWith("--") && !a.contains("=")) positionalCount++;
                    }
                    if (positionalCount > 1) {
                        log.warn("Multiple YAML paths provided via CLI. Only one is supported; using the first: {}", yamlPath);
                    }
                }
            }
            if (yamlPath == null || yamlPath.isBlank()) {
                log.warn("No YAML path provided. Pass --yamllocation=<path> or provide a positional file path argument.");
                return;
            }

            // Parse optional CLI parameters: environment and credentials (generic for all vendors)
            String cliEnv = null; // e.g., dev, qa, prod
            String cliDBUser = null;
            String cliDBPass = null;
            String mode = "execute"; // default behavior
            if (args != null) {
                for (String a : args) {
                    if (a == null) continue;
                    if (a.startsWith("--env=")) cliEnv = a.substring("--env=".length());
                    else if (a.startsWith("--db.username=")) cliDBUser = a.substring("--db.username=".length());
                    else if (a.startsWith("--db.password=")) cliDBPass = a.substring("--db.password=".length());
                    else if (a.startsWith("--mode=")) mode = a.substring("--mode=".length()).trim().toLowerCase();
                    else if (a.equalsIgnoreCase("--validateOnly") || a.equalsIgnoreCase("--validateonly")) mode = "validate";
                }
            }
            if (cliEnv != null && !cliEnv.isBlank()) {
                log.info("Environment parameter detected: {}", cliEnv);
            }
            if (!"execute".equals(mode) && !"validate".equals(mode)) {
                log.warn("Unknown mode '{}'. Falling back to 'execute'. Supported: validate, execute.", mode);
                mode = "execute";
            } else {
                log.info("DML mode: {}", mode);
            }

            String path = yamlPath;
            log.info("Loading DML YAML from {}", path);
            QueriesConfig cfg = loader.load(path);

            // Decide vendor based on path
            String lower = path.toLowerCase();
            String vendor = null;
            if (lower.contains("oracle")) vendor = "oracle";
            else if (lower.contains("postgres")) vendor = "postgres";

            DmlExecutorService execForFile = null;
            if (vendor != null) {
                // Build per-vendor DataSource
                String url;
                String driver;
                String username;
                String password;

                String envTag = (cliEnv != null && !cliEnv.isBlank()) ? cliEnv.trim() : null;

                if (vendor.equals("oracle")) {
                    url = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.oracle." + envTag + ".url" : null),
                            prop(env, envTag != null ? "spring.datasource." + envTag + ".url" : null),
                            prop(env, "spring.datasource.oracle.url"),
                            prop(env, "spring.datasource.url"));
                    driver = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.oracle." + envTag + ".driver-class-name" : null),
                            prop(env, envTag != null ? "spring.datasource." + envTag + ".driver-class-name" : null),
                            prop(env, "spring.datasource.oracle.driver-class-name"),
                            prop(env, "spring.datasource.driver-class-name"),
                            "oracle.jdbc.OracleDriver");
                    username = cliDBUser;
                    password = cliDBPass;
                } else { // postgres
                    url = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.postgres." + envTag + ".url" : null),
                            prop(env, envTag != null ? "spring.datasource." + envTag + ".url" : null),
                            prop(env, "spring.datasource.postgres.url"),
                            prop(env, "spring.datasource.url"));
                    driver = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.postgres." + envTag + ".driver-class-name" : null),
                            prop(env, envTag != null ? "spring.datasource." + envTag + ".driver-class-name" : null),
                            prop(env, "spring.datasource.postgres.driver-class-name"),
                            prop(env, "spring.datasource.driver-class-name"),
                            "org.postgresql.Driver");
                    username = cliDBUser;
                    password = cliDBPass;
                }

                if (url == null || url.isBlank()) {
                    log.warn("No JDBC URL configured for vendor '{}' (path: {}). Provide spring.datasource.{}.url or global spring.datasource.url.", vendor, path, vendor);
                } else {
                    DriverManagerDataSource ds = new DriverManagerDataSource();
                    ds.setUrl(url);
                    if (driver != null && !driver.isBlank()) ds.setDriverClassName(driver);
                    if (username != null) ds.setUsername(username);
                    if (password != null) ds.setPassword(password);
                    JdbcTemplate jt = new JdbcTemplate(ds);
                    DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
                    execForFile = new DmlExecutorService(jt, tx);
                    log.info("Using {} DataSource for {} with user={} url={}", vendor.toUpperCase(), path, (username != null ? username : "<none>"), url);
                }
            }

            if (execForFile == null) {
                if (vendor == null) {
                    log.info("No vendor detected from path '{}'. Using default DataSource if available.", path);
                }
                if (defaultExecutor == null) {
                    log.warn("No default executor available; skipping {}.", path);
                    return;
                }
                execForFile = defaultExecutor;
            }

            if ("validate".equals(mode)) {
                execForFile.validateOnly(cfg);
                log.info("DML validation finished for {}.", path);
            } else {
                execForFile.execute(cfg);
                log.info("DML execution finished for {}.", path);
            }

            // Output results in simple summary and CSV format to console
            try {
                String summary = buildPlainSummary(cfg);
                log.info("DML execution summary for {}:\n{}", path, summary);
            } catch (Exception e) {
                log.warn("Failed to render DML results summary for {}: {}", path, e.getMessage());
            }
            try {
                String csv = buildCsv(cfg);
                log.info("DML execution summary (CSV) for {}:\n{}", path, csv);
            } catch (Exception e) {
                log.warn("Failed to render DML results as CSV for {}: {}", path, e.getMessage());
            }
        };
    }

    private String buildPlainSummary(QueriesConfig cfg) {
        StringBuilder sb = new StringBuilder();
        if (cfg != null && cfg.getResults() != null) {
            // Prefer group-wise summary if available
            List<QueriesConfig.GroupResult> groups = cfg.getResults().getNamed();
            if (groups != null && !groups.isEmpty()) {
                sb.append("Groups (by name):\n");
                for (QueriesConfig.GroupResult g : groups) {
                    sb.append("  ")
                      .append(g.getName() != null ? g.getName() : "<unnamed>")
                      .append(" [").append(g.getType()).append("] ")
                      .append("total=").append(g.getTotal())
                      .append(", success=").append(g.getSuccessCount())
                      .append(", failed=").append(g.getFailureCount())
                      .append('\n');
                    if (g.getOutcomes() != null) {
                        for (QueriesConfig.StatementOutcome o : g.getOutcomes()) {
                            if (!o.isSuccess()) {
                                sb.append("    - FAILED: ")
                                  .append(trimSqlSafe(o.getSql()))
                                  .append(" -> ")
                                  .append(o.getError() != null ? o.getError() : "")
                                  .append('\n');
                            }
                        }
                    }
                }
            }
            // Also include legacy summaries for completeness
            List<QueriesConfig.BatchResult> tx = cfg.getResults().getTransactional();
            if (tx != null && !tx.isEmpty()) {
                sb.append("Transactional batches:\n");
                for (QueriesConfig.BatchResult r : tx) {
                    sb.append("  Batch ")
                      .append(r != null ? r.getBatchNumber() : "?")
                      .append(": ")
                      .append(r != null && r.isSuccess() ? "SUCCESS" : "FAILED");
                    if (r != null && !r.isSuccess() && r.getError() != null) {
                        sb.append(" - ").append(r.getError());
                    }
                    sb.append('\n');
                }
            }
            List<QueriesConfig.StatementResult> nonTx = cfg.getResults().getNonTransactional();
            if (nonTx != null && !nonTx.isEmpty()) {
                sb.append("Non-transactional statements:\n");
                for (QueriesConfig.StatementResult r : nonTx) {
                    sb.append("  Statement ")
                      .append(r != null ? r.getStatementNumber() : "?")
                      .append(": ")
                      .append(r != null && r.isSuccess() ? "SUCCESS" : "FAILED");
                    if (r != null && !r.isSuccess() && r.getError() != null) {
                        sb.append(" - ").append(r.getError());
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String buildCsv(QueriesConfig cfg) {
        StringBuilder sb = new StringBuilder();
        if (cfg != null && cfg.getResults() != null && cfg.getResults().getNamed() != null && !cfg.getResults().getNamed().isEmpty()) {
            sb.append("scope,name,type,number,sql,success,error\n");
            for (QueriesConfig.GroupResult g : cfg.getResults().getNamed()) {
                int i = 0;
                if (g.getOutcomes() != null) {
                    for (QueriesConfig.StatementOutcome o : g.getOutcomes()) {
                        i++;
                        sb.append("group").append(',')
                          .append(csvEscape(g.getName())).append(',')
                          .append(csvEscape(g.getType())).append(',')
                          .append(i).append(',')
                          .append(csvEscape(trimSqlSafe(o.getSql()))).append(',')
                          .append(o.isSuccess()).append(',')
                          .append(csvEscape(o.getError()))
                          .append('\n');
                    }
                }
            }
        } else {
            sb.append("category,number,success,error\n");
        }
        if (cfg != null && cfg.getResults() != null) {
            List<QueriesConfig.BatchResult> tx = cfg.getResults().getTransactional();
            if (tx != null) {
                for (QueriesConfig.BatchResult r : tx) {
                    sb.append("transactional").append(',')
                      .append(r != null ? r.getBatchNumber() : "")
                      .append(',')
                      .append(r != null && r.isSuccess())
                      .append(',')
                      .append(csvEscape(r != null ? r.getError() : null))
                      .append('\n');
                }
            }
            List<QueriesConfig.StatementResult> nonTx = cfg.getResults().getNonTransactional();
            if (nonTx != null) {
                for (QueriesConfig.StatementResult r : nonTx) {
                    sb.append("nonTransactional").append(',')
                      .append(r != null ? r.getStatementNumber() : "")
                      .append(',')
                      .append(r != null && r.isSuccess())
                      .append(',')
                      .append(csvEscape(r != null ? r.getError() : null))
                      .append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"");
        String esc = s.replace("\"", "\"\"");
        return needsQuotes ? "\"" + esc + "\"" : esc;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String prop(Environment env, String key) {
        if (env == null || key == null || key.isBlank()) return null;
        return env.getProperty(key);
    }

    private String trimSqlSafe(String sql) {
        if (sql == null) return "";
        String s = sql.trim().replaceAll("\n+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
