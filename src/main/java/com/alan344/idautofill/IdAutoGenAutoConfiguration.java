package com.alan344.idautofill;

import com.alan344.idautofill.mybatis.IdGenInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author AlanSun
 * @date 2022/12/10 16:17
 */
@Configuration(proxyBeanMethods = false)
public class IdAutoGenAutoConfiguration {

    @Bean
    public IdFillService idFillService() {
        return new IdFillService();
    }

    @Bean
    public IdGenInterceptor idGenInterceptor() {
        return new IdGenInterceptor();
    }
}
