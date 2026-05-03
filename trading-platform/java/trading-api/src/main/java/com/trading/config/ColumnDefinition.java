package com.trading.config;

import lombok.Data;

@Data
public class ColumnDefinition {
    private String name;
    private String key;
    private String type;
    private Object defaultValue;
    private boolean required;
    private String layer;

}
