package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实习僧与牛客网共用的配置、投递记录和分析存储。 */
@Service
@RequiredArgsConstructor
public class CampusPlatformService {
    private final JdbcTemplate jdbc;

    @PostConstruct
    public void initTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS campus_platform_config (
                  platform TEXT PRIMARY KEY,
                  keywords TEXT NOT NULL DEFAULT '',
                  city TEXT NOT NULL DEFAULT '',
                  sort_order TEXT NOT NULL DEFAULT 'latest',
                  resume_index INTEGER NOT NULL DEFAULT 1,
                  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
        addColumnIfMissing("campus_platform_config", "sort_order", "TEXT NOT NULL DEFAULT 'latest'");
        addColumnIfMissing("campus_platform_config", "resume_index", "INTEGER NOT NULL DEFAULT 1");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS campus_platform_data (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  platform TEXT NOT NULL,
                  job_id TEXT NOT NULL,
                  job_title TEXT,
                  company_name TEXT,
                  job_link TEXT,
                  salary TEXT,
                  location TEXT,
                  delivery_status TEXT NOT NULL DEFAULT 'discovered',
                  failure_reason TEXT,
                  delivered_at DATETIME,
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE(platform, job_id)
                )""");
        jdbc.update("INSERT OR IGNORE INTO campus_platform_config(platform, keywords, city) VALUES(?,?,?)",
                "shixiseng", "", "");
        jdbc.update("INSERT OR IGNORE INTO campus_platform_config(platform, keywords, city) VALUES(?,?,?)",
                "nowcoder", "", "");
    }

    public Map<String, Object> getConfig(String platform) {
        return jdbc.queryForMap("SELECT platform,keywords,city,sort_order AS sortOrder," +
                "resume_index AS resumeIndex,updated_at AS updatedAt " +
                "FROM campus_platform_config WHERE platform=?", platform);
    }

    public Map<String, Object> saveConfig(String platform, Map<String, Object> input) {
        String keywords = String.valueOf(input.getOrDefault("keywords", "")).trim();
        String city = String.valueOf(input.getOrDefault("city", "")).trim();
        String sortOrder = "default".equals(String.valueOf(input.get("sortOrder"))) ? "default" : "latest";
        int resumeIndex = Math.max(1, parseInt(input.get("resumeIndex"), 1));
        jdbc.update("UPDATE campus_platform_config SET keywords=?,city=?,sort_order=?,resume_index=?," +
                        "updated_at=CURRENT_TIMESTAMP WHERE platform=?",
                keywords, city, sortOrder, resumeIndex, platform);
        return getConfig(platform);
    }

    public void upsertDiscovered(String platform, String jobId, String title, String company,
                                 String link, String salary, String location) {
        jdbc.update("""
                INSERT INTO campus_platform_data(platform,job_id,job_title,company_name,job_link,salary,location)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(platform,job_id) DO UPDATE SET
                  job_title=excluded.job_title, company_name=excluded.company_name,
                  job_link=excluded.job_link, salary=excluded.salary, location=excluded.location,
                  updated_at=CURRENT_TIMESTAMP
                """, platform, jobId, title, company, link, salary, location);
    }

    public boolean wasDelivered(String platform, String jobId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM campus_platform_data " +
                "WHERE platform=? AND job_id=? AND delivery_status='delivered'", Integer.class, platform, jobId);
        return count != null && count > 0;
    }

    public void markDelivered(String platform, String jobId) {
        jdbc.update("UPDATE campus_platform_data SET delivery_status='delivered',failure_reason=NULL," +
                "delivered_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE platform=? AND job_id=?",
                platform, jobId);
    }

    public void markFailed(String platform, String jobId, String reason) {
        jdbc.update("UPDATE campus_platform_data SET delivery_status='failed',failure_reason=?," +
                "updated_at=CURRENT_TIMESTAMP WHERE platform=? AND job_id=?", trim(reason, 500), platform, jobId);
    }

    public Map<String, Object> stats(String platform) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count(platform, null));
        result.put("delivered", count(platform, "delivered"));
        result.put("failed", count(platform, "failed"));
        result.put("pending", count(platform, "discovered"));
        result.put("todayDelivered", jdbc.queryForObject("SELECT COUNT(*) FROM campus_platform_data " +
                "WHERE platform=? AND delivery_status='delivered' AND date(delivered_at)=date('now','localtime')",
                Integer.class, platform));
        result.put("daily", jdbc.queryForList("SELECT date(delivered_at) AS date,COUNT(*) AS count " +
                "FROM campus_platform_data WHERE platform=? AND delivery_status='delivered' AND delivered_at IS NOT NULL " +
                "GROUP BY date(delivered_at) ORDER BY date DESC LIMIT 14", platform));
        return result;
    }

    public List<Map<String, Object>> list(String platform, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return jdbc.queryForList("SELECT id,job_id AS jobId,job_title AS jobTitle,company_name AS companyName," +
                "job_link AS jobLink,salary,location,delivery_status AS deliveryStatus,failure_reason AS failureReason," +
                "delivered_at AS deliveredAt,created_at AS createdAt FROM campus_platform_data WHERE platform=? " +
                "ORDER BY id DESC LIMIT ? OFFSET ?", platform, safeSize, (safePage - 1) * safeSize);
    }

    private int count(String platform, String status) {
        Integer count = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM campus_platform_data WHERE platform=?", Integer.class, platform)
                : jdbc.queryForObject("SELECT COUNT(*) FROM campus_platform_data WHERE platform=? AND delivery_status=?",
                    Integer.class, platform, status);
        return count == null ? 0 : count;
    }

    private int parseInt(Object value, int fallback) {
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try { jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition); }
        catch (Exception ignored) { /* SQLite 已有该列时会报 duplicate column */ }
    }
}
