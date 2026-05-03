package com.trading.entry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
public class Candle {

    private LocalDate date;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
}