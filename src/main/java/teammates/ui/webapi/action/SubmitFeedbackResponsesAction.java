package teammates.ui.webapi.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import teammates.common.datatransfer.FeedbackParticipantType;
import teammates.common.datatransfer.attributes.FeedbackQuestionAttributes;
import teammates.common.datatransfer.attributes.FeedbackResponseAttributes;
import teammates.common.datatransfer.attributes.FeedbackSessionAttributes;
import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.datatransfer.questions.FeedbackResponseDetails;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.EntityNotFoundException;
import teammates.common.exception.InvalidHttpParameterException;
import teammates.common.exception.InvalidHttpRequestBodyException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.UnauthorizedAccessException;
import teammates.common.util.Const;
import teammates.ui.webapi.output.FeedbackResponsesData;
import teammates.ui.webapi.request.FeedbackResponsesRequest;
import teammates.ui.webapi.request.Intent;

/**
 * Submits a list of feedback responses to a feedback question.
 *
 * <p>This action is meant to completely overwrite the feedback responses that are previously attached to the
 * same feedback question.
 */
public class SubmitFeedbackResponsesAction extends BasicFeedbackSubmissionAction {

    @Override
    protected AuthType getMinAuthLevel() {
        return AuthType.PUBLIC;
    }

    @Override
    public void checkSpecificAccessControl() {
        String feedbackQuestionId = getNonNullRequestParamValue(Const.ParamsNames.FEEDBACK_QUESTION_ID);
        FeedbackQuestionAttributes feedbackQuestion = logic.getFeedbackQuestion(feedbackQuestionId);
        if (feedbackQuestion == null) {
            throw new EntityNotFoundException(new EntityDoesNotExistException("The feedback question does not exist."));
        }
        FeedbackSessionAttributes feedbackSession =
                logic.getFeedbackSession(feedbackQuestion.feedbackSessionName, feedbackQuestion.courseId);

        verifyInstructorCanSeeQuestionIfInModeration(feedbackQuestion);
        verifySessionOpenExceptForModeration(feedbackSession);
        verifyNotPreview();

        Map<String, String> recipientsOfTheQuestion;
        Intent intent = Intent.valueOf(getNonNullRequestParamValue(Const.ParamsNames.INTENT));
        switch (intent) {
        case STUDENT_SUBMISSION:
            gateKeeper.verifyAnswerableForStudent(feedbackQuestion);
            StudentAttributes studentAttributes = getStudentOfCourseFromRequest(feedbackQuestion.getCourseId());
            checkAccessControlForStudentFeedbackSubmission(studentAttributes, feedbackSession);
            recipientsOfTheQuestion = logic.getRecipientsOfQuestion(feedbackQuestion, null, studentAttributes);
            break;
        case INSTRUCTOR_SUBMISSION:
            gateKeeper.verifyAnswerableForInstructor(feedbackQuestion);
            InstructorAttributes instructorAttributes = getInstructorOfCourseFromRequest(feedbackQuestion.getCourseId());
            checkAccessControlForInstructorFeedbackSubmission(instructorAttributes, feedbackSession);
            recipientsOfTheQuestion = logic.getRecipientsOfQuestion(feedbackQuestion, instructorAttributes, null);
            break;
        case INSTRUCTOR_RESULT:
        case STUDENT_RESULT:
            throw new InvalidHttpParameterException("Invalid intent for this action");
        default:
            throw new InvalidHttpParameterException("Unknown intent " + intent);
        }

        FeedbackResponsesRequest submitRequest = getAndValidateRequestBody(FeedbackResponsesRequest.class);

        for (String recipient : submitRequest.getRecipients()) {
            if (!recipientsOfTheQuestion.containsKey(recipient)) {
                throw new UnauthorizedAccessException("The recipient is not a valid recipient of the question");
            }
        }
    }

