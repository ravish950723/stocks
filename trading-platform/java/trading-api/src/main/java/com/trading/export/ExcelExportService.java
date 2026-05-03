package com.trading.export;

import com.trading.config.AppRuntimeConfig;
import com.trading.config.ColumnDefinition;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Data
public class ExcelExportService {

    private static final Logger log = LogManager.getLogger(ExcelExportService.class);

    public void export(AppRuntimeConfig config, List<Map<String, Object>> rows) {
        Path outputPath = Path.of(config.getOutputFile());
        log.info("Exporting {} pipeline rows to Excel file={}", rows.size(), outputPath.toAbsolutePath());
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            try (XSSFWorkbook workbook = new XSSFWorkbook();
                 OutputStream outputStream = Files.newOutputStream(outputPath)) {
                XSSFSheet sheet = workbook.createSheet("predictions_summary_out_nextgen");
                writeHeader(sheet, config.getColumns());
                writeRows(sheet, config.getColumns(), rows);
                autosize(sheet, config.getColumns().size());
                workbook.write(outputStream);
                log.info("Excel export completed successfully at {}", outputPath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Failed to write Excel output to {}", outputPath, e);
            throw new IllegalStateException("Failed to write Excel output to " + outputPath, e);
        }
    }

    private void writeHeader(XSSFSheet sheet, List<ColumnDefinition> columns) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(columns.get(i).getName());
        }
    }

    private void writeRows(XSSFSheet sheet, List<ColumnDefinition> columns, List<Map<String, Object>> rows) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row excelRow = sheet.createRow(rowIndex + 1);
            Map<String, Object> data = applyDefaults(columns, rows.get(rowIndex));
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                ColumnDefinition column = columns.get(columnIndex);
                Object value = data.getOrDefault(column.getKey(), column.getDefaultValue());
                writeCell(excelRow.createCell(columnIndex), value, column.getType());
            }
        }
    }

    private Map<String, Object> applyDefaults(List<ColumnDefinition> columns, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        for (ColumnDefinition column : columns) {
            normalized.putIfAbsent(column.getKey(), column.getDefaultValue());
        }
        return normalized;
    }

    private void writeCell(Cell cell, Object value, String type) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if ("bool".equalsIgnoreCase(type) || value instanceof Boolean) {
            cell.setCellValue(Boolean.parseBoolean(String.valueOf(value)));
            return;
        }
        if (("float".equalsIgnoreCase(type) || "int".equalsIgnoreCase(type) || value instanceof Number)) {
            try {
                cell.setCellValue(Double.parseDouble(String.valueOf(value)));
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        cell.setCellValue(String.valueOf(value));
    }

    private void autosize(XSSFSheet sheet, int numberOfColumns) {
        for (int i = 0; i < numberOfColumns; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
