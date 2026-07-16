package com.zsc.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时为 vector_store 增加 BM25 全文索引列与 GIN 索引（幂等）。
 * 必须在 Spring AI 自动建表之后执行，因此设置较低优先级。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(100)
public class DbInitRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    // 保证单例
    private volatile boolean indexReady = false;

    @Override
    public void run(ApplicationArguments args) {
        ensureBM25Index();
    }

    /**
     * 幂等：ALTER COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS。
     * 可被 IndexingService 在首次写入后调用，以防启动时表尚未存在。
     */
    public synchronized void ensureBM25Index() {
        if (indexReady) return;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'vector_store'",
                    Integer.class);
            if (count == null || count == 0) {
                log.warn("[DbInitRunner] vector_store 表不存在，跳过 BM25 索引初始化（首次上传文件后会自动补上）");
                return;
            }
            // BM25 全文索引列（加一列） tsvector PostgreSQL 专门给全文搜索用的类型
            jdbcTemplate.execute(
                    "ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector " +
                            "GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content,''))) STORED");
            // GIN 索引
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_vector_store_tsv ON vector_store USING GIN(content_tsv)");
            indexReady = true;
            log.info("[DbInitRunner] BM25 全文索引列与 GIN 索引就绪");
        } catch (Exception e) {
            log.error("[DbInitRunner] 初始化 BM25 索引失败: {}", e.getMessage());
        }
    }
}
