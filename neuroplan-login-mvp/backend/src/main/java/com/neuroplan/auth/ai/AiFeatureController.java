package com.neuroplan.auth.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuroplan.auth.ai.AiGenerationService.FeedbackGeneration;
import com.neuroplan.auth.ai.AiGenerationService.PlanGeneration;
import com.neuroplan.auth.ai.AiGenerationService.QuizCheckResult;
import com.neuroplan.auth.ai.AiGenerationService.QuizGeneration;
import com.neuroplan.auth.ai.AiGenerationService.QuizOptionContent;
import com.neuroplan.auth.ai.AiGenerationService.QuizQuestionContent;
import com.neuroplan.auth.ai.AiGenerationService.RecommendationContext;
import com.neuroplan.auth.ai.AiGenerationService.RecommendationGeneration;
import com.neuroplan.auth.ai.AiGenerationService.ShortAnswerEvaluationGeneration;
import com.neuroplan.auth.ai.AiGenerationService.ShortAnswerGeneration;
import com.neuroplan.auth.ai.AiGenerationService.ShortAnswerQuestionContent;
import com.neuroplan.auth.ai.AiGenerationService.WrongNoteContext;
import com.neuroplan.auth.ai.AiQuotaService.AiQuotaResponse;
import com.neuroplan.auth.auth.CurrentUserService;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.user.UserRecord;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
@ResponseBody
@RequestMapping("/api/ai")
public class AiFeatureController {
    private static final Logger log = LoggerFactory.getLogger(AiFeatureController.class);
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final AiQuotaService quotaService;
    private final AiPreferencesService preferencesService;
    private final AiGenerationService generationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean demoWriteFenceEnabled;

    public AiFeatureController(JdbcTemplate jdbcTemplate, CurrentUserService currentUserService,
                               AiQuotaService quotaService, AiPreferencesService preferencesService,
                               AiGenerationService generationService,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               @org.springframework.beans.factory.annotation.Value("${app.demo-write-fence.enabled:false}") boolean demoWriteFenceEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.quotaService = quotaService;
        this.preferencesService = preferencesService;
        this.generationService = generationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.demoWriteFenceEnabled = demoWriteFenceEnabled;
    }

    @GetMapping("/quota")
    public AiQuotaResponse quota(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return quotaService.status(user.id());
    }

    @GetMapping("/preferences")
    public AiPreferencesService.AiPreferenceResponse preferences(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return preferencesService.status(user.id());
    }

    @PutMapping("/preferences")
    public AiPreferencesService.AiPreferenceResponse updatePreferences(
            @RequestBody AiPreferenceRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        return preferencesService.update(user.id(), body.enabled(), body.consent(),
                body.explanationStyle(), body.availableMinutes());
    }

    @PostMapping("/plans")
    public AiPlanResponse generatePlan(@RequestParam String subjectCode,
                                       @RequestBody(required = false) AiPromptRequest body,
                                       HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        PlanGeneration generated = generationService.generatePlan(
                user.id(), focus.subjectId(), focus.subjectName(), focus.levelLabel(),
                generationPrompt(focus.focusTopic(), body == null ? null : body.additionalPrompt()));
        long planId = persistAiResult(user.id(), generated.generationRunId(), "AI 플랜 저장 실패 환불",
                () -> savePlan(user.id(), focus, generated));
        return new AiPlanResponse(plan(planId, user.id()), generated.generationRunId(),
                generated.fallback(), generated.content().rationale(), generated.quota());
    }

    @PostMapping("/questions")
    public AiQuizResponse generateQuestions(
            @RequestParam String subjectCode,
            @RequestBody(required = false) AiPromptRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        String questionType = normalizeQuestionType(body == null ? null : body.questionType());
        if ("SHORT_ANSWER".equals(questionType)) {
            ShortAnswerGeneration generated = generationService.generateShortAnswerQuiz(
                    user.id(), focus.subjectId(), focus.subjectCode(), focus.subjectName(), focus.levelLabel(),
                    generationPrompt(focus.focusTopic(), body == null ? null : body.additionalPrompt()));
            List<AiQuizQuestionResponse> questions = persistAiResult(
                    user.id(), generated.generationRunId(), "AI 주관식 문제 저장 실패 환불",
                    () -> saveShortAnswerQuestions(focus, generated));
            return new AiQuizResponse(generated.generationRunId(), false, questions, generated.quota());
        }
        QuizGeneration generated = generationService.generateQuiz(
                user.id(), focus.subjectId(), focus.subjectCode(), focus.subjectName(), focus.levelLabel(),
                generationPrompt(focus.focusTopic(), body == null ? null : body.additionalPrompt()));
        // AI 문제에도 실제 문제/보기 ID를 부여한다. 따라서 풀이 결과를 기존
        // diagnosis_attempts와 wrong_notes 흐름으로 그대로 기록할 수 있다.
        List<AiQuizQuestionResponse> questions = persistAiResult(
                user.id(), generated.generationRunId(), "AI 문제 저장 실패 환불",
                () -> saveQuizQuestions(focus, generated));
        return new AiQuizResponse(generated.generationRunId(), generated.fallback(), questions, generated.quota());
    }

