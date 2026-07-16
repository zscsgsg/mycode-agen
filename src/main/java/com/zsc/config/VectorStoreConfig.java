package com.zsc.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
//
//@Configuration
//public class VectorStoreConfig {
//
////    @Bean
////    public PgVectorStore pgVectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
////        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
////        PgVectorStoreConfig config = PgVectorStoreConfig.builder()
////                .withTableName("vector_store")
////                .withCreateVectorTable(true)   // 自动建表
////                .build();
////        return PgVectorStore.builder(jdbcTemplate, embeddingModel, config).build();
////    }
//}