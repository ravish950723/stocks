package com.trading.agent.service;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.YamlConfigService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

@Component
@Data
@AllArgsConstructor
public class AppRuntimeConfigProvider {
    private final YamlConfigService yamlConfigService;


    public AppRuntimeConfig get() {
        return yamlConfigService.load();
    }
}