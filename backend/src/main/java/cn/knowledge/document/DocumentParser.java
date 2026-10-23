package cn.knowledge.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import cn.knowledge.common.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    public static final int MAX_CHARACTERS = 120_000;
    public record Page(int number, String text) {}
    public record Part(int ordinal, int pageNumber, int startOffset, String content) {}
    public record Parsed(int pageCount, List<Part> parts) {}

    public Parsed parse(String type, byte[] bytes) {
        try {
            List<Page> pages = new ArrayList<>();
            if (type.equals("pdf")) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.isEncrypted()) { throw ApiException.badRequest("请先解除 PDF 密码保护再上传"); }
                    if (pdf.getNumberOfPages() > 100) { throw ApiException.badRequest("第一版支持 100 页以内的 PDF"); }
                    var stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    int characters = 0;
                    for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String text = normalize(stripper.getText(pdf));
                        characters += text.length();
                        checkLength(characters);
                        pages.add(new Page(page, text));
                    }
                }
            } else {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                if (text.indexOf('\0') >= 0) { throw ApiException.badRequest("文件包含二进制内容，请上传 UTF-8 文本"); }
                text = normalize(text);
                checkLength(text.length());
                pages.add(new Page(1, text));
            }
            List<Part> parts = split(pages);
            if (parts.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "没有提取到可阅读的文字；扫描版 PDF 请先完成 OCR");
            }
            return new Parsed(pages.size(), parts);
        } catch (CharacterCodingException ex) {
            throw ApiException.badRequest("文本编码不是 UTF-8，请转换后上传");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "无法解析文件，请检查文件是否完整或受密码保护");
        }
    }

    // Page boundaries are retained. Offsets always refer to the normalized text shown in the preview.
    List<Part> split(List<Page> pages) {
        List<Part> parts = new ArrayList<>();
        for (Page page : pages) {
            int start = 0;
            while (start < page.text().length()) {
                int end = Math.min(start + 420, page.text().length());
                if (end < page.text().length()) {
                    int boundary = Math.max(page.text().lastIndexOf('\n', end - 1),
                            page.text().lastIndexOf('。', end - 1));
                    if (boundary > start + 210) { end = boundary + 1; }
                }
                if (end < page.text().length() && Character.isHighSurrogate(page.text().charAt(end - 1))) { end--; }
                String content = page.text().substring(start, end);
                if (!content.isBlank()) {
                    parts.add(new Part(parts.size() + 1, page.number(), start, content));
                }
                if (end == page.text().length()) { break; }
                start = end - 60;
                if (start > 0 && Character.isLowSurrogate(page.text().charAt(start))) { start--; }
            }
        }
        return List.copyOf(parts);
    }

    private String normalize(String value) {
        return value.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private void checkLength(int characters) {
        if (characters > MAX_CHARACTERS) {
            throw ApiException.badRequest("单份文档提取后的文字不能超过 12 万字符，请拆分上传");
        }
    }
}
