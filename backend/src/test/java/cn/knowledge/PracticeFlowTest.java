package cn.knowledge;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import cn.knowledge.auth.AuthService;
import cn.knowledge.common.ApiException;
import cn.knowledge.persistence.DocumentMapper;
import cn.knowledge.persistence.Rows;
import cn.knowledge.practice.PracticeModel;
import cn.knowledge.rag.Citation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:practice-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","kb.ai-enabled=false"})
@AutoConfigureMockMvc
class PracticeFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired DocumentMapper documents;
    @Autowired JdbcTemplate db;
    @MockitoBean PracticeModel model;
    static final String CONTENT = "子类构造方法会先调用父类构造方法，完成父类部分的初始化，再执行子类构造方法的剩余内容。静态方法属于类，不参与实例方法的多态。";
    @BeforeEach void setupModel() {
        doAnswer(call -> {
            List<Citation> sources = call.getArgument(0);
            return new PracticeModel.Question("子类构造方法先初始化哪一部分？",List.of(new PracticeModel.Criterion("先初始化父类部分",1,CONTENT)),sources,PracticeModel.QUALITY_VERSION);
        }).when(model).create(anyList(),anyList());
        doReturn(new PracticeModel.Grade(100,"本题已掌握",List.of(new PracticeModel.Check(1,"CORRECT","顺序正确","先初始化父类部分")))).when(model).grade(any(),anyString());
    }
    @Test void hidesReferenceUntilSubmissionAndPersistsTheGradedRecord() throws Exception {
        var owner = account(); String doc = note(owner, CONTENT);
        var created = tree(create(owner,doc).andExpect(status().isCreated()).andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.grade").doesNotExist()).andExpect(jsonPath("$.citations").isEmpty()).andExpect(jsonPath("$.qualityChecked").value(true)));
        String id = created.path("id").asText();
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner))).andExpect(jsonPath("$.referenceAnswer").doesNotExist());
        mvc.perform(get("/api/practice").header("Authorization",bearer(owner))).andExpect(jsonPath("$[0].referenceAnswer").doesNotExist());
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk()).andExpect(jsonPath("$.grade.score").value(100))
                .andExpect(jsonPath("$.referenceAnswer[0].point").value("先初始化父类部分"));
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner))).andExpect(jsonPath("$.studentAnswer").value("先初始化父类部分"));
        assertThat(db.queryForObject("SELECT grade_json FROM kb_exercise WHERE id=?",String.class,id)).contains("100");
    }
    @Test void selectedDocumentsAndExercisesAreIsolatedByUser() throws Exception {
        var owner = account(); var other = account(); String doc = note(owner,CONTENT);
        String id = tree(create(owner,doc).andExpect(status().isCreated())).path("id").asText();
        create(other,doc).andExpect(status().isNotFound());
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(other))).andExpect(status().isNotFound());
        submit(other,id,"答案").andExpect(status().isNotFound());
        mvc.perform(delete("/api/practice/{id}",id).header("Authorization",bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/practice").header("Authorization",bearer(other))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/practice").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
    }
    @Test void titleOnlyMaterialAndEmptySelectionsCannotCreateFakeExercises() throws Exception {
        var owner = account();
        create(owner).andExpect(status().isBadRequest());
        create(owner,note(owner,"# 无标题\n[导航](https://example.com)" )).andExpect(status().isBadRequest());
        verify(model,never()).create(anyList(),anyList());
    }
    @Test void repeatedSubmissionIsIdempotentAndCannotReplaceAnExistingAnswer() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk());
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk());
        submit(owner,id,"另一份答案").andExpect(status().isConflict());
        verify(model,times(1)).grade(any(),anyString());
    }
    @Test void modelFailureDoesNotSaveFakeQuestionOrGradeAndCanBeRetried() throws Exception {
        var owner = account(); String doc = note(owner,CONTENT);
        when(model.create(anyList(),anyList())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"模型失败"));
        create(owner,doc).andExpect(status().isBadGateway());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=?",Integer.class,owner.user().id())).isZero();
        setupModel(); String id = tree(create(owner,doc)).path("id").asText();
        when(model.grade(any(),anyString())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"模型失败"));
        submit(owner,id,"回答").andExpect(status().isBadGateway());
        assertThat(db.queryForObject("SELECT grade_json FROM kb_exercise WHERE id=?",String.class,id)).isNull();
        setupModel(); submit(owner,id,"回答").andExpect(status().isOk());
    }
    @Test void deletedDocumentDoesNotDestroyExerciseSnapshotAndOwnerCanRemoveRecord() throws Exception {
        var owner = account(); String doc = note(owner,CONTENT); String id = tree(create(owner,doc)).path("id").asText();
        documents.delete(doc,owner.user().id());
        submit(owner,id,"先初始化父类").andExpect(status().isOk()).andExpect(jsonPath("$.citations[0].content").value(CONTENT));
        mvc.perform(delete("/api/practice/{id}",id).header("Authorization",bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner))).andExpect(status().isNotFound());
    }
    @Test void rejectsBlankAndOversizedAnswersBeforeModelCalls() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        submit(owner,id,"  ").andExpect(status().isBadRequest());
        submit(owner,id,"a".repeat(4001)).andExpect(status().isBadRequest());
        verify(model,never()).grade(any(),anyString());
    }
    @Test void noisyTranscriptAndRejectedCandidateCannotBeSavedAsExercises() throws Exception {
        var owner=account();
        create(owner,note(owner,"当循环结束之后就表示所有的数据我都已经比较完了所以最后哥们只要做一件事情就是变为数组这就OK哥们可以写上AR点上4i一个" )).andExpect(status().isBadRequest());
        verify(model,never()).create(anyList(),anyList());
        doThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"质量检查未通过")).when(model).create(anyList(),anyList());
        create(owner,note(owner,CONTENT)).andExpect(status().isUnprocessableEntity());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=?",Integer.class,owner.user().id())).isZero();
    }
    @Test void oldRecordsRemainUnchangedButTheirUncheckedReferenceAndGradeAreNotPublished() throws Exception {
        var owner=account(); String id=tree(create(owner,note(owner,CONTENT))).path("id").asText();
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk());
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,id));
        legacy.remove("qualityVersion");
        String original=json.writeValueAsString(legacy);
        db.update("UPDATE kb_exercise SET solution_json=? WHERE id=?",original,id);
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                .andExpect(jsonPath("$.qualityChecked").value(false)).andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.grade").doesNotExist()).andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.studentAnswer").value("先初始化父类部分"));
        assertThat(db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo(original);
        assertThat(db.queryForObject("SELECT grade_json FROM kb_exercise WHERE id=?",String.class,id)).contains("100");
        db.update("UPDATE kb_exercise SET grade_json=NULL,student_answer=NULL WHERE id=?",id);
        submit(owner,id,"新回答").andExpect(status().isConflict());
    }
    @Test void questionsFromOtherDocumentsAreNotSentAsCurrentChapterHistory() throws Exception {
        var owner=account();
        create(owner,note(owner,CONTENT)).andExpect(status().isCreated());
        create(owner,note(owner,CONTENT)).andExpect(status().isCreated());
        var history=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model,times(2)).create(anyList(),history.capture());
        assertThat(history.getAllValues()).allSatisfy(items -> assertThat(items).isEmpty());
    }
    @Test void retryKeepsTheOriginalResultAndHidesTheSavedSolutionUntilItsOwnSubmission() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        doReturn(new PracticeModel.Grade(0,"需要复习",List.of(new PracticeModel.Check(1,"INCORRECT","顺序颠倒","先初始化子类"))))
                .when(model).grade(any(),anyString());
        submit(owner,id,"先初始化子类").andExpect(status().isOk());
        String before = db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,id);
        var fresh = tree(retry(owner,id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewOf").value(id)).andExpect(jsonPath("$.qualityChecked").value(true))
                .andExpect(jsonPath("$.studentAnswer").doesNotExist()).andExpect(jsonPath("$.grade").doesNotExist())
                .andExpect(jsonPath("$.referenceAnswer").doesNotExist()).andExpect(jsonPath("$.citations").isEmpty()));
        String repeatId = fresh.path("id").asText();
        assertThat(repeatId).isNotEqualTo(id);
        setupModel();
        submit(owner,repeatId,"先初始化父类部分").andExpect(status().isOk()).andExpect(jsonPath("$.grade.score").value(100));
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                .andExpect(jsonPath("$.studentAnswer").value("先初始化子类")).andExpect(jsonPath("$.grade.score").value(0));
        assertThat(db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo(before);
        var history = tree(mvc.perform(get("/api/practice").header("Authorization",bearer(owner))).andExpect(status().isOk()));
        assertThat(history.findValuesAsText("reviewOf")).containsExactly(id);
        verify(model,times(1)).create(anyList(),anyList());
    }
    @Test void concurrentRetriesReuseOnePendingAttemptAndKeepTheSameRootAcrossRounds() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        submit(owner,id,"先初始化父类部分");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<String> request = () -> tree(retry(owner,id).andExpect(status().isCreated())).path("id").asText();
            var first = workers.submit(request); var second = workers.submit(request);
            String repeatId = first.get(15,TimeUnit.SECONDS);
            assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(repeatId);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=?",Integer.class,owner.user().id())).isEqualTo(2);
            submit(owner,repeatId,"先初始化父类部分");
            String nextId = tree(retry(owner,repeatId).andExpect(jsonPath("$.reviewOf").value(id))).path("id").asText();
            assertThat(tree(retry(owner,id)).path("id").asText()).isEqualTo(nextId);
            verify(model,times(1)).create(anyList(),anyList());
        } finally { workers.shutdownNow(); }
    }
    @Test void retryEnforcesOwnershipCompletionAndCurrentQualityVersion() throws Exception {
        var owner = account(); var other = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        retry(other,id).andExpect(status().isNotFound());
        retry(owner,id).andExpect(status().isConflict());
        mvc.perform(post("/api/practice/{id}/retry",id)).andExpect(status().isUnauthorized());
        submit(owner,id,"先初始化父类部分");
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,id));
        legacy.remove("qualityVersion");
        db.update("UPDATE kb_exercise SET solution_json=? WHERE id=?",json.writeValueAsString(legacy),id);
        retry(owner,id).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=?",Integer.class,owner.user().id())).isEqualTo(1);
    }
    @Test void retryUsesTheSourceSnapshotEvenAfterTheDocumentAndOriginalRecordAreDeleted() throws Exception {
        var owner = account(); String doc = note(owner,CONTENT); String id = tree(create(owner,doc)).path("id").asText();
        submit(owner,id,"先初始化父类部分");
        documents.delete(doc,owner.user().id());
        String repeatId = tree(retry(owner,id).andExpect(status().isCreated())).path("id").asText();
        mvc.perform(delete("/api/practice/{id}",id).header("Authorization",bearer(owner))).andExpect(status().isNoContent());
        submit(owner,repeatId,"先初始化父类部分").andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].content").value(CONTENT));
        retry(owner,repeatId).andExpect(status().isCreated()).andExpect(jsonPath("$.reviewOf").value(id));
    }
    @Test void retryReusesAnExistingPendingAttemptOutsideTheRecentHundredRecords() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        submit(owner,id,"先初始化父类部分");
        String repeatId = tree(retry(owner,id)).path("id").asText();
        for (int i=0;i<101;i++) {
            db.update("""
                INSERT INTO kb_exercise (id,user_id,anchor_chunk_id,question,solution_json,student_answer,grade_json,created_at,graded_at)
                SELECT ?,user_id,anchor_chunk_id,question,solution_json,student_answer,grade_json,?,graded_at FROM kb_exercise WHERE id=?
                """,UUID.randomUUID().toString(),LocalDateTime.now().plusMinutes(1),id);
        }
        var history = tree(mvc.perform(get("/api/practice").header("Authorization",bearer(owner))));
        assertThat(history.size()).isEqualTo(100);
        assertThat(history.findValuesAsText("id")).doesNotContain(repeatId);
        retry(owner,id).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(repeatId));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=? AND grade_json IS NULL",Integer.class,owner.user().id())).isEqualTo(1);
    }
    @Test void draftPersistsExactInputForReloadAndCanBeClearedWithoutPublishingTheSolution() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        String draft = "  先初始化父类\n还没写完 😀  ";
        draft(owner,id,draft,null).andExpect(status().isOk()).andExpect(jsonPath("$.studentAnswer").value(draft))
                .andExpect(jsonPath("$.grade").doesNotExist()).andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.citations").isEmpty()).andExpect(jsonPath("$.gradedAt").doesNotExist());
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                .andExpect(jsonPath("$.studentAnswer").value(draft));
        mvc.perform(get("/api/practice").header("Authorization",bearer(owner)))
                .andExpect(jsonPath("$[0].studentAnswer").value(draft)).andExpect(jsonPath("$[0].grade").doesNotExist());
        draft(owner,id,"",draft).andExpect(status().isOk()).andExpect(jsonPath("$.studentAnswer").value(""));
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_exercise WHERE user_id=?",Integer.class,owner.user().id())).isEqualTo(1);
        verify(model,never()).grade(any(),anyString());
    }
    @Test void draftsAreIsolatedByOwnerAndRequireAuthentication() throws Exception {
        var owner = account(); var other = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(other,id,"外部草稿",null).andExpect(status().isNotFound());
        mvc.perform(patch("/api/practice/{id}/draft",id).contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"草稿\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isNull();
    }
    @Test void rejectsMissingAndOversizedDraftFieldsBeforeSaving() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,null,null).andExpect(status().isBadRequest());
        draft(owner,id,"x".repeat(4001),null).andExpect(status().isBadRequest());
        draft(owner,id,"答案","x".repeat(4001)).andExpect(status().isBadRequest());
        draft(owner,id,"x".repeat(4000),null).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).hasSize(4000);
        verify(model,never()).grade(any(),anyString());
    }
    @Test void draftConflictChecksAreExactAndReplayedSavesAreIdempotent() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"Draft",null).andExpect(status().isOk());
        draft(owner,id,"较旧页面的内容","").andExpect(status().isConflict());
        draft(owner,id,"Draft","较旧页面记录").andExpect(status().isOk());
        draft(owner,id,"新内容","draft").andExpect(status().isConflict());
        draft(owner,id,"新内容","Draft ").andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo("Draft");
        draft(owner,id,"新内容","Draft").andExpect(status().isOk()).andExpect(jsonPath("$.studentAnswer").value("新内容"));
    }
    @Test void lateDraftsCannotOverwriteGradedOrLegacyExercisesAndRetriesStartWithoutAnOldDraft() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"还在思考",null).andExpect(status().isOk());
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk());
        String grade = db.queryForObject("SELECT grade_json FROM kb_exercise WHERE id=?",String.class,id);
        draft(owner,id,"迟到的草稿","还在思考").andExpect(status().isConflict());
        draft(owner,id,"先初始化父类部分","先初始化父类部分").andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo("先初始化父类部分");
        assertThat(db.queryForObject("SELECT grade_json FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo(grade);
        String retryId = tree(retry(owner,id).andExpect(jsonPath("$.studentAnswer").doesNotExist())).path("id").asText();
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT solution_json FROM kb_exercise WHERE id=?",String.class,retryId));
        legacy.remove("qualityVersion");
        db.update("UPDATE kb_exercise SET solution_json=? WHERE id=?",json.writeValueAsString(legacy),retryId);
        draft(owner,retryId,"旧版题草稿",null).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,retryId)).isNull();
    }
    @Test void concurrentDraftEditsCannotSilentlyReplaceEachOther() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> draft(owner,id,"第一页输入",null).andReturn().getResponse().getStatus());
            var second = workers.submit(() -> draft(owner,id,"第二页输入",null).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isIn("第一页输入","第二页输入");
        } finally { workers.shutdownNow(); }
    }
    @Test void failedGradingKeepsThePreviouslySavedDraftAvailableForReload() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"先初始化父类，继续思考",null).andExpect(status().isOk());
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY,"模型失败")).when(model).grade(any(),anyString());
        submit(owner,id,"先初始化父类，继续思考").andExpect(status().isBadGateway());
        mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                .andExpect(jsonPath("$.studentAnswer").value("先初始化父类，继续思考"))
                .andExpect(jsonPath("$.grade").doesNotExist()).andExpect(jsonPath("$.referenceAnswer").doesNotExist());
        setupModel();
        submit(owner,id,"先初始化父类部分").andExpect(status().isOk()).andExpect(jsonPath("$.grade.score").value(100));
    }
    @Test void gradingRejectsAStaleExpectedDraftBeforeCallingTheModelAndKeepsRepeatedSubmissionIdempotent() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"已保存的草稿",null).andExpect(status().isOk());
        submit(owner,id,"先初始化父类部分","x".repeat(4001)).andExpect(status().isBadRequest());
        submit(owner,id,"先初始化父类部分","").andExpect(status().isConflict());
        verify(model,never()).grade(any(),anyString());
        assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo("已保存的草稿");
        submit(owner,id,"先初始化父类部分","已保存的草稿").andExpect(status().isOk());
        submit(owner,id,"先初始化父类部分","不再匹配的旧草稿").andExpect(status().isOk());
        verify(model,times(1)).grade(any(),anyString());
    }
    @Test void aDraftSavedDuringInferenceSurvivesAndPreventsTheOldGradeFromBeingStored() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"初稿",null).andExpect(status().isOk());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Grading latch timed out"); }
            return new PracticeModel.Grade(100,"本题已掌握",List.of(new PracticeModel.Check(1,"CORRECT","顺序正确","先初始化父类部分")));
        }).when(model).grade(any(),anyString());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var grading = workers.submit(() -> submit(owner,id,"先初始化父类部分","初稿").andReturn().getResponse().getStatus());
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var editing = workers.submit(() -> draft(owner,id,"另一页刚写下的新内容","初稿").andReturn().getResponse().getStatus());
            assertThat(editing.get(5,TimeUnit.SECONDS)).isEqualTo(200);
            release.countDown();
            assertThat(grading.get(10,TimeUnit.SECONDS)).isEqualTo(409);
            mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                    .andExpect(jsonPath("$.studentAnswer").value("另一页刚写下的新内容"))
                    .andExpect(jsonPath("$.grade").doesNotExist()).andExpect(jsonPath("$.referenceAnswer").doesNotExist());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void gradingCanFinishWhenTheInterleavedDraftAlreadyEqualsTheSubmittedFinalAnswer() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        draft(owner,id,"初稿",null).andExpect(status().isOk());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Grading latch timed out"); }
            return new PracticeModel.Grade(100,"本题已掌握",List.of(new PracticeModel.Check(1,"CORRECT","顺序正确","先初始化父类部分")));
        }).when(model).grade(any(),anyString());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var grading = workers.submit(() -> submit(owner,id,"先初始化父类部分","初稿").andReturn().getResponse().getStatus());
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var editing = workers.submit(() -> draft(owner,id,"先初始化父类部分","初稿").andReturn().getResponse().getStatus());
            assertThat(editing.get(5,TimeUnit.SECONDS)).isEqualTo(200);
            release.countDown();
            assertThat(grading.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            mvc.perform(get("/api/practice/{id}",id).header("Authorization",bearer(owner)))
                    .andExpect(jsonPath("$.studentAnswer").value("先初始化父类部分")).andExpect(jsonPath("$.grade.score").value(100));
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void overlappingIdenticalGradingRequestsReuseTheFirstStoredResultAfterInference() throws Exception {
        var owner = account(); String id = tree(create(owner,note(owner,CONTENT))).path("id").asText();
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Grading latch timed out"); }
            return new PracticeModel.Grade(100,"本题已掌握",List.of(new PracticeModel.Check(1,"CORRECT","顺序正确","先初始化父类部分")));
        }).when(model).grade(any(),anyString());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> submit(owner,id,"先初始化父类部分","").andReturn().getResponse().getStatus();
            var first = workers.submit(request); var second = workers.submit(request);
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(db.queryForObject("SELECT student_answer FROM kb_exercise WHERE id=?",String.class,id)).isEqualTo("先初始化父类部分");
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    AuthService.AuthResult account() { return auth.register("quiz_"+UUID.randomUUID().toString().replace("-","").substring(0,15),"练习验收","practice-fixture-123"); }
    String note(AuthService.AuthResult account,String text) {
        String id=UUID.randomUUID().toString();
        documents.insert(new Rows.Document(id,account.user().id(),"继承笔记.md","md",text.length(),UUID.randomUUID().toString(),1,1,LocalDateTime.now()));
        documents.insertChunks(List.of(new Rows.Chunk(UUID.randomUUID().toString(),id,account.user().id(),1,1,0,text)));
        return id;
    }
    ResultActions create(AuthService.AuthResult owner,String... ids) throws Exception {
        return mvc.perform(post("/api/practice").header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("documentIds",ids))));
    }
    ResultActions submit(AuthService.AuthResult owner,String id,String text) throws Exception {
        return submit(owner,id,text,null);
    }
    ResultActions submit(AuthService.AuthResult owner,String id,String text,String expectedAnswer) throws Exception {
        Map<String,String> request = new LinkedHashMap<>(); request.put("answer",text);
        if (expectedAnswer != null) { request.put("expectedAnswer",expectedAnswer); }
        return mvc.perform(post("/api/practice/{id}/answer",id).header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)));
    }
    ResultActions retry(AuthService.AuthResult owner,String id) throws Exception {
        return mvc.perform(post("/api/practice/{id}/retry",id).header("Authorization",bearer(owner)));
    }
    ResultActions draft(AuthService.AuthResult owner,String id,String answer,String expectedAnswer) throws Exception {
        Map<String,String> request = new LinkedHashMap<>(); request.put("answer",answer); request.put("expectedAnswer",expectedAnswer);
        return mvc.perform(patch("/api/practice/{id}/draft",id).header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)));
    }
    String bearer(AuthService.AuthResult account) { return "Bearer "+account.token(); }
    JsonNode tree(ResultActions action) throws Exception { return json.readTree(action.andReturn().getResponse().getContentAsByteArray()); }
}
