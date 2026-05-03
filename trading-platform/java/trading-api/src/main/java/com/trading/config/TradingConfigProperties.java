package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.config")
public class TradingConfigProperties {

    private String directory;

    private String mainConfig;
    private String tradingConfigPath;
    private String indicatorsPath;
    private String rulesPath;
    private String columnsPath;
    private String patternsPath;
    private String quantPath;
    private String substagesPath;
}