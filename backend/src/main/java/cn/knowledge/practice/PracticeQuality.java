package cn.knowledge.practice;

import java.util.regex.Pattern;

/** Conservative guards: uncertain transcripts are unsuitable as an exam's source of truth. */
final class PracticeQuality {
    private static final Pattern CHATTER = Pattern.compile("哥们|兄弟们|老铁|家人们|这就\\s*OK|(?:AR|arr|fast|slow)\\s*(?:点上|点|加加|减减)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSCRIPT = Pattern.compile("那么接下来|所以我们|我们来看|我来写|同学们|写上一个注释|一下代码");
    private static final Pattern UNCERTAIN = Pattern.compile("(?:原始|视频|语音)转写|待核对|待核实|未审校|识别不清");
    private static final String HTML_TAG = "(?i:</?(?:script|style|iframe|div|span|img|br|p|a)(?:\\s[^>]*)?/?>)";
    private static final Pattern MARKUP = Pattern.compile("https?://|```|" + HTML_TAG + "|(?m)^\\s*(?:#{1,6}|[-*]|\\d+[.)、])\\s");
    private static final Pattern EVIDENCE_MARKERS = Pattern.compile("\\*\\*(\\S(?:[^\\n]*?\\S)?)\\*\\*|`[^`\\n]+`|(?m)^#{1,6}(?=\\s)");
    private PracticeQuality() {}

    static boolean usableSource(String text) {
        String prose = text.replaceAll("(?s)```.*?```", "").replaceAll("!?\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll(HTML_TAG, "").replaceAll("[#*`]", "").strip();
        if (prose.replaceAll("\\s+", "").length() < 40 || CHATTER.matcher(prose).find() || UNCERTAIN.matcher(prose).find()) return false;
        if (TRANSCRIPT.matcher(prose).results().limit(2).count() >= 2) return false;
        return !longRunOn(prose);
    }

    static boolean clearOutput(String text, int maxLength) {
        return text != null && text.strip().length() >= 4 && text.length() <= maxLength
                && !text.contains("\n") && !text.contains("\r") && !MARKUP.matcher(text).find()
                && !CHATTER.matcher(text).find() && !TRANSCRIPT.matcher(text).find()
                && !longRunOn(text) && !circular(text);
    }

    static boolean standaloneQuestion(String question) {
        return !Pattern.compile("(?:该|此|上述|以上|这个|这段|这种)(?:算法|方法|代码|过程|规则|操作|构造器)").matcher(question).find();
    }

    static boolean concisePoint(String point) {
        return clearOutput(point, 120) && point.split("[。！？!?]").length <= 2;
    }

    static String referencePoint(String question, String point) {
        // Numeric facts do not need another model guess: use the explicit subject already in the question.
        if (point.length() >= 4 || !point.matches("[+-]?\\d+(?:\\.\\d+)?")) return point;
        String stem = question.replaceAll("[？?]+$", "").strip();
        for (String ending : new String[]{"是多少", "为多少", "多少"}) {
            if (stem.endsWith(ending)) return stem.substring(0, stem.length() - ending.length())
                    + (ending.equals("是多少") ? "是" : "为") + point + "。";
        }
        return point;
    }

    private static boolean longRunOn(String text) {
        for (String sentence : text.split("[。！？!?；;\\n]")) {
            if (sentence.replaceAll("[^\\p{IsHan}]", "").length() > 150) return true;
        }
        return false;
    }

    private static boolean circular(String text) {
        int because = text.indexOf("因为");
        if (because < 0) return false;
        String claim = compact(text.substring(0, because)), reason = compact(text.substring(because + 2));
        return claim.length() >= 4 && reason.length() >= 4
                && (claim.equals(reason) || (claim.contains(reason) && reason.length() >= claim.length() * .85));
    }

    static String compact(String text) { return text.replaceAll("[\\s\\p{P}]", ""); }

    /** Match formatting-insensitive evidence, then return the exact original slice, never a model rewrite. */
    static String originalQuote(String content, String evidence) {
        String needle = normalizeEvidence(evidence).text();
        if (needle.length() < 8 || needle.length() > 300) return null;
        var original = normalizeEvidence(content);
        int start = original.text().indexOf(needle);
        return start < 0 ? null : content.substring(original.offsets().get(start), original.offsets().get(start + needle.length() - 1) + 1);
    }

    private record Normalized(String text, java.util.List<Integer> offsets) {}
    private static Normalized normalizeEvidence(String content) {
        boolean[] formatting = new boolean[content.length()];
        var markers = EVIDENCE_MARKERS.matcher(content);
        while (markers.find()) {
            int width = markers.group().startsWith("**") ? 2 : 1;
            if (markers.group().startsWith("#")) {
                for (int i = markers.start(); i < markers.end(); i++) formatting[i] = true;
            } else {
                for (int i = 0; i < width; i++) { formatting[markers.start() + i] = true; formatting[markers.end() - i - 1] = true; }
            }
        }
        StringBuilder normalized = new StringBuilder();
        var offsets = new java.util.ArrayList<Integer>();
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!Character.isWhitespace(ch) && !formatting[i]) {
                normalized.append(ch); offsets.add(i);
            }
        }
        return new Normalized(normalized.toString(), offsets);
    }
}
