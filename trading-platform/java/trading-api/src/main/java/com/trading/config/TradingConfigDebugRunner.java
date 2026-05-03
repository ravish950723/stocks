package com.trading.config;

import com.trading.shorts.ShortSellingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingConfigDebugRunner implements CommandLineRunner {

    private final TradingConfigProperties tradingConfigProperties;
    private final ShortSellingConfig shortSellingConfig;

    @Override
    public void run(String... args) {
        log.info("Trading config directory={}", tradingConfigProperties.getDirectory());
        log.info("Trading config path={}", tradingConfigProperties.getTradingConfigPath());
        log.info("ShortSelling shortThreshold={}", shortSellingConfig.getShortThreshold());
        log.info("ShortSelling strongShortThreshold={}", shortSellingConfig.getStrongShortThreshold());
        log.info("ShortSelling weights={}", shortSellingConfig.getWeights());
    }
}