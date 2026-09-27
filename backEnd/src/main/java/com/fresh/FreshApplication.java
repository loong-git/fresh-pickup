package com.fresh;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableScheduling（T-超时关单）：开启 @Scheduled 调度，驱动 OrderTimeoutTask
 * 定期关闭超时未支付订单（口径见 OrderServiceImpl#closeTimeoutOrders）
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.fresh.mapper")
public class FreshApplication {
    public static void main(String[] args) {
        SpringApplication.run(FreshApplication.class, args);
    }
}