    /** A disabled-by-default, one-subject-per-run problem-bank replenisher. */
    @Scheduled(fixedDelayString = "${app.ai.problem-bank-refill-interval-ms:300000}")
    public void replenishProblemBank() {
        if (demoWriteFenceEnabled) {
            log.info("AI problem bank replenishment skipped: DEMO_WRITE_FENCE is enabled");
            return;
        }
        try {
            List<ProblemBankSetting> settings = jdbcTemplate.query("""
                    SELECT owner_user_id, target_count
                      FROM ai_problem_bank_settings
                     WHERE id = 1 AND is_enabled = TRUE AND owner_user_id IS NOT NULL
                    """, (rs, rowNum) -> new ProblemBankSetting(
                    rs.getLong("owner_user_id"), rs.getInt("target_count")));
            if (settings.isEmpty()) return;
            ProblemBankSetting setting = settings.getFirst();
            List<ProfileSubject> subjects = jdbcTemplate.query("""
                    SELECT us.subject_id, s.code, s.name, us.learning_level, us.focus_topic
                      FROM user_subjects us JOIN subjects s ON s.id = us.subject_id
                     WHERE us.user_id = ? AND s.is_active = TRUE ORDER BY us.slot_no
                    """, (rs, rowNum) -> new ProfileSubject(
                    rs.getLong("subject_id"), rs.getString("code"), rs.getString("name"),
                    certificationStageLabel(rs.getString("code"), levelLabel(rs.getString("learning_level"))),
                    rs.getString("focus_topic")), setting.ownerUserId());
            for (ProfileSubject subject : subjects) {
                Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM diagnosis_questions WHERE subject_id = ? AND is_active = TRUE
                        """, Integer.class, subject.subjectId());
                if (count != null && count >= setting.targetCount()) continue;
                QuizGeneration generated = generationService.generateQuiz(
                        setting.ownerUserId(), subject.subjectId(), subject.subjectCode(), subject.subjectName(), subject.levelLabel(),
                        generationPrompt(subject.focusTopic(), null));
                persistAiResult(setting.ownerUserId(), generated.generationRunId(), "문제은행 자동 보충 저장 실패 환불",
                        () -> saveQuizQuestions(subject, generated));
                jdbcTemplate.update("""
                        UPDATE ai_problem_bank_settings
                           SET last_run_at = CURRENT_TIMESTAMP(6), last_error = NULL, updated_at = CURRENT_TIMESTAMP(6)
                         WHERE id = 1
                        """);
                log.info("AI problem bank replenished: subjectId={}, runId={}", subject.subjectId(), generated.generationRunId());
                return;
            }
        } catch (RuntimeException error) {
            log.warn("AI problem bank replenishment skipped: {}", error.getMessage());
            try {
                jdbcTemplate.update("""
                        UPDATE ai_problem_bank_settings SET last_error = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = 1
                        """, error.getMessage() == null ? "자동 보충 중 알 수 없는 오류" : error.getMessage());
            } catch (RuntimeException ignored) {
                log.debug("Unable to store problem bank replenishment error", ignored);
            }
        }
    }

    @PostMapping("/questions/{runId}/check")
    public QuizCheckResult checkQuestion(
            @PathVariable long runId,
            @RequestBody AiQuizCheckRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        return generationService.checkQuiz(user.id(), runId, body.questionNo(), body.selectedOptionNo());
    }

    @PostMapping("/questions/{runId}/short-answer/check")
    public AiShortAnswerCheckResponse checkShortAnswer(
            @PathVariable long runId,
            @RequestBody AiShortAnswerCheckRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        RunSubject run = jdbcTemplate.query("""
                SELECT r.subject_id, s.code, s.name
                  FROM ai_generation_runs r
                  JOIN subjects s ON s.id = r.subject_id
                 WHERE r.id = ? AND r.user_id = ? AND r.request_type = 'QUESTION_DRAFT'
                """, (rs, rowNum) -> new RunSubject(
                rs.getLong("subject_id"), rs.getString("code"), rs.getString("name")
        ), runId, user.id()).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AI 주관식 문제 세트를 찾을 수 없습니다."
        ));
        if (!"INFORMATION_PROCESSING_PRACTICAL".equals(run.subjectCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "정보처리기사 실기 주관식 문제만 AI 검토를 지원합니다.");
        }
        ShortAnswerStoredQuestion question = jdbcTemplate.query("""
                SELECT id, question_text, reference_answer, accepted_answers_json, grading_rubric
                  FROM diagnosis_questions
                 WHERE id = ? AND subject_id = ? AND question_type = 'SHORT_ANSWER' AND is_active = TRUE
                """, (rs, rowNum) -> new ShortAnswerStoredQuestion(
                rs.getLong("id"), rs.getString("question_text"), rs.getString("reference_answer"),
                rs.getString("accepted_answers_json"), rs.getString("grading_rubric")
        ), body.questionId(), run.subjectId()).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "AI 주관식 문제와 답안이 일치하지 않습니다."
        ));
        ShortAnswerEvaluationGeneration generated = generationService.evaluateShortAnswer(
                user.id(), run.subjectId(), run.subjectName(), question.questionText(), question.referenceAnswer(),
                question.acceptedAnswersJson(), question.gradingRubric(), body.answerText());
        transactionTemplate.executeWithoutResult(status -> persistShortAnswerResult(
                user.id(), run.subjectId(), question.id(), body.answerText(), generated));
        return new AiShortAnswerCheckResponse(
                generated.evaluation().correct(), generated.evaluation().score(), generated.evaluation().feedback(),
                generated.evaluation().modelAnswer(), generated.generationRunId(), generated.quota()
        );
    }

    @PostMapping("/wrong-notes/{questionId}/feedback")
    public AiFeedbackResponse generateWrongFeedback(@PathVariable long questionId, HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        WrongNoteContext context = wrongNoteContext(user.id(), questionId);
        FeedbackGeneration generated = generationService.generateWrongFeedback(user.id(), context);
        long feedbackId = persistAiResult(user.id(), generated.generationRunId(), "AI 오답 해설 저장 실패 환불",
                () -> insertFeedback(user.id(), questionId, generated));
        return new AiFeedbackResponse(feedbackId, generated.generationRunId(), generated.fallback(),
                generated.content().feedback(), generated.content().recommendedActions(), generated.quota());
    }

    @GetMapping("/wrong-notes/feedback")
    public List<AiFeedbackSummary> feedback(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return jdbcTemplate.query("""
                SELECT id, question_id, generation_run_id, feedback_text,
                       recommended_action_json, created_at
                  FROM wrong_note_ai_feedback
                 WHERE user_id = ? AND feedback_status = 'ACTIVE'
                 ORDER BY created_at DESC, id DESC
                """, (rs, rowNum) -> new AiFeedbackSummary(
                rs.getLong("id"), rs.getLong("question_id"), rs.getLong("generation_run_id"),
                rs.getString("feedback_text"), readStringList(rs.getString("recommended_action_json")),
                rs.getTimestamp("created_at").toInstant()
        ), user.id());
    }

    @PostMapping("/recommendations")
    public AiRecommendationResponse generateRecommendation(
            @RequestParam String subjectCode,
            @RequestBody(required = false) AiPromptRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        String summary = learningSummary(user.id(), focus.subjectId());
        RecommendationGeneration generated = generationService.generateRecommendation(
                user.id(), new RecommendationContext(
                        focus.subjectId(), focus.subjectName(), focus.levelLabel(), summary),
                generationPrompt(focus.focusTopic(), body == null ? null : body.additionalPrompt()));
        long queueId = persistAiResult(user.id(), generated.generationRunId(), "AI 재학습 추천 저장 실패 환불",
                () -> insertRecommendation(user.id(), focus.subjectId(), generated));
        return new AiRecommendationResponse(queueId, generated.generationRunId(), generated.fallback(),
                generated.content().title(), generated.content().content(), generated.content().priority(),
                generated.quota());
    }

    @GetMapping("/recommendations")
    public List<AiRecommendationSummary> recommendations(
            @RequestParam(required = false) String subjectCode,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        String code = subjectCode == null ? null : subjectCode.trim().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                SELECT q.id, q.subject_id, s.code, s.name, q.title, q.content,
                       q.priority, q.created_at
                  FROM next_plan_queue q
                  JOIN subjects s ON s.id = q.subject_id
                 WHERE q.user_id = ? AND q.queue_status = 'PENDING'
                   AND (? IS NULL OR s.code = ?)
                 ORDER BY q.priority DESC, q.created_at DESC
                 LIMIT 20
                """, (rs, rowNum) -> new AiRecommendationSummary(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("title"), rs.getString("content"),
                rs.getInt("priority"), rs.getTimestamp("created_at").toInstant()
        ), user.id(), code, code);
    }

    private ProfileSubject profileSubject(long userId, String subjectCode) {
        String code = subjectCode == null ? "" : subjectCode.trim().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                SELECT us.subject_id, s.code, s.name, us.learning_level, us.focus_topic
                  FROM user_subjects us
                  JOIN subjects s ON s.id = us.subject_id
                 WHERE us.user_id = ? AND s.code = ? AND s.is_active = TRUE
                """, (rs, rowNum) -> new ProfileSubject(
                rs.getLong("subject_id"), rs.getString("code"), rs.getString("name"),
                certificationStageLabel(rs.getString("code"), levelLabel(rs.getString("learning_level"))),
                rs.getString("focus_topic")
        ), userId, code).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "학습 프로필에 선택한 과목이 없습니다: " + code
        ));
    }

    private long savePlan(long userId, ProfileSubject focus, PlanGeneration generated) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO daily_plans (
                        user_id, subject_id, plan_date, title, plan_status, created_at, updated_at
                    ) VALUES (?, ?, CURRENT_DATE, ?, 'READY', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, focus.subjectId());
            statement.setString(3, generated.content().title());
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("플랜 번호를 생성하지 못했습니다.");
        long planId = keys.getKey().longValue();
        for (var step : generated.content().steps()) {
            jdbcTemplate.update("""
                    INSERT INTO plan_steps (
                        plan_id, step_no, title, content, step_status, completed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, planId, step.stepNo(), step.title(), step.content());
        }
        jdbcTemplate.update("DELETE FROM daily_plan_ai_meta WHERE plan_id = ?", planId);
        jdbcTemplate.update("""
                INSERT INTO daily_plan_ai_meta (
                    plan_id, generation_run_id, rationale, criteria_json, generated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, planId, generated.generationRunId(), generated.content().rationale(),
                criteriaJson(focus, generated.fallback()));
        jdbcTemplate.update("""
                INSERT INTO user_active_plans (user_id, subject_id, plan_id, selected_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE plan_id = VALUES(plan_id), selected_at = VALUES(selected_at)
                """, userId, focus.subjectId(), planId);
        return planId;
    }

    private <T> T persistAiResult(long userId, long generationRunId, String refundReason,
                                  Supplier<T> persistence) {
        try {
            T result = transactionTemplate.execute(status -> persistence.get());
            if (result == null) throw new IllegalStateException("AI 결과를 저장하지 못했습니다.");
            return result;
        } catch (RuntimeException persistenceFailure) {
            log.error("AI persistence failed: runId={}, userId={}, reason={}",
                    generationRunId, userId, refundReason, persistenceFailure);
            try {
                if (persistenceFailure instanceof DuplicateQuestionException duplicate) {
                    generationService.markFailure(generationRunId, "DUPLICATE_QUESTION", duplicate.getMessage());
                } else {
                    generationService.markPersistenceFailure(generationRunId, refundReason);
                }
            } catch (RuntimeException statusFailure) {
                persistenceFailure.addSuppressed(statusFailure);
            }
            try {
                quotaService.refundUsage(userId, generationRunId, refundReason);
            } catch (RuntimeException refundFailure) {
                persistenceFailure.addSuppressed(refundFailure);
            }
            if (persistenceFailure instanceof DuplicateQuestionException duplicate) {
                throw new ApiException(HttpStatus.CONFLICT, duplicate.getMessage());
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PERSISTENCE_FAILED: AI 결과를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요. (실행 번호: "
                            + generationRunId + ")");
        }
    }

    private List<AiQuizQuestionResponse> saveQuizQuestions(ProfileSubject focus, QuizGeneration generated) {
        Integer highestQuestionNo = jdbcTemplate.query("""
                SELECT question_no
                  FROM diagnosis_questions
                 WHERE subject_id = ?
                 ORDER BY question_no DESC
                 LIMIT 1
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getInt("question_no"), focus.subjectId())
                .stream().findFirst().orElse(0);
        int[] nextQuestionNo = { highestQuestionNo == null ? 1 : highestQuestionNo + 1 };
        Set<String> knownHashes = existingQuestionHashes(focus.subjectId());
        Set<String> generatedTexts = new HashSet<>();
        List<QuizQuestionContent> uniqueQuestions = new ArrayList<>();
        for (QuizQuestionContent question : generated.content().questions()) {
            String hash = questionContentHash(question);
            if (!knownHashes.add(hash) || !generatedTexts.add(normalizeHashText(question.text()))) continue;
            uniqueQuestions.add(question);
        }
        if (uniqueQuestions.size() != generated.content().questions().size()) {
            throw new DuplicateQuestionException(
                    "DUPLICATE_QUESTION: 기존 문제와 중복된 AI 문제가 포함되었습니다. 다시 시도해 주세요.");
        }
        return uniqueQuestions.stream().map(question -> {
            String contentHash = questionContentHash(question);
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO diagnosis_questions (
                            subject_id, question_no, difficulty, question_text, content_hash, explanation,
                            is_active, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, focus.subjectId());
                statement.setInt(2, nextQuestionNo[0]++);
                statement.setString(3, difficultyCode(question.difficulty()));
                statement.setString(4, question.text());
                statement.setString(5, contentHash);
                statement.setString(6, question.explanation());
                return statement;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("AI 문제 번호를 생성하지 못했습니다.");
            long questionId = keys.getKey().longValue();
            List<AiQuizOptionResponse> options = question.options().stream().map(option -> {
                GeneratedKeyHolder optionKeys = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO question_options (question_id, option_no, option_text, is_correct)
                            VALUES (?, ?, ?, ?)
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, questionId);
                    statement.setInt(2, option.optionNo());
                    statement.setString(3, option.text());
                    statement.setBoolean(4, option.correct());
                    return statement;
                }, optionKeys);
                if (optionKeys.getKey() == null) throw new IllegalStateException("AI 보기 번호를 생성하지 못했습니다.");
                return new AiQuizOptionResponse(optionKeys.getKey().longValue(), option.optionNo(), option.text());
            }).toList();
            return new AiQuizQuestionResponse(questionId, question.questionNo(), question.subjectName(),
                    question.difficulty(), "MULTIPLE_CHOICE", question.text(), options);
        }).toList();
    }

    private List<AiQuizQuestionResponse> saveShortAnswerQuestions(ProfileSubject focus, ShortAnswerGeneration generated) {
        Integer highestQuestionNo = jdbcTemplate.query("""
                SELECT question_no FROM diagnosis_questions WHERE subject_id = ?
                 ORDER BY question_no DESC LIMIT 1 FOR UPDATE
                """, (rs, rowNum) -> rs.getInt("question_no"), focus.subjectId())
                .stream().findFirst().orElse(0);
        int[] nextQuestionNo = { highestQuestionNo == null ? 1 : highestQuestionNo + 1 };
        Set<String> knownHashes = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT content_hash FROM diagnosis_questions
                 WHERE subject_id = ? AND content_hash IS NOT NULL
                """, String.class, focus.subjectId()));
        Set<String> generatedTexts = new HashSet<>();
        List<ShortAnswerQuestionContent> uniqueQuestions = new ArrayList<>();
        for (ShortAnswerQuestionContent question : generated.content().questions()) {
            String hash = shortAnswerContentHash(question);
            if (!knownHashes.add(hash) || !generatedTexts.add(normalizeHashText(question.text()))) continue;
            uniqueQuestions.add(question);
        }
        if (uniqueQuestions.size() != generated.content().questions().size()) {
            throw new DuplicateQuestionException(
                    "DUPLICATE_QUESTION: 기존 문제와 중복된 AI 주관식 문제가 포함되었습니다. 다시 시도해 주세요.");
        }
        return uniqueQuestions.stream().map(question -> {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO diagnosis_questions (
                            subject_id, question_no, difficulty, question_type, question_text, content_hash,
                            explanation, reference_answer, accepted_answers_json, grading_rubric,
                            is_active, created_at, updated_at
                        ) VALUES (?, ?, ?, 'SHORT_ANSWER', ?, ?, ?, ?, ?, ?, TRUE,
                                  CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, focus.subjectId());
                statement.setInt(2, nextQuestionNo[0]++);
                statement.setString(3, difficultyCode(question.difficulty()));
                statement.setString(4, question.text());
                statement.setString(5, shortAnswerContentHash(question));
                statement.setString(6, question.explanation());
                statement.setString(7, question.referenceAnswer());
                statement.setString(8, writeJson(question.acceptedAnswers()));
                statement.setString(9, question.gradingRubric());
                return statement;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("AI 주관식 문제 번호를 생성하지 못했습니다.");
            long questionId = keys.getKey().longValue();
            return new AiQuizQuestionResponse(questionId, question.questionNo(), question.subjectName(),
                    question.difficulty(), "SHORT_ANSWER", question.text(), List.of());
        }).toList();
    }

    private void persistShortAnswerResult(long userId, long subjectId, long questionId, String answerText,
                                          ShortAnswerEvaluationGeneration generated) {
        LocalDateTime now = LocalDateTime.now();
        long attemptId = insertAndReturnId("""
                INSERT INTO diagnosis_attempts (
                    user_id, subject_id, attempt_type, attempt_status,
                    total_questions, correct_answers, started_at, completed_at
                ) VALUES (?, ?, 'DIAGNOSTIC', 'COMPLETED', 1, ?, ?, ?)
                """, userId, subjectId, generated.evaluation().correct() ? 1 : 0,
                Timestamp.valueOf(now.minusSeconds(1)), Timestamp.valueOf(now));
        jdbcTemplate.update("""
                INSERT INTO diagnosis_answers (
                    attempt_id, question_id, selected_option_id, answer_text, is_correct,
                    evaluation_detail, evaluated_by, evaluated_at, answered_at
                ) VALUES (?, ?, NULL, ?, ?, ?, 'AI', ?, ?)
                """, attemptId, questionId, answerText.trim(), generated.evaluation().correct(),
                writeJson(generated.evaluation()), Timestamp.valueOf(now), Timestamp.valueOf(now));
        if (!generated.evaluation().correct()) {
            jdbcTemplate.update("""
                    INSERT INTO wrong_notes (
                        user_id, question_id, last_attempt_id, wrong_count,
                        is_relearned, first_wrong_at, last_wrong_at, relearned_at
                    ) VALUES (?, ?, ?, 1, FALSE, ?, ?, NULL)
                    ON DUPLICATE KEY UPDATE
                        last_attempt_id = VALUES(last_attempt_id), wrong_count = wrong_count + 1,
                        is_relearned = FALSE, last_wrong_at = VALUES(last_wrong_at), relearned_at = NULL
                    """, userId, questionId, attemptId, Timestamp.valueOf(now), Timestamp.valueOf(now));
        }
        jdbcTemplate.update("""
                INSERT INTO study_daily_stats (
                    user_id, study_date, solved_count, correct_count, completed_step_count, updated_at
                ) VALUES (?, CURRENT_DATE, 1, ?, 0, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    solved_count = solved_count + 1,
                    correct_count = correct_count + VALUES(correct_count),
                    updated_at = CURRENT_TIMESTAMP(6)
                """, userId, generated.evaluation().correct() ? 1 : 0);
    }

    private Set<String> existingQuestionHashes(long subjectId) {
        List<StoredQuestionOption> rows = jdbcTemplate.query("""
                SELECT q.id, q.difficulty, q.question_text,
                       o.option_no, o.option_text, o.is_correct
                  FROM diagnosis_questions q
                  JOIN question_options o ON o.question_id = q.id
                 WHERE q.subject_id = ?
                 ORDER BY q.id, o.option_no
                """, (rs, rowNum) -> new StoredQuestionOption(
                rs.getLong("id"), rs.getString("difficulty"), rs.getString("question_text"),
                rs.getInt("option_no"), rs.getString("option_text"), rs.getBoolean("is_correct")
        ), subjectId);
        Map<Long, List<StoredQuestionOption>> optionsByQuestion = new LinkedHashMap<>();
        for (StoredQuestionOption row : rows) {
            optionsByQuestion.computeIfAbsent(row.questionId(), ignored -> new ArrayList<>()).add(row);
        }
        Set<String> hashes = new HashSet<>();
        for (List<StoredQuestionOption> options : optionsByQuestion.values()) {
            if (options.isEmpty()) continue;
            StoredQuestionOption first = options.get(0);
            List<QuizOptionContent> quizOptions = options.stream()
                    .map(option -> new QuizOptionContent(option.optionNo(), option.optionText(), option.correct()))
                    .toList();
            hashes.add(questionContentHash(first.difficulty(), first.questionText(), quizOptions));
        }
        return hashes;
    }

    private String questionContentHash(QuizQuestionContent question) {
        return questionContentHash(difficultyCode(question.difficulty()), question.text(), question.options());
    }

    private String shortAnswerContentHash(ShortAnswerQuestionContent question) {
        String source = difficultyCode(question.difficulty()) + "\n" + question.text().trim() + "\n"
                + String.join("\n", question.acceptedAnswers()).trim();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("AI 주관식 문제 해시를 생성하지 못했습니다.", exception);
        }
    }

    private String questionContentHash(String difficulty, String questionText, List<QuizOptionContent> options) {
        String optionText = options.stream()
                .sorted(Comparator.comparingInt(QuizOptionContent::optionNo))
                .map(option -> option.optionNo() + ":" + normalizeHashText(option.text())
                        + ":" + (option.correct() ? "1" : "0"))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String source = normalizeHashText(difficulty) + "|" + normalizeHashText(questionText) + "|" + optionText;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("AI 문제 중복 확인용 해시를 만들지 못했습니다.", exception);
        }
    }

    private String normalizeHashText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String difficultyCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("ADVANCED") || normalized.contains("고급")) return "ADVANCED";
        if (normalized.contains("INTERMEDIATE") || normalized.contains("중급")) return "INTERMEDIATE";
        return "BEGINNER";
    }

    private PlanResponse plan(long planId, long userId) {
        PlanHeader header = jdbcTemplate.query("""
                SELECT dp.id, dp.title, dp.plan_status, dp.subject_id, s.code, s.name
                  FROM daily_plans dp JOIN subjects s ON s.id = dp.subject_id
                 WHERE dp.id = ? AND dp.user_id = ?
                """, (rs, rowNum) -> new PlanHeader(
                rs.getLong("id"), rs.getString("title"), rs.getString("plan_status"),
                rs.getLong("subject_id"), rs.getString("code"), rs.getString("name")
        ), planId, userId).stream().findFirst().orElseThrow();
        List<PlanStepResponse> steps = jdbcTemplate.query("""
                SELECT id, step_no, title, content, step_status
                  FROM plan_steps WHERE plan_id = ? ORDER BY step_no
                """, (rs, rowNum) -> new PlanStepResponse(
                rs.getLong("id"), rs.getInt("step_no"), rs.getString("title"),
                rs.getString("content"), rs.getString("step_status")
        ), planId);
        return new PlanResponse(header.id(), header.title(), header.status(), header.subjectId(),
                header.subjectCode(), header.subjectName(), steps);
    }

    private WrongNoteContext wrongNoteContext(long userId, long questionId) {
        return jdbcTemplate.query("""
                SELECT wn.question_id, q.subject_id, s.name AS subject_name, q.question_text, q.explanation,
                       COALESCE(da.answer_text, selected.option_text, '기록 없음') AS selected_answer,
                       COALESCE(q.reference_answer, correct.option_text, '정답 정보 없음') AS correct_answer
                  FROM wrong_notes wn
                  JOIN diagnosis_questions q ON q.id = wn.question_id
                  JOIN subjects s ON s.id = q.subject_id
             LEFT JOIN diagnosis_answers da
                    ON da.attempt_id = wn.last_attempt_id AND da.question_id = wn.question_id
             LEFT JOIN question_options selected ON selected.id = da.selected_option_id
             LEFT JOIN question_options correct ON correct.question_id = q.id AND correct.is_correct = TRUE
                 WHERE wn.user_id = ? AND wn.question_id = ?
                """, (rs, rowNum) -> new WrongNoteContext(
                rs.getLong("question_id"), rs.getLong("subject_id"), rs.getString("subject_name"),
                rs.getString("question_text"), rs.getString("selected_answer"),
                rs.getString("correct_answer"), rs.getString("explanation")
        ), userId, questionId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AI 해설을 생성할 오답 노트를 찾을 수 없습니다."
        ));
    }

    private long insertFeedback(long userId, long questionId, FeedbackGeneration generated) {
        jdbcTemplate.update("""
                UPDATE wrong_note_ai_feedback
                   SET feedback_status = 'SUPERSEDED'
                 WHERE user_id = ? AND question_id = ? AND feedback_status = 'ACTIVE'
                """, userId, questionId);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wrong_note_ai_feedback (
                        user_id, question_id, generation_run_id, feedback_text,
                        recommended_action_json, feedback_status, created_at, applied_at
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, questionId);
            statement.setLong(3, generated.generationRunId());
            statement.setString(4, generated.content().feedback());
            statement.setString(5, writeJson(generated.content().recommendedActions()));
            return statement;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private String learningSummary(long userId, long subjectId) {
        return jdbcTemplate.query("""
                SELECT COALESCE(SUM(da.total_questions), 0) AS solved,
                       COALESCE(SUM(da.correct_answers), 0) AS correct,
                       (SELECT COUNT(*) FROM wrong_notes wn
                         JOIN diagnosis_questions q ON q.id = wn.question_id
                        WHERE wn.user_id = ? AND q.subject_id = ? AND wn.is_relearned = FALSE) AS pending_wrong,
                       (SELECT COUNT(*) FROM plan_steps ps
                         JOIN daily_plans dp ON dp.id = ps.plan_id
                        WHERE dp.user_id = ? AND dp.subject_id = ? AND ps.step_status = 'COMPLETED') AS completed_steps
                  FROM diagnosis_attempts da
                 WHERE da.user_id = ? AND da.subject_id = ? AND da.attempt_status = 'COMPLETED'
                """, (rs, rowNum) -> "풀이 %d문제, 정답 %d문제, 미해결 오답 %d개, 완료 단계 %d개"
                .formatted(rs.getInt("solved"), rs.getInt("correct"),
                        rs.getInt("pending_wrong"), rs.getInt("completed_steps")),
                userId, subjectId, userId, subjectId, userId, subjectId)
                .stream().findFirst().orElse("학습 기록 없음");
    }

    private long insertRecommendation(long userId, long subjectId, RecommendationGeneration generated) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO next_plan_queue (
                        user_id, subject_id, source_type, source_id, title, content,
                        priority, queue_status, applied_plan_id, created_at, applied_at
                    ) VALUES (?, ?, 'AI_RECOMMENDATION', ?, ?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP(6), NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, subjectId);
            statement.setLong(3, generated.generationRunId());
            statement.setString(4, generated.content().title());
            statement.setString(5, generated.content().content());
            statement.setInt(6, generated.content().priority());
            return statement;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private String criteriaJson(ProfileSubject focus, boolean fallback) {
        return writeJson(java.util.Map.of(
                "subjectCode", focus.subjectCode(), "level", focus.levelLabel(), "fallback", fallback));
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("AI 결과 JSON을 저장하지 못했습니다.", exception); }
    }

    private long insertAndReturnId(String sql, Object... parameters) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("DB에서 생성 번호를 확인하지 못했습니다.");
        return keys.getKey().longValue();
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "BEGINNER" -> "초급";
            case "INTERMEDIATE" -> "중급";
            case "ADVANCED" -> "고급";
            default -> level;
        };
    }

    private String normalizeAdditionalPrompt(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "추가 요청은 300자 이내로 입력해 주세요.");
        }
        return normalized;
    }

    private boolean isPracticalCertification(String code) {
        return "INFORMATION_PROCESSING_PRACTICAL".equalsIgnoreCase(code);
    }

    private String certificationStageLabel(String code, String fallback) {
        return switch (String.valueOf(code).toUpperCase(java.util.Locale.ROOT)) {
            case "INFORMATION_PROCESSING_WRITTEN" -> "필기";
            case "INFORMATION_PROCESSING_PRACTICAL" -> "실기";
            case "LINUX_MASTER_2_FIRST" -> "1차";
            case "LINUX_MASTER_2_SECOND" -> "2차";
            default -> fallback;
        };
    }

    private String generationPrompt(String focusTopic, String additionalPrompt) {
        String normalizedPrompt = normalizeAdditionalPrompt(additionalPrompt);
        if (focusTopic == null || focusTopic.isBlank()) return normalizedPrompt;
        String topicInstruction = "시험 영역: " + focusTopic;
        return normalizedPrompt.isBlank() ? topicInstruction : topicInstruction + "\n" + normalizedPrompt;
    }

    private String normalizeQuestionType(String value) {
        String normalized = value == null ? "MULTIPLE_CHOICE" : value.trim().toUpperCase(Locale.ROOT);
        if (!"MULTIPLE_CHOICE".equals(normalized) && !"SHORT_ANSWER".equals(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "문제 유형이 올바르지 않습니다.");
        }
        return normalized;
    }

    private record ProfileSubject(long subjectId, String subjectCode, String subjectName,
                                  String levelLabel, String focusTopic) {}
    private record PlanHeader(long id, String title, String status, long subjectId, String subjectCode, String subjectName) {}
    public record PlanStepResponse(long id, int stepNo, String title, String content, String status) {}
    public record PlanResponse(long id, String title, String status, long subjectId, String subjectCode,
                               String subjectName, List<PlanStepResponse> steps) {}
    public record AiPlanResponse(PlanResponse plan, long generationRunId, boolean fallback,
                                 String rationale, AiQuotaResponse quota) {}
    public record AiQuizOptionResponse(long id, int optionNo, String text) {}
    public record AiQuizQuestionResponse(long id, int questionNo, String subjectName, String difficulty,
                                         String questionType, String text, List<AiQuizOptionResponse> options) {}
    public record AiQuizResponse(long generationRunId, boolean fallback,
                                 List<AiQuizQuestionResponse> questions, AiQuotaResponse quota) {}
    public record AiQuizCheckRequest(int questionNo, int selectedOptionNo) {}
    public record AiFeedbackResponse(long id, long generationRunId, boolean fallback, String feedback,
                                     List<String> recommendedActions, AiQuotaResponse quota) {}
    public record AiRecommendationResponse(long id, long generationRunId, boolean fallback, String title,
                                           String content, int priority, AiQuotaResponse quota) {}
    public record AiPreferenceRequest(boolean enabled, boolean consent, String explanationStyle,
                                      int availableMinutes) {}
    public record AiPromptRequest(String additionalPrompt, String questionType) {}
    public record AiShortAnswerCheckRequest(long questionId, String answerText) {}
    public record AiShortAnswerCheckResponse(boolean correct, int score, String feedback, String modelAnswer,
                                             long generationRunId, AiQuotaResponse quota) {}
    public record AiFeedbackSummary(long id, long questionId, long generationRunId, String feedback,
                                    List<String> recommendedActions, java.time.Instant createdAt) {}
    public record AiRecommendationSummary(long id, long subjectId, String subjectCode, String subjectName,
                                          String title, String content, int priority,
                                          java.time.Instant createdAt) {}
    private record StoredQuestionOption(long questionId, String difficulty, String questionText,
                                        int optionNo, String optionText, boolean correct) {}
    private record RunSubject(long subjectId, String subjectCode, String subjectName) {}
    private record ShortAnswerStoredQuestion(long id, String questionText, String referenceAnswer,
                                             String acceptedAnswersJson, String gradingRubric) {}
    private record ProblemBankSetting(long ownerUserId, int targetCount) {}
    private static final class DuplicateQuestionException extends RuntimeException {
        private DuplicateQuestionException(String message) { super(message); }
    }
}
