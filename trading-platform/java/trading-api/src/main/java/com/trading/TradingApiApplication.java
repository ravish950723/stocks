package com.trading;

import com.trading.ml.MlClient;
import com.trading.service.PipelineService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TradingApiApplication {

    private static final Logger log = LogManager.getLogger(TradingApiApplication.class);

    public static void main(String[] args) {
        log.info("Starting TradingApiApplication");
        SpringApplication.run(TradingApiApplication.class, args);
    }

    @Bean
    CommandLineRunner pipelineRunner(PipelineService pipelineService, MlClient mlClient) {
        return args -> {
            log.info("Executing pipeline from CommandLineRunner");
            mlClient.verifyAvailableOnce();
            String result = pipelineService.runPipeline();
            log.info(result);
        };
    }
}