    @Override
    public ActionResult execute() {
        String feedbackQuestionId = getNonNullRequestParamValue(Const.ParamsNames.FEEDBACK_QUESTION_ID);
        FeedbackQuestionAttributes feedbackQuestion = logic.getFeedbackQuestion(feedbackQuestionId);
        if (feedbackQuestion == null) {
            throw new EntityNotFoundException(new EntityDoesNotExistException("The feedback question does not exist."));
        }

        FeedbackResponsesRequest submitRequest = getAndValidateRequestBody(FeedbackResponsesRequest.class);

        if (submitRequest.getResponses().isEmpty()) {
            logic.deleteFeedbackResponsesForQuestion(feedbackQuestionId);
            return new JsonResult(new FeedbackResponsesData(new ArrayList<>()));
        }

        List<FeedbackResponseAttributes> existingResponses;

        String giverIdentifier;
        String giverSection;
        Intent intent = Intent.valueOf(getNonNullRequestParamValue(Const.ParamsNames.INTENT));
        switch (intent) {
        case STUDENT_SUBMISSION:
            StudentAttributes studentAttributes = getStudentOfCourseFromRequest(feedbackQuestion.getCourseId());
            giverIdentifier =
                    feedbackQuestion.getGiverType() == FeedbackParticipantType.TEAMS
                            ? studentAttributes.getTeam() : studentAttributes.getEmail();
            giverSection = studentAttributes.getSection();
            existingResponses = logic.getFeedbackResponsesFromStudentOrTeamForQuestion(feedbackQuestion, studentAttributes);
            logic.populateFieldsToGenerateInQuestion(feedbackQuestion,
                    studentAttributes.getEmail(), studentAttributes.getTeam());
            break;
        case INSTRUCTOR_SUBMISSION:
            InstructorAttributes instructorAttributes = getInstructorOfCourseFromRequest(feedbackQuestion.getCourseId());
            giverIdentifier = instructorAttributes.getEmail();
            giverSection = Const.DEFAULT_SECTION;
            existingResponses = logic.getFeedbackResponsesFromInstructorForQuestion(feedbackQuestion, instructorAttributes);
            logic.populateFieldsToGenerateInQuestion(feedbackQuestion,
                    instructorAttributes.getEmail(), null);
            break;
        default:
            throw new InvalidHttpParameterException("Unknown intent " + intent);
        }

        Map<String, FeedbackResponseAttributes> existingResponsesPerRecipient = new HashMap<>();
        existingResponses.forEach(response -> existingResponsesPerRecipient.put(response.getRecipient(), response));

        List<FeedbackResponsesRequest.FeedbackResponseRequest> responsesPerRecipient =
                submitRequest.getResponses();
        List<FeedbackResponseAttributes> feedbackResponsesToValidate = new ArrayList<>();
        List<FeedbackResponseAttributes> feedbackResponsesToAdd = new ArrayList<>();
        List<FeedbackResponseAttributes.UpdateOptions> feedbackResponsesToUpdate = new ArrayList<>();

        responsesPerRecipient.forEach(responseRequest -> {
            String recipient = responseRequest.getRecipient();
            FeedbackResponseDetails responseDetails = responseRequest.getResponseDetails();

            if (existingResponsesPerRecipient.containsKey(recipient)) {
                String recipientSection = getRecipientSection(feedbackQuestion.getCourseId(),
                        feedbackQuestion.getGiverType(),
                        feedbackQuestion.getRecipientType(), recipient);
                FeedbackResponseAttributes updatedResponse =
                        new FeedbackResponseAttributes(existingResponsesPerRecipient.get(recipient));
                FeedbackResponseAttributes.UpdateOptions updateOptions =
                        FeedbackResponseAttributes.updateOptionsBuilder(updatedResponse.getId())
                                .withGiver(giverIdentifier)
                                .withGiverSection(giverSection)
                                .withRecipient(recipient)
                                .withRecipientSection(recipientSection)
                                .withResponseDetails(responseDetails)
                                .build();
                updatedResponse.update(updateOptions);

                feedbackResponsesToValidate.add(updatedResponse);
                feedbackResponsesToUpdate.add(updateOptions);
            } else {
                FeedbackResponseAttributes feedbackResponse = FeedbackResponseAttributes
                        .builder(feedbackQuestion.getId(), giverIdentifier, recipient)
                        .withGiverSection(giverSection)
                        .withRecipientSection(getRecipientSection(feedbackQuestion.getCourseId(),
                                feedbackQuestion.getGiverType(),
                                feedbackQuestion.getRecipientType(), recipient))
                        .withCourseId(feedbackQuestion.getCourseId())
                        .withFeedbackSessionName(feedbackQuestion.getFeedbackSessionName())
                        .withResponseDetails(responseDetails)
                        .build();

                feedbackResponsesToValidate.add(feedbackResponse);
                feedbackResponsesToAdd.add(feedbackResponse);
            }
        });

        validateResponsesOfQuestion(feedbackQuestion, feedbackResponsesToValidate);

        List<String> recipients = submitRequest.getRecipients();
        List<FeedbackResponseAttributes> feedbackResponsesToDelete = existingResponsesPerRecipient.entrySet().stream()
                .filter(entry -> !recipients.contains(entry.getKey()))
                .map(entry -> entry.getValue())
                .collect(Collectors.toList());

        for (FeedbackResponseAttributes feedbackResponse : feedbackResponsesToDelete) {
            logic.deleteFeedbackResponseCascade(feedbackResponse.getId());
        }

        List<FeedbackResponseAttributes> output = new ArrayList<>();

        for (FeedbackResponseAttributes feedbackResponse : feedbackResponsesToAdd) {
            try {
                output.add(logic.createFeedbackResponse(feedbackResponse));
            } catch (InvalidParametersException | EntityAlreadyExistsException e) {
                throw new InvalidHttpRequestBodyException(e.getMessage(), e);
            }
        }

        for (FeedbackResponseAttributes.UpdateOptions feedbackResponse : feedbackResponsesToUpdate) {
            try {
                output.add(logic.updateFeedbackResponseCascade(feedbackResponse));
            } catch (InvalidParametersException | EntityAlreadyExistsException | EntityDoesNotExistException e) {
                throw new InvalidHttpRequestBodyException(e.getMessage(), e);
            }
        }

        return new JsonResult(new FeedbackResponsesData(output));
    }

    private void validateResponsesOfQuestion(FeedbackQuestionAttributes questionAttributes,
                                             List<FeedbackResponseAttributes> responsesToValidate) {
        List<FeedbackResponseDetails> responseDetails = responsesToValidate.stream()
                .map(FeedbackResponseAttributes::getResponseDetails)
                .collect(Collectors.toList());

        List<String> questionSpecificErrors =
                questionAttributes.getQuestionDetails().validateResponsesDetails(responseDetails);

        if (!questionSpecificErrors.isEmpty()) {
            throw new InvalidHttpRequestBodyException(questionSpecificErrors.toString());
        }
    }

}
