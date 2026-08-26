package com.sportshop.shared.db;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SQLiteConfiguration {

    @Bean
    DataSource dataSource(@Value("${spring.datasource.url}") String jdbcUrl) {
        return new ReloadableDataSource(jdbcUrl);
    }
}
