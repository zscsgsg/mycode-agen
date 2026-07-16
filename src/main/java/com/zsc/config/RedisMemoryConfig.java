package com.zsc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zsc.entity.ChatMemoryWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;



@Configuration
public class RedisMemoryConfig {

    @Bean
    public RedisTemplate<String, ChatMemoryWrapper> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, ChatMemoryWrapper> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 不需要配置多态验证器，因为 StoredMessage 是具体类
        // 如果仍然希望存储类型信息，可以保留但白名单简单些
        Jackson2JsonRedisSerializer<ChatMemoryWrapper> serializer =
                new Jackson2JsonRedisSerializer<>(mapper, ChatMemoryWrapper.class);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}