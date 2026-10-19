package com.hic.util;

import com.hic.exception.BadRequestException;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TabularFileUtil {
    public static final String LEAVE_TEMPLATE_NOTE = "Məzuniyyət importu: qırmızı başlıqlı sahələri doldurun. Əməkdaş ID və məzuniyyət növünü Siyahılar səhifəsindən seçin. Tarix: YYYY-MM-DD (məsələn, 2026-10-18). Başlanğıc və bitmə günləri daxildir. Boş vəziyyət Təsdiqlənib sayılır. Xəta varsa heç bir sətir yazılmır. Təkrar və üst-üstə düşən məzuniyyət qəbul edilmir.";

    public static final int MAX_ROWS = 5_000;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final String TEMPLATE_NOTE = "Qırmızı başlıqlı sahələr mütləq doldurulmalıdır. Xanadakı siyahıdan seçin və ya Siyahılar səhifəsindən adı köçürün. Departament seçilmiş əraziyə, vəzifə departamentə uyğun olmalıdır. Əlavə əraziləri ; ilə ayırın. Siyahı boşdursa, əvvəl proqramda yaradın və şablonu yenidən endirin.";

    public ParsedTable read(MultipartFile file) {
        validateFile(file);
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase(Locale.ROOT)
                : "";
        try {
            byte[] bytes = file.getBytes();
            if (filename.endsWith(".csv")) {
                return readCsv(bytes);
            }
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return readExcel(bytes);
            }
        } catch (IOException | CsvValidationException ex) {
            throw new BadRequestException("Fayl oxuna bilmədi: " + ex.getMessage());
        }
        throw new BadRequestException("Yalnız CSV, XLS və XLSX faylları qəbul edilir");
    }

    public byte[] writeExcel(String sheetName, List<String> headers, List<List<String>> rows) {
        return writeExcel(sheetName, headers, rows, Set.of(), Map.of(), List.of());
    }

    public byte[] writeExcelTemplate(String sheetName, List<String> headers, Set<String> requiredHeaders,
                                     Map<String, List<String>> choices, List<List<String>> references) {
        return writeExcel(sheetName, headers, List.of(), requiredHeaders, choices, references);
    }

    private byte[] writeExcel(String sheetName, List<String> headers, List<List<String>> rows,
                              Set<String> requiredHeaders, Map<String, List<String>> choices,
                              List<List<String>> references) {
        return writeExcel(sheetName, headers, rows, requiredHeaders, choices, references, TEMPLATE_NOTE);
    }

    public byte[] writeExcelTemplate(String sheetName, List<String> headers, Set<String> requiredHeaders,
                                    Map<String, List<String>> choices, List<List<String>> references, String note) {
        return writeExcel(sheetName, headers, List.of(), requiredHeaders, choices, references, note);
    }

    private byte[] writeExcel(String sheetName, List<String> headers, List<List<String>> rows,
                              Set<String> requiredHeaders, Map<String, List<String>> choices,
                              List<List<String>> references, String templateNote) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeSheetName(sheetName));
            CellStyle headerStyle = createHeaderStyle(workbook);
            boolean template = !requiredHeaders.isEmpty();
            int headerIndex = template ? 1 : 0;
            CellStyle requiredStyle = createHeaderStyle(workbook);
            Font requiredFont = workbook.createFont();
            requiredFont.setBold(true);
            requiredFont.setColor(IndexedColors.RED.getIndex());
            requiredStyle.setFont(requiredFont);
            if (template) {
                Row note = sheet.createRow(0);
                note.setHeightInPoints(90);
                CellStyle noteStyle = workbook.createCellStyle();
                noteStyle.setFont(requiredFont);
                noteStyle.setWrapText(true);
                note.createCell(0).setCellValue(templateNote);
                note.getCell(0).setCellStyle(noteStyle);
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
            }
            Row headerRow = sheet.createRow(headerIndex);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = headerRow.createCell(column);
                cell.setCellValue(headers.get(column));
                cell.setCellStyle(requiredHeaders.contains(headers.get(column)) ? requiredStyle : headerStyle);
            }

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + headerIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int column = 0; column < headers.size(); column++) {
                    row.createCell(column).setCellValue(column < values.size() ? nullSafe(values.get(column)) : "");
                }
            }

            for (int column = 0; column < headers.size(); column++) {
                sheet.autoSizeColumn(column);
                int width = Math.min(Math.max(sheet.getColumnWidth(column) + 512, 3_000), 12_000);
                sheet.setColumnWidth(column, width);
            }
            if (template) {
                addTemplateChoices(workbook, sheet, headers, choices, references, headerStyle);
            }
            sheet.createFreezePane(0, headerIndex + 1);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new BadRequestException("Excel faylı yaradıla bilmədi: " + ex.getMessage());
        }
    }

    private void addTemplateChoices(Workbook workbook, Sheet input, List<String> headers,
                                    Map<String, List<String>> choices, List<List<String>> references,
                                    CellStyle headerStyle) {
        Sheet lists = workbook.createSheet("Siyahılar");
        Row titles = lists.createRow(0);
        int column = 0;
        for (Map.Entry<String, List<String>> entry : choices.entrySet()) {
            Cell title = titles.createCell(column);
            title.setCellValue(entry.getKey());
            title.setCellStyle(headerStyle);
            List<String> values = entry.getValue();
            for (int index = 0; index < values.size(); index++) {
                Row row = lists.getRow(index + 1);
                if (row == null) row = lists.createRow(index + 1);
                row.createCell(column).setCellValue(values.get(index));
            }
            int inputColumn = headers.indexOf(entry.getKey());
            if (inputColumn >= 0 && !values.isEmpty()) {
                Name range = workbook.createName();
                range.setNameName("Choices_" + column);
                String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column);
                range.setRefersToFormula("'Siyahılar'!$" + letter + "$2:$" + letter + "$" + (values.size() + 1));
                DataValidationHelper helper = input.getDataValidationHelper();
                DataValidation validation = helper.createValidation(
                        helper.createFormulaListConstraint(range.getNameName()),
                        new CellRangeAddressList(2, MAX_ROWS + 1, inputColumn, inputColumn));
                validation.setSuppressDropDownArrow(true);
                // A parent department may also be created in another row of this import.
                validation.setShowErrorBox(!"Valideyn departament".equals(entry.getKey()));
                validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                validation.createErrorBox("Yanlış seçim", "Siyahıdakı adlardan birini seçin.");
                input.addValidationData(validation);
            }
            lists.setColumnWidth(column++, 7000);
        }
        // Keep area/department/position relationships visible for copying the correct names.
        int referenceColumn = column + 1;
        for (int rowIndex = 0; rowIndex < references.size(); rowIndex++) {
            Row row = lists.getRow(rowIndex);
            if (row == null) row = lists.createRow(rowIndex);
            for (int index = 0; index < references.get(rowIndex).size(); index++) {
                Cell cell = row.createCell(referenceColumn + index);
                cell.setCellValue(references.get(rowIndex).get(index));
                if (rowIndex == 0) cell.setCellStyle(headerStyle);
                lists.setColumnWidth(referenceColumn + index, 7000);
            }
        }
        lists.createFreezePane(0, 1);
    }

    public byte[] writeCsv(List<String> headers, List<List<String>> rows) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             OutputStreamWriter streamWriter = new OutputStreamWriter(output, StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(streamWriter)) {
            streamWriter.write('\uFEFF');
            writer.writeNext(headers.toArray(String[]::new), false);
            for (List<String> row : rows) {
                String[] values = new String[headers.size()];
                for (int column = 0; column < headers.size(); column++) {
                    values[column] = column < row.size() ? nullSafe(row.get(column)) : "";
                }
                writer.writeNext(values, false);
            }
            writer.flush();
            return output.toByteArray();
        } catch (IOException ex) {
            throw new BadRequestException("CSV faylı yaradıla bilmədi: " + ex.getMessage());
        }
    }

    public static String normalizeHeader(String value) {
        return nullSafe(value)
                .replace("\uFEFF", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private ParsedTable readCsv(byte[] bytes) throws IOException, CsvValidationException {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        char separator = detectSeparator(content);
        try (CSVReader reader = new CSVReaderBuilder(new StringReader(content))
                .withCSVParser(new CSVParserBuilder().withSeparator(separator).build())
                .build()) {
            String[] headerValues = nextNonBlank(reader);
            if (headerValues == null) {
                throw new BadRequestException("Faylda başlıq sətri yoxdur");
            }
            List<String> headers = normalizeAndValidateHeaders(List.of(headerValues));
            List<ParsedRow> rows = new ArrayList<>();
            String[] values;
            while ((values = reader.readNext()) != null) {
                if (isBlank(values)) {
                    continue;
                }
                rows.add(new ParsedRow((int) reader.getLinesRead(), toValueMap(headers, values)));
                enforceRowLimit(rows.size());
            }
            return new ParsedTable(headers, rows);
        }
    }

    private ParsedTable readExcel(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BadRequestException("Excel faylında səhifə yoxdur");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row headerRow = firstNonBlankRow(sheet, formatter);
            if (headerRow == null) {
                throw new BadRequestException("Faylda başlıq sətri yoxdur");
            }

            int columnCount = Math.max(headerRow.getLastCellNum(), 0);
            List<String> rawHeaders = new ArrayList<>(columnCount);
            for (int column = 0; column < columnCount; column++) {
                rawHeaders.add(formatter.formatCellValue(headerRow.getCell(column)));
            }
            List<String> headers = normalizeAndValidateHeaders(rawHeaders);
            List<ParsedRow> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlank(row, columnCount, formatter)) {
                    continue;
                }
                String[] values = new String[columnCount];
                for (int column = 0; column < columnCount; column++) {
                    values[column] = formatter.formatCellValue(row.getCell(column));
                }
                rows.add(new ParsedRow(rowIndex + 1, toValueMap(headers, values)));
                enforceRowLimit(rows.size());
            }
            return new ParsedTable(headers, rows);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Import faylı seçilməyib");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BadRequestException("Import faylı 5 MB-dan böyük ola bilməz");
        }
    }

    private String[] nextNonBlank(CSVReader reader) throws IOException, CsvValidationException {
        String[] row;
        while ((row = reader.readNext()) != null) {
            if (!isBlank(row)) {
                return row;
            }
        }
        return null;
    }

    private List<String> normalizeAndValidateHeaders(List<String> rawHeaders) {
        List<String> headers = rawHeaders.stream().map(TabularFileUtil::normalizeHeader).toList();
        Set<String> seen = new LinkedHashSet<>();
        for (String header : headers) {
            if (header.isBlank()) {
                throw new BadRequestException("Başlıq adları boş ola bilməz");
            }
            if (!seen.add(header)) {
                throw new BadRequestException("Təkrarlanan başlıq: " + header);
            }
        }
        return headers;
    }

    private Map<String, String> toValueMap(List<String> headers, String[] values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            result.put(headers.get(column), column < values.length ? nullSafe(values[column]).trim() : "");
        }
        return result;
    }

    private Row firstNonBlankRow(Sheet sheet, DataFormatter formatter) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && !TEMPLATE_NOTE.equals(formatter.formatCellValue(row.getCell(0)))
                    && !LEAVE_TEMPLATE_NOTE.equals(formatter.formatCellValue(row.getCell(0)))
                    && !isBlank(row, Math.max(row.getLastCellNum(), 0), formatter)) {
                return row;
            }
        }
        return null;
    }

    private boolean isBlank(Row row, int columnCount, DataFormatter formatter) {
        for (int column = 0; column < columnCount; column++) {
            if (!formatter.formatCellValue(row.getCell(column)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlank(String[] row) {
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private char detectSeparator(String content) {
        String firstLine = content.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        return countOutsideQuotes(firstLine, ';') > countOutsideQuotes(firstLine, ',') ? ';' : ',';
    }

    private long countOutsideQuotes(String line, char target) {
        boolean quoted = false;
        long count = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && value == target) {
                count++;
            }
        }
        return count;
    }

    private void enforceRowLimit(int rowCount) {
        if (rowCount > MAX_ROWS) {
            throw new BadRequestException("Bir faylda maksimum " + MAX_ROWS + " məlumat sətri ola bilər");
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String safeSheetName(String value) {
        String normalized = nullSafe(value).replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (normalized.isBlank()) {
            return "Data";
        }
        return normalized.substring(0, Math.min(normalized.length(), 31));
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    public record ParsedTable(List<String> headers, List<ParsedRow> rows) {
        public boolean hasHeader(String header) {
            return headers.contains(normalizeHeader(header));
        }
    }

    public record ParsedRow(int rowNumber, Map<String, String> values) {
        public String get(String header) {
            return values.getOrDefault(normalizeHeader(header), "");
        }
    }
}
