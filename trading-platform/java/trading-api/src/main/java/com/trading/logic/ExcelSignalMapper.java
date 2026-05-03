package com.trading.logic;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Refactor target for Excel row key/display-name mapping. */
@Service
public class ExcelSignalMapper {
    public void putBoth(Map<String, Object> row, String key, String displayName, Object value) {
        if (row == null) return;
        row.put(key, value);
        row.put(displayName, value);
    }
}
