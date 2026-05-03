package com.trading.entry.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EntryCandidate {

    private String label;
    private EntrySource source;
    private double price;
    private double score;
    private int weeks;
    private boolean valid;
    private String reason;
}