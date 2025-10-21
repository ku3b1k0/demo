package com.mastercard.dgc.bizops.dml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads DML queries from a YAML resource.
 * Supports only the new structure (queries: [ { name, type, queries: [...] }, ... ]).
 */
public class DmlYamlLoader {
    private static final Logger log = LoggerFactory.getLogger(DmlYamlLoader.class);

    private final ResourceLoader resourceLoader;

    public DmlYamlLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @SuppressWarnings("unchecked")
    public QueriesConfig load(String location) {
        try {
            String resolved = resolveLocation(location);
            Resource resource = resourceLoader.getResource(resolved);
            if (!resource.exists()) {
                // Attempt to resolve common pitfalls: extension mismatch (.yml vs .yaml) and missing prefix
                String alt = null;
                if (location != null) {
                    if (location.endsWith(".yml")) alt = location.substring(0, location.length() - 4) + ".yaml";
                    else if (location.endsWith(".yaml")) alt = location.substring(0, location.length() - 5) + ".yml";
                }
                // Try filesystem with alternate extension if original not found
                if (location != null && !location.contains(":")) {
                    File fOrig = new File(location);
                    if (!fOrig.exists() && alt != null) {
                        File fAlt = new File(alt);
                        if (fAlt.exists()) {
                            resolved = "file:" + fAlt.getAbsolutePath();
                            resource = resourceLoader.getResource(resolved);
                        }
                    }
                }
                if (!resource.exists()) {
                    log.warn("DML YAML resource not found at {}{}", resolved, (alt != null ? " (also tried alt extension)" : ""));
                    return new QueriesConfig();
                } else {
                    log.info("Resolved DML YAML resource to {}", resolved);
                }
            }
            try (InputStream in = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(in);
                if (root == null) return new QueriesConfig();

                // Expecting new structure root: { queries: [ { name, type, queries: [...] }, ... ] }
                if (root instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) root;
                    Object queriesNode = map.get("queries");
                    if (queriesNode instanceof List) {
                        // New structure
                        return fromNewStructure((List<Object>) queriesNode);
                    } else {
                        // Unsupported shape for 'queries' when not a list; attempt POJO bind as fallback
                        log.warn("Unsupported 'queries' node structure; expected a list. Falling back to POJO bind.");
                        try {
                            String resolved2 = resolveLocation(location);
                            Resource res2 = resourceLoader.getResource(resolved2);
                            try (InputStream in2 = res2.getInputStream()) {
                                return new Yaml().loadAs(in2, QueriesConfig.class);
                            }
                        } catch (Exception ignored) {
                            // Fall through
                        }
                    }
                }
                // As a last resort, attempt to bind to QueriesConfig
                try {
                    String resolved2 = resolveLocation(location);
                    Resource res2 = resourceLoader.getResource(resolved2);
                    try (InputStream in2 = res2.getInputStream()) {
                        return new Yaml().loadAs(in2, QueriesConfig.class);
                    }
                } catch (Exception ignored) {}
                return new QueriesConfig();
            }
        } catch (Exception e) {
            log.error("Failed to load DML YAML from {}: {}", location, e.getMessage(), e);
            return new QueriesConfig();
        }
    }

    @SuppressWarnings("unchecked")
    private QueriesConfig fromNewStructure(List<Object> items) {
        QueriesConfig cfg = new QueriesConfig();
        List<List<String>> transactional = new ArrayList<>();
        List<String> nonTransactional = new ArrayList<>();
        List<QueriesConfig.Group> groups = new ArrayList<>();

        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String name = safeString(m.get("name"));
            String type = safeString(m.get("type"));
            Object q = m.get("queries");
            List<String> statements = toStringList(q);
            if (statements.isEmpty()) continue;

            String t = type != null ? type.toLowerCase(Locale.ROOT).replace('_', '-') : "";
            if ("transactional".equals(t)) {
                transactional.add(statements);
            } else if ("non-transactional".equals(t) || "nontransactional".equals(t) || "non_transactional".equals(t)) {
                nonTransactional.addAll(statements);
            } else {
                // Default to non-transactional if type unknown
                nonTransactional.addAll(statements);
                t = "non-transactional";
            }
            // Preserve group for per-name execution reporting
            QueriesConfig.Group g = new QueriesConfig.Group();
            g.setName(name);
            g.setType(t);
            g.setQueries(statements);
            groups.add(g);
        }

        cfg.getQueries().setTransactional(transactional);
        cfg.getQueries().setNonTransactional(nonTransactional);
        cfg.getQueries().setGroups(groups);
        return cfg;
    }


    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof List) {
            for (Object o : (List<Object>) node) {
                String s = safeString(o);
                if (s != null && !s.isBlank()) out.add(s);
            }
        } else if (node instanceof String) {
            String s = (String) node;
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private String safeString(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private String resolveLocation(String location) {
        if (location == null) return "";
        // If it already has a known prefix, keep as is
        if (location.contains(":")) return location;
        // If it looks like an existing file, prepend file:
        File f = new File(location);
        if (f.exists()) {
            return "file:" + f.getAbsolutePath();
        }
        // Fall back to treating as classpath
        return location;
    }
}
