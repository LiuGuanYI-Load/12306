package org.jav.train12306;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@MapperScan("org.jav.train12306.dao.mapper")
@EnableFeignClients("org.jav.train12306.remote")
@EnableRetry
public class PayServiceApplication {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}