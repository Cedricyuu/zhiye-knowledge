package cn.knowledge.practice;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import cn.knowledge.common.ApiException;
import cn.knowledge.persistence.DocumentMapper;
import cn.knowledge.persistence.ExerciseMapper;
import cn.knowledge.persistence.ExerciseMapper.Exercise;
import cn.knowledge.persistence.Rows;
import cn.knowledge.rag.Citation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PracticeService {
    public record ExerciseView(String id, String question, List<String> documents, String studentAnswer,
                               PracticeModel.Grade grade, List<PracticeModel.Criterion> referenceAnswer,
                               List<Citation> citations, LocalDateTime createdAt, LocalDateTime gradedAt, boolean qualityChecked,
                               String reviewOf) {}
    // Keep retry metadata beside the saved snapshot. Existing rows need no rewrite or schema migration.
    private record StoredQuestion(String question, List<PracticeModel.Criterion> criteria, List<Citation> citations,
                                  int qualityVersion, String reviewOf) {
        StoredQuestion(PracticeModel.Question source, String reviewOf) {
            this(source.question(), source.criteria(), source.citations(), source.qualityVersion(), reviewOf);
        }
        PracticeModel.Question toQuestion() { return new PracticeModel.Question(question, criteria, citations, qualityVersion); }
    }
    private final DocumentMapper documents;
    private final ExerciseMapper exercises;
    private final PracticeModel model;
    private final ObjectMapper json;
    private final TransactionTemplate writes;
    public PracticeService(DocumentMapper documents, ExerciseMapper exercises, PracticeModel model, ObjectMapper json,
                           PlatformTransactionManager transactions) {
        this.documents = documents; this.exercises = exercises; this.model = model; this.json = json;
        this.writes = new TransactionTemplate(transactions);
    }

    public ExerciseView create(String userId, List<String> documentIds) {
        if (documentIds != null) {
            for (String id : documentIds) { if (documents.owned(id, userId) == null) { throw ApiException.notFound(); } }
        }
        var chunks = documents.searchable(userId, documentIds);
        var eligible = chunks.stream().filter(c -> useful(c.content())).toList();
        if (eligible.isEmpty()) { throw ApiException.badRequest("所选资料没有适合出题的清晰正文：标题、链接、口语转写或含混片段不用于出题。请先整理笔记"); }
        var recent = exercises.list(userId);
        var seen = recent.stream().limit(20).map(Exercise::anchorChunkId).collect(Collectors.toSet());
        var fresh = eligible.stream().filter(c -> !seen.contains(c.id())).toList();
        var pool = fresh.isEmpty() ? eligible : fresh;
        var grouped = pool.stream().collect(Collectors.groupingBy(Rows.SearchChunk::documentId));
        var groups = new ArrayList<>(grouped.values());
        var group = groups.get(ThreadLocalRandom.current().nextInt(groups.size()));
        var anchor = group.get(ThreadLocalRandom.current().nextInt(group.size()));
        // Adjacent text keeps code examples and their explanation together without loading every note into the model.
        var sources = chunks.stream().filter(c -> c.documentId().equals(anchor.documentId()) &&
                Math.abs(c.ordinal() - anchor.ordinal()) <= 1 && useful(c.content())).map(c -> new Citation(c.ordinal(), c.id(), c.documentId(),
                c.documentName(), c.fileType(), c.ordinal(), c.pageNumber(), c.startOffset(), c.content(), 0)).toList();
        // Use compact citation numbers, preserving the original ordinal separately for source navigation.
        List<Citation> numbered = new ArrayList<>();
        for (Citation c : sources) {
            numbered.add(new Citation(numbered.size()+1, c.chunkId(), c.documentId(), c.documentName(), c.fileType(),
                    c.ordinal(), c.pageNumber(), c.startOffset(), c.content(), 0));
        }
        var previous = recent.stream().filter(row -> decode(row.solutionJson(), StoredQuestion.class).citations().stream()
                .anyMatch(c -> c.documentId().equals(anchor.documentId()))).limit(6).map(Exercise::question).toList();
        var question = model.create(numbered, previous);
        var row = new Exercise(UUID.randomUUID().toString(), userId, anchor.id(), question.question(), encode(new StoredQuestion(question, null)),
                null, null, LocalDateTime.now(ZoneOffset.UTC), null);
        exercises.insert(row);
        return view(row);
    }

    public ExerciseView grade(String userId, String id, String answer) {
        return grade(userId, id, answer, null);
    }
    public ExerciseView grade(String userId, String id, String answer, String expectedAnswer) {
        String submitted = answer.strip();
        if (submitted.isEmpty()) { throw ApiException.badRequest("请先写下你的回答，再提交批改"); }
        var row = owned(userId, id);
        if (row.gradeJson() != null) { return repeated(row, submitted); }
        var solution = decode(row.solutionJson(), StoredQuestion.class);
        if (solution.qualityVersion() != PracticeModel.QUALITY_VERSION) {
            throw new ApiException(HttpStatus.CONFLICT, "这道旧版练习未经过新版质量检查，请重新出题；原记录和你的回答仍保留");
        }
        String originalDraft = Objects.requireNonNullElse(row.studentAnswer(), "");
        if (expectedAnswer != null && !originalDraft.equals(expectedAnswer)) {
            throw new ApiException(HttpStatus.CONFLICT, "这道题的草稿已在其他页面更新，请先重新读取记录；当前输入仍保留");
        }
        // Model inference must not hold a database transaction or row lock.
        var result = model.grade(solution.toQuestion(), submitted);
        return writes.execute(status -> {
            var current = exercises.lockOwned(id, userId);
            if (current == null) { throw ApiException.notFound(); }
            if (current.gradeJson() != null) { return repeated(current, submitted); }
            String latestDraft = Objects.requireNonNullElse(current.studentAnswer(), "");
            if (!latestDraft.equals(originalDraft) && !latestDraft.equals(submitted)) {
                throw new ApiException(HttpStatus.CONFLICT, "批改期间草稿已在其他页面更新，本次结果未保存；请重新读取记录后提交");
            }
            if (exercises.grade(id, userId, submitted, encode(result), LocalDateTime.now(ZoneOffset.UTC)) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "这道题的状态已更新，请重新读取记录");
            }
            return view(owned(userId, id));
        });
    }
    @Transactional
    public ExerciseView saveDraft(String userId, String id, String answer, String expectedAnswer) {
        var row = exercises.lockOwned(id, userId);
        if (row == null) { throw ApiException.notFound(); }
        var solution = decode(row.solutionJson(), StoredQuestion.class);
        if (solution.qualityVersion() != PracticeModel.QUALITY_VERSION) {
            throw new ApiException(HttpStatus.CONFLICT, "这道旧版练习未经过新版质量检查，请重新出题");
        }
        if (row.gradeJson() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "这道题已经批改过，不能再修改草稿");
        }
        String current = Objects.requireNonNullElse(row.studentAnswer(), "");
        // Exact text comparison avoids database collation treating different drafts as identical.
        if (current.equals(answer)) { return view(row); }
        if (!current.equals(Objects.requireNonNullElse(expectedAnswer, ""))) {
            throw new ApiException(HttpStatus.CONFLICT, "这道题的草稿已在其他页面更新，请先重新读取记录；当前输入仍保留");
        }
        if (exercises.saveDraft(id, userId, answer) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "这道题的状态已更新，请重新读取记录");
        }
        return view(owned(userId, id));
    }
    @Transactional
    public ExerciseView retry(String userId, String id) {
        // Serialize short retry writes for this owner, including requests made through different attempts.
        if (documents.lockOwner(userId) == null) { throw ApiException.notFound(); }
        var original = owned(userId, id);
        var saved = decode(original.solutionJson(), StoredQuestion.class);
        if (saved.qualityVersion() != PracticeModel.QUALITY_VERSION) {
            throw new ApiException(HttpStatus.CONFLICT, "这道旧版练习未经过新版质量检查，请重新出题");
        }
        if (original.gradeJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "这道题尚未批改，请先完成当前作答");
        }
        String rootId = saved.reviewOf() == null ? original.id() : saved.reviewOf();
        // Search pending attempts without the history's 100-row limit so a late retry cannot duplicate one.
        for (var pending : exercises.pending(userId)) {
            var snapshot = decode(pending.solutionJson(), StoredQuestion.class);
            if (rootId.equals(snapshot.reviewOf()) && snapshot.qualityVersion() == PracticeModel.QUALITY_VERSION) {
                return view(pending);
            }
        }
        var row = new Exercise(UUID.randomUUID().toString(), userId, original.anchorChunkId(), original.question(),
                encode(new StoredQuestion(saved.toQuestion(), rootId)), null, null, LocalDateTime.now(ZoneOffset.UTC), null);
        exercises.insert(row);
        return view(row);
    }
    public List<ExerciseView> list(String userId) { return exercises.list(userId).stream().map(this::view).toList(); }
    public ExerciseView get(String userId, String id) { return view(owned(userId, id)); }
    public void delete(String userId, String id) { if (exercises.delete(id, userId) == 0) { throw ApiException.notFound(); } }
    private Exercise owned(String userId, String id) {
        var row = exercises.owned(id, userId);
        if (row == null) { throw ApiException.notFound(); }
        return row;
    }
    private ExerciseView repeated(Exercise row, String answer) {
        if (answer.equals(row.studentAnswer())) { return view(row); }
        throw new ApiException(HttpStatus.CONFLICT, "这道题已经批改过，请开始下一题");
    }
    private ExerciseView view(Exercise row) {
        var solution = decode(row.solutionJson(), StoredQuestion.class);
        boolean checked = solution.qualityVersion() == PracticeModel.QUALITY_VERSION;
        boolean graded = checked && row.gradeJson() != null;
        return new ExerciseView(row.id(), row.question(), solution.citations().stream().map(Citation::documentName).distinct().toList(),
                row.studentAnswer(), graded ? decode(row.gradeJson(), PracticeModel.Grade.class) : null,
                graded ? solution.criteria() : null, graded ? solution.citations() : List.of(), row.createdAt(), row.gradedAt(), checked,
                solution.reviewOf());
    }
    static boolean useful(String text) {
        return PracticeQuality.usableSource(text);
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private <T> T decode(String value, Class<T> type) { try { return json.readValue(value, type); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); } }
}
