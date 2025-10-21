package com.mastercard.dgc.bizops;

import com.mastercard.dgc.bizops.dml.DmlExecutorService;
import com.mastercard.dgc.bizops.dml.DmlYamlLoader;
import com.mastercard.dgc.bizops.dml.QueriesConfig;
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

import java.util.List;

@SpringBootApplication(exclude = {org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class})
public class DMLExecutorApplication {
    private static final Logger log = LoggerFactory.getLogger(DMLExecutorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DMLExecutorApplication.class, args);
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
            @Value("${dml.enabled:false}") boolean enabled
    ) {
        return args -> {
            // Print all command-line arguments right at startup
            if (args == null || args.length == 0) {
                log.info("CLI args: <none>");
            } else {
                for (int i = 0; i < args.length; i++) {
                    String a = args[i];
                    log.info("CLI arg[{}]: {}", i, a);
                }
                try {
                    log.info("CLI args (single line): {}", String.join(" ", args));
                } catch (Exception ignore) {
                    // Fallback: individual args are already printed above
                }
            }
            if (!enabled) {
                log.info("DML runner disabled (set dml.enabled=true to enable).");
                return;
            }
            Environment env = envProvider.getIfAvailable();

            // Determine a single YAML path exclusively from command line args
            String yamlPath = null;
            if (args != null) {
                for (String a : args) {
                    if (a == null) continue;
                    if (a.startsWith("--yaml-location=")) {
                        yamlPath = a.substring("--yaml-location=".length());
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
                log.warn("No YAML path provided. Pass --yaml-location=<path> or provide a positional file path argument.");
                return;
            }

            // Parse optional CLI parameters: environment and credentials (generic for all vendors)
            String cliEnv = null; // e.g., dev, qa, prod
            String cliDBUser = null;
            String cliDBPass = null;
            if (args != null) {
                for (String a : args) {
                    if (a == null) continue;
                    if (a.startsWith("--env=")) cliEnv = a.substring("--env=".length());
                    else if (a.startsWith("--auth.id=")) cliDBUser = a.substring("--auth.id=".length());
                    else if (a.startsWith("--auth.secret=")) cliDBPass = a.substring("--auth.secret=".length());
                    // Any --mode or --validateOnly flags are ignored; application always executes.
                }
            }
            if (cliEnv != null && !cliEnv.isBlank()) {
                log.info("Environment parameter detected: {}", cliEnv);
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
                            prop(env, "spring.datasource.oracle.url"));
                    driver = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.oracle.driver-class-name" : null),
                            "oracle.jdbc.OracleDriver");
                } else { // postgres
                    url = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.postgres." + envTag + ".url" : null),
                            prop(env, "spring.datasource.postgres.url"));
                    driver = firstNonBlank(
                            prop(env, envTag != null ? "spring.datasource.postgres.driver-class-name" : null),
                            "org.postgresql.Driver");
                }
                username = cliDBUser;
                password = cliDBPass;

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
                    log.info("Using {} DataSource for {}. URL={}", vendor.toUpperCase(), path, url);
                }
            }

            if (execForFile == null) {
                if (vendor == null) {
                    log.warn("No vendor detected from path '{}'; skipping {}.", path, path);
                } else {
                    log.warn("Unable to create executor for vendor '{}'; skipping {}.", vendor, path);
                }
                return;
            }

            // Always execute (validation mode removed)
            execForFile.execute(cfg);
            log.info("DML execution finished for {}.", path);

            // Output results in simple summary and CSV format to console
            try {
                String summary = buildPlainSummary(cfg);
                log.info("DML execution summary for {}:\n{}", path, summary);
            } catch (Exception e) {
                log.warn("Failed to render DML results summary for {}: {}", path, e.getMessage());
            }
        };
    }

    private String buildPlainSummary(QueriesConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='dml-summary'>");
        // Header with metadata and execution time
        try {
            QueriesConfig.Metadata md = (cfg != null) ? cfg.getMetadata() : null;
            String author = firstNonBlank(md != null ? md.getAuthor() : null, "-");
            String createdOn = firstNonBlank(md != null ? md.getCreatedOn() : null, "-");
            String executedOn = java.time.LocalDateTime.now().toString();
            sb.append("<h3><b>Author:</b> ").append(htmlEscape(author))
              .append(" &nbsp; <b>Created On:</b> ").append(htmlEscape(createdOn))
              .append(" &nbsp; <b>Executed On:</b> ").append(htmlEscape(executedOn))
              .append("</h3>");
        } catch (Exception ignored) {}
        if (cfg != null && cfg.getResults() != null) {
            // Prefer group-wise summary if available
            List<QueriesConfig.GroupResult> groups = cfg.getResults().getNamed();
            if (groups != null && !groups.isEmpty()) {
                // Groups overview table
                sb.append("<h3>Groups (by name)</h3>");
                sb.append("<table border='1' cellspacing='0' cellpadding='4'>");
                sb.append("<thead><tr>")
                  .append("<th>Group</th>")
                  .append("<th>Type</th>")
                  .append("<th>Total</th>")
                  .append("<th>Success</th>")
                  .append("<th>Failed</th>")
                  .append("<th>Rows Updated</th>")
                  .append("<th>Rows Returned</th>")
                  .append("</tr></thead><tbody>");
                for (QueriesConfig.GroupResult g : groups) {
                    int rowsUpdatedTotal = 0;
                    int rowsReturnedTotal = 0;
                    if (g.getOutcomes() != null) {
                        for (QueriesConfig.StatementOutcome o : g.getOutcomes()) {
                            if (o != null && o.isSuccess()) {
                                if (o.getRowsUpdated() != null) rowsUpdatedTotal += o.getRowsUpdated();
                                if (o.getRowsReturned() != null) rowsReturnedTotal += o.getRowsReturned();
                            }
                        }
                    }
                    sb.append("<tr>")
                      .append("<td>").append(htmlEscape(g.getName() != null ? g.getName() : "<unnamed>")).append("</td>")
                      .append("<td>").append(htmlEscape(g.getType())).append("</td>")
                      .append("<td>").append(g.getTotal()).append("</td>")
                      .append("<td>").append(g.getSuccessCount()).append("</td>")
                      .append("<td>").append(g.getFailureCount()).append("</td>")
                      .append("<td>").append(rowsUpdatedTotal).append("</td>")
                      .append("<td>").append(rowsReturnedTotal).append("</td>")
                      .append("</tr>");
                }
                sb.append("</tbody></table>");

                // Detailed statements table
                sb.append("<h3>Statements</h3>");
                sb.append("<table border='1' cellspacing='0' cellpadding='4'>");
                sb.append("<thead><tr>")
                  .append("<th>Group</th>")
                  .append("<th>#</th>")
                  .append("<th>Status</th>")
                  .append("<th>Rows Updated</th>")
                  .append("<th>Rows Returned</th>")
                  .append("<th>SQL</th>")
                  .append("<th>Error</th>")
                  .append("</tr></thead><tbody>");
                for (QueriesConfig.GroupResult g : groups) {
                    if (g.getOutcomes() == null) continue;
                    int idx = 0;
                    for (QueriesConfig.StatementOutcome o : g.getOutcomes()) {
                        idx++;
                        boolean ok = o != null && o.isSuccess();
                        sb.append("<tr>")
                          .append("<td>").append(htmlEscape(g.getName() != null ? g.getName() : "<unnamed>")).append("</td>")
                          .append("<td>").append(idx).append("</td>")
                          .append("<td>")
                          .append(ok ? "<span style='color:green'>OK</span>" : "<span style='color:red'>FAILED</span>")
                          .append("</td>")
                          .append("<td>").append(ok ? String.valueOf(o.getRowsUpdated() != null ? o.getRowsUpdated() : 0) : "-").append("</td>")
                          .append("<td>").append(ok ? String.valueOf(o.getRowsReturned() != null ? o.getRowsReturned() : 0) : "-").append("</td>")
                          .append("<td>").append(htmlEscape(trimSqlSafe(o != null ? o.getSql() : ""))).append("</td>")
                          .append("<td>").append(!ok ? htmlEscape(o != null && o.getError() != null ? o.getError() : "") : "").append("</td>")
                          .append("</tr>");
                    }
                }
                sb.append("</tbody></table>");
            }
        }
        // Fallbacks when there are no results to display
        if (sb.toString().equals("<div class='dml-summary'>")) {
            if (cfg == null) {
                sb.append("<p>No configuration loaded.</p>");
            } else {
                QueriesConfig.Queries q = cfg.getQueries();
                int groupCount = (q != null && q.getGroups() != null) ? q.getGroups().size() : 0;
                int txCount = 0;
                if (q != null && q.getTransactional() != null) {
                    for (List<String> b : q.getTransactional()) if (b != null) txCount += b.size();
                }
                int nonTxCount = (q != null && q.getNonTransactional() != null) ? q.getNonTransactional().size() : 0;
                sb.append("<p>No execution results available.</p>");
                if (groupCount > 0) {
                    sb.append("<p>Configured groups: ").append(groupCount).append("</p>");
                }
                if (txCount > 0 || nonTxCount > 0) {
                    sb.append("<p>Configured statements - transactional: ").append(txCount)
                      .append(", non-transactional: ").append(nonTxCount).append("</p>");
                }
                sb.append("<p>No execution results available. Possible reasons: execution produced no reportable results (e.g., no statements or only DDL with no row counts) or execution was skipped.</p>");
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default:
                    if (c < 32) out.append(' '); else out.append(c);
            }
        }
        return out.toString();
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
