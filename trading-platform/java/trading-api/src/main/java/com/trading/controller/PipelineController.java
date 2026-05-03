package com.trading.controller;

import com.trading.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final PipelineService pipelineService;

    @GetMapping("/run")
    public String run() {
        return pipelineService.runPipeline();
    }

    @GetMapping("/health")
    public String health() {
        return "UP";
    }
}