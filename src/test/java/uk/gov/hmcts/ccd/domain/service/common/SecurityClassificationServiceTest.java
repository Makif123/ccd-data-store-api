package uk.gov.hmcts.ccd.domain.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gov.hmcts.ccd.config.JacksonUtils;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.definition.CaseDefinitionRepository;
import uk.gov.hmcts.ccd.domain.model.casedataaccesscontrol.AccessProfile;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseFieldDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.domain.model.std.AuditEvent;
import uk.gov.hmcts.ccd.domain.service.casedataaccesscontrol.CaseDataAccessControl;

import static com.google.common.collect.Sets.newHashSet;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.ccd.data.casedetails.SecurityClassification.PRIVATE;
import static uk.gov.hmcts.ccd.data.casedetails.SecurityClassification.PUBLIC;
import static uk.gov.hmcts.ccd.data.casedetails.SecurityClassification.RESTRICTED;
import static uk.gov.hmcts.ccd.domain.service.common.TestBuildersUtil.CaseFieldBuilder.newCaseField;
import static uk.gov.hmcts.ccd.domain.service.common.TestBuildersUtil.CaseTypeBuilder.newCaseType;

// too many legacy OperatorWrap occurrences on JSON strings so suppress until move to Java12+
@SuppressWarnings("checkstyle:OperatorWrap")
public class SecurityClassificationServiceTest {

    private static final JsonNodeFactory JSON_NODE_FACTORY = new JsonNodeFactory(false);
    private static final String JURISDICTION_ID = "PROBATE";
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    private SecurityClassificationServiceImpl securityClassificationService;

    @Mock
    private Authentication authentication;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private CaseDataAccessControl caseDataAccessControl;

    @Mock
    private CaseDefinitionRepository caseDefinitionRepository;

    @Mock
    private CaseAccessCategoriesService caseAccessCategoriesService;

    private CaseDetails caseDetails;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        caseDetails = new CaseDetails();
        caseDetails.setJurisdiction(JURISDICTION_ID);

        doReturn(authentication).when(securityContext).getAuthentication();
        SecurityContextHolder.setContext(securityContext);

        securityClassificationService = spy(new SecurityClassificationServiceImpl(caseDataAccessControl,
            caseDefinitionRepository,
            caseAccessCategoriesService));
        Predicate<CaseDetails> predicate = cd -> true;
        when(caseAccessCategoriesService
            .caseHasMatchingCaseAccessCategories(anySet(), anyBoolean())).thenReturn(predicate);
    }

    @Nested
    @DisplayName("Check security classification for a field")
    class CheckSecurityClassificationForField {
        private static final String CASE_TYPE_ONE = "CaseTypeOne";
        private static final String SC_PUBLIC = "PUBLIC";
        private static final String SC_RESTRICTED = "RESTRICTED";
        private static final String CASE_FIELD_ID_1_1 = "CASE_FIELD_1_1";
        private static final String CASE_FIELD_ID_1_2 = "CASE_FIELD_1_2";
        private final CaseFieldDefinition testCaseField11 =
            newCaseField().withId(CASE_FIELD_ID_1_1).withSC(SC_PUBLIC).build();
        private final CaseFieldDefinition testCaseField12 =
            newCaseField().withId(CASE_FIELD_ID_1_2).withSC(SC_RESTRICTED).build();
        private final CaseTypeDefinition testCaseTypeDefinition = newCaseType()
            .withId(CASE_TYPE_ONE)
            .withField(testCaseField11)
            .withField(testCaseField12)
            .build();

        @Test
        @DisplayName("should return TRUE when user has enough SC rank")
        void userHasEnoughSecurityClassificationForField() {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(PUBLIC, PRIVATE));

            assertTrue(securityClassificationService.userHasEnoughSecurityClassificationForField(JURISDICTION_ID,
                testCaseTypeDefinition,
                CASE_FIELD_ID_1_1));
        }

        @Test
        @DisplayName("should return FALSE when user doesn't have enough SC rank")
        void userDoesNotHaveEnoughSecurityClassificationForField() {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(PUBLIC, PRIVATE));

            assertFalse(securityClassificationService.userHasEnoughSecurityClassificationForField(JURISDICTION_ID,
                testCaseTypeDefinition,
                CASE_FIELD_ID_1_2));
        }
    }

    @Nested
    @DisplayName("getUserClassification()")
    class GetUserClassification {

        @Test
        @DisplayName("should retrieve user classifications from user repository")
        public void shouldRetrieveClassificationsFromRepository() {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(PUBLIC, PRIVATE));
            securityClassificationService.getUserClassification(new CaseTypeDefinition(), true);

            verify(caseDataAccessControl, times(1))
                .getUserClassifications(any(CaseTypeDefinition.class), anyBoolean());
        }

        @Test
        @DisplayName("should keep highest ranked classification")
        public void shouldRetrieveHigherRankedRole() {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(PUBLIC, PRIVATE));

            Optional<SecurityClassification> userClassification = securityClassificationService
                .getUserClassification(new CaseTypeDefinition(), true);

            assertEquals(PRIVATE,
                userClassification.get(),
                "The user's security classification for jurisdiction is incorrect");
        }

        @Test
        @DisplayName("should retrieve no security classification if empty list of classifications returned by user "
            + "repository")
        public void shouldRetrieveNoSecurityClassificationIfEmptyListOfClassifications() {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet());
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet());

            Optional<SecurityClassification> userClassification = securityClassificationService.getUserClassification(
                new CaseTypeDefinition(), true);

            assertFalse(userClassification.isPresent(), "Should not have classification");
        }
    }

    @Nested
    @DisplayName("Apply to CaseDetails")
    class ApplyToCaseDetails {

        private CaseDetails caseDetails;

        @BeforeEach
        void setUp() throws IOException {
            caseDetails = new CaseDetails();
            caseDetails.setJurisdiction(JURISDICTION_ID);
        }

        Optional<CaseDetails> applyClassification(SecurityClassification userClassification,
                                                  SecurityClassification caseClassification) {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(userClassification));

            when(caseDataAccessControl.getUserClassifications(any(CaseDetails.class)))
                .thenReturn(newHashSet(userClassification));

            caseDetails.setSecurityClassification(caseClassification);

            return securityClassificationService.applyClassification(caseDetails);
        }

        @Test
        @DisplayName("should return null when user has no classification")
        void shouldReturnNullWhenUserNoClassification() {
            assertThat(applyClassification(null, RESTRICTED).isPresent(), is(false));
        }

        @Test
        @DisplayName("should return null when user has lower classification")
        void shouldReturnNullWhenUserLowerClassification() {
            assertThat(applyClassification(PUBLIC, RESTRICTED).isPresent(), is(false));
        }

        @Test
        @DisplayName("should return case when user has same classification")
        void shouldReturnCaseWhenUserSameClassification() {
            assertThat(applyClassification(PRIVATE, PRIVATE).get(), sameInstance(caseDetails));
        }

        @Test
        @DisplayName("should return case when user has higher classification")
        void shouldReturnCaseWhenUserHigherClassification() {
            assertThat(applyClassification(RESTRICTED, PUBLIC).get(), sameInstance(caseDetails));
        }

    }

    @Nested
    @DisplayName("CaseDetails case access categories")
    class ApplyCaseAccessCategoriesToCaseDetails {

        private CaseDetails caseDetails;

        @BeforeEach
        void setUp() throws IOException {
            caseDetails = new CaseDetails();

            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"CaseAccessCategory\": \"Civil/Standard\"\n," +
                    "       \"Note2\": \"note2\"\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            caseDetails.setJurisdiction(JURISDICTION_ID);
        }

        Optional<CaseDetails> applyClassification(SecurityClassification userClassification,
                                                  SecurityClassification caseClassification,
                                                  String caseAccessCategories) {
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean()))
                .thenReturn(newHashSet(userClassification));

            when(caseDataAccessControl.getUserClassifications(any(CaseDetails.class)))
                .thenReturn(newHashSet(userClassification));

            AccessProfile accessProfile = new AccessProfile("Test");
            accessProfile.setCaseAccessCategories(caseAccessCategories);
            when(caseDataAccessControl.generateAccessProfilesByCaseDetails(any(CaseDetails.class)))
                .thenReturn(newHashSet(accessProfile));

            caseDetails.setSecurityClassification(caseClassification);

            return securityClassificationService.applyClassification(caseDetails);
        }

        @Test
        @DisplayName("should return null when case has no matching case access categories")
        void shouldReturnNullWhenCaseHasNoCaseAccessCategories() {
            assertThat(applyClassification(PUBLIC, RESTRICTED, "Civil/Test").isPresent(), is(false));
        }

        @Test
        @DisplayName("should return case details when case and access profiles has matching case access categories")
        void shouldReturnCaseDetailsWhenCaseAndAccessProfileHasSameCategories() {
            assertThat(applyClassification(PUBLIC, RESTRICTED, "Civil/Standard,Crime/Standard").isPresent(), is(false));
        }

    }

    @Nested
    @DisplayName("Apply to List of Events")
    class ApplyToEventList {

        private AuditEvent publicEvent;
        private AuditEvent privateEvent;
        private AuditEvent restrictedEvent;

        @BeforeEach
        void setUp() {
            publicEvent = new AuditEvent();
            publicEvent.setSecurityClassification(PUBLIC);

            privateEvent = new AuditEvent();
            privateEvent.setSecurityClassification(PRIVATE);

            restrictedEvent = new AuditEvent();
            restrictedEvent.setSecurityClassification(RESTRICTED);

            doReturn(Optional.empty()).when(securityClassificationService)
                .getUserClassification(any(CaseTypeDefinition.class), anyBoolean());
        }

        @Test
        @DisplayName("should return empty list when given null")
        void shouldReturnEmptyListInsteadOfNull() {
            final List<AuditEvent> classifiedEvents =
                securityClassificationService.applyClassification(caseDetails, null);

            assertAll(
                () -> assertThat(classifiedEvents, is(notNullValue())),
                () -> assertThat(classifiedEvents, hasSize(0))
            );
        }

        @Test
        @DisplayName("should return all events when user has higher classification")
        void shouldReturnAllEventsWhenUserHigherClassification() {
            when(caseDataAccessControl.getUserClassifications(any(CaseDetails.class)))
                .thenReturn(newHashSet(RESTRICTED));

            final List<AuditEvent> classifiedEvents =
                securityClassificationService.applyClassification(caseDetails,
                                                                Arrays.asList(publicEvent,
                                                                    privateEvent,
                                                                    restrictedEvent));

            assertAll(
                () -> assertThat(classifiedEvents, hasSize(3)),
                () -> assertThat(classifiedEvents, hasItems(publicEvent, privateEvent, restrictedEvent))
            );
        }

        @Test
        @DisplayName("should filter out events with higher classification")
        void shouldFilterOutEventsHigherClassification() {
            when(caseDataAccessControl.getUserClassifications(any(CaseDetails.class)))
                .thenReturn(newHashSet(PUBLIC));

            final List<AuditEvent> classifiedEvents =
                securityClassificationService.applyClassification(caseDetails,
                                                                    Arrays.asList(publicEvent,
                                                                        privateEvent,
                                                                        restrictedEvent));

            assertAll(
                () -> assertThat(classifiedEvents, hasSize(1)),
                () -> assertThat(classifiedEvents, hasItems(publicEvent))
            );
        }

        @Test
        @DisplayName("should return empty list when user has no classification")
        void shouldReturnEmptyListWhenNoUserClassification() {

            final List<AuditEvent> classifiedEvents =
                securityClassificationService.applyClassification(caseDetails,
                                                                    Arrays.asList(publicEvent,
                                                                        privateEvent,
                                                                        restrictedEvent));

            assertThat(classifiedEvents, hasSize(0));
        }
    }

    @Nested
    @DisplayName("getClassificationForEvent()")
    class GetSecurityClassificationForEvent {

        private final CaseTypeDefinition caseTypeDefinition = new CaseTypeDefinition();

        @BeforeEach
        void setUp() throws IOException {
            CaseEventDefinition createEvent = new CaseEventDefinition();
            createEvent.setId("createEvent");
            createEvent.setSecurityClassification(RESTRICTED);
            CaseEventDefinition updateEvent = new CaseEventDefinition();
            updateEvent.setId("updateEvent");
            updateEvent.setSecurityClassification(PRIVATE);
            List<CaseEventDefinition> events = Arrays.asList(createEvent, updateEvent);
            caseTypeDefinition.setEvents(events);
        }

        @Test
        @DisplayName("should return classification relevant for event")
        void shouldGetClassificationForEvent() {
            CaseEventDefinition eventTrigger = new CaseEventDefinition();
            eventTrigger.setId("createEvent");
            SecurityClassification result = securityClassificationService.getClassificationForEvent(caseTypeDefinition,
                eventTrigger);

            assertThat(result, is(equalTo(RESTRICTED)));
        }

        @Test
        @DisplayName("should fail to return fields when event not found")
        void shouldThrowRuntimeExceptionIfEventNotFound() {
            CaseEventDefinition caseEventDefinition = new CaseEventDefinition();
            caseEventDefinition.setId("unknown");

            assertThrows(RuntimeException.class, () ->
                securityClassificationService.getClassificationForEvent(caseTypeDefinition, caseEventDefinition));
        }
    }

    @Nested
    @DisplayName("Apply to fields of CaseDetails")
    class ApplyToCaseDetailsFields {

        private static final String FIRST_CHILD_ID = "46f98326-6c88-426d-82be-d362f0246b7a";
        private static final String SECOND_CHILD_ID = "7c7cfd2a-b5d7-420a-8420-3ac3019cfdc7";
        private static final String VALUE = "value";
        private static final String ID = "id";
        private CaseDetails caseDetails;

        private final JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(false);

        @BeforeEach
        void setUp() throws IOException {
            caseDetails = new CaseDetails();
            caseDetails.setJurisdiction(JURISDICTION_ID);
        }

        CaseDetails applyClassification(SecurityClassification userClassification) {
            doReturn(Optional.ofNullable(userClassification)).when(securityClassificationService)
                .getUserClassification(any(CaseTypeDefinition.class), anyBoolean());
            when(caseDataAccessControl.getUserClassifications(any(CaseTypeDefinition.class), anyBoolean())).thenReturn(
                Sets.newHashSet(userClassification));

            when(caseDataAccessControl.getUserClassifications(any(CaseDetails.class))).thenReturn(
                Sets.newHashSet(userClassification));

            caseDetails.setSecurityClassification(PRIVATE);

            return securityClassificationService.applyClassification(caseDetails).get();
        }


        @Test
        @DisplayName("should remove all fields for case if data classification missing")
        void shouldNotFilterFieldsForCaseIfDataClassificationMissing() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"note1\"\n," +
                    "       \"Note2\": \"note2\"\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Note1"), is(false)),
                () -> assertThat(resultNode.has("Note2"), is(false))
            );
        }

        @Test
        @DisplayName("should filter out fields for case if invalid security classification")
        void shouldFilterOutFieldsForCaseIfUnparsableSecurityClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"note1\"\n," +
                    "       \"Note2\": \"note2\"\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"nonClassification\"\n," +
                    "       \"Note2\": \"nonClassification\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Note1"), is(false)),
                () -> assertThat(resultNode.has("Note2"), is(false))
            );
        }

        @Test
        @DisplayName("should filter out simple fields with higher classification")
        void shouldFilterFieldsForCaseWithSimpleTextTypes() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"note1\"\n," +
                    "       \"Note2\": \"note2\"\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Note1"), is(false)),
                () -> assertThat(resultNode.get("Note2"), is(equalTo(getTextNode("note2"))))
            );
        }

        @Test
        @DisplayName("should filter out all simple fields with higher classification")
        void shouldFilterAllFieldsForCaseWithSimpleTextTypes() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"note1\"\n," +
                    "       \"Note2\": \"note2\"\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"RESTRICTED\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should filter out multi-select fields with higher classification")
        void shouldFilterFieldsForCaseWithMultiSelectListTypes() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"OrderType1\":[  \n" +
                    "         \"ChildOrder\",\n" +
                    "         \"SpecialIssueOrder\"\n" +
                    "      ]," +
                    "       \"OrderType2\":[  \n" +
                    "         \"ChildOrder\",\n" +
                    "         \"SpecialIssueOrder\"\n" +
                    "      ]," +
                    "       \"OrderType3\":[  \n" +
                    "         \"V1\",\n" +
                    "         \"V2\"\n" +
                    "      ]}\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"OrderType1\": \"PUBLIC\"," +
                    "       \"OrderType2\": \"RESTRICTED\"," +
                    "       \"OrderType3\": \"PRIVATE\"}\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("OrderType1"), hasItems(
                    getTextNode("ChildOrder"), getTextNode("SpecialIssueOrder"))),
                () -> assertThat(resultNode.has("OrderType2"), is(false)),
                () -> assertThat(resultNode.get("OrderType3"), hasItems(
                    getTextNode("V1"), getTextNode("V2")))
            );
        }

        @Test
        @DisplayName("should filter out all multi-select fields with higher classification")
        void shouldFilterAllFieldsForCaseWithMultiSelectListTypes() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"OrderType1\":[  \n" +
                    "         \"ChildOrder\",\n" +
                    "         \"SpecialIssueOrder\"\n" +
                    "      ]," +
                    "       \"OrderType2\":[  \n" +
                    "         \"ChildOrder\",\n" +
                    "         \"SpecialIssueOrder\"\n" +
                    "      ]}\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"OrderType1\": \"RESTRICTED\"," +
                    "       \"OrderType2\": \"RESTRICTED\"}\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())))
            );
        }

        @Test
        @DisplayName("should filter out nested fields for case if missing security classification for complex field")
        void shouldFilterOutNestedFieldsForCaseIfMissingSecurityClassificationForComplexField() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": { \n" +
                    "           \"Note2\": \"note1\"\n" +
                    "       },\n" +
                    "       \"Note3\": { \n" +
                    "           \"Note4\": \"note4\"\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": { \n" +
                    "           \"value\": { \n" +
                    "               \"Note2\": null \n" +
                    "           } \n" +
                    "        }, \n" +
                    "       \"Note3\": { \n" +
                    "           \"classification\": null,\n" +
                    "           \"value\": { \n" +
                    "               \"Note4\": null \n" +
                    "           } \n" +
                    "        } \n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should filter out fields within nested types (complex->complex->complex)")
        void shouldFilterFieldsForCaseWithNestedComplexTypes() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "            \"NestedField\": {  \n" +
                    "               \"Field1\": \"field1\",\n" +
                    "               \"NestedNestedField\": {\n" +
                    "                   \"Field2\": \"field2\",\n" +
                    "                   \"Field3\": \"field3\"\n" +
                    "                }\n" +
                    "            }\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"value\": { \n" +
                    "            \"NestedField\": {  \n" +
                    "               \"classification\": \"PRIVATE\",\n" +
                    "               \"value\": {  \n" +
                    "                    \"Field1\":\"PUBLIC\",\n" +
                    "                    \"NestedNestedField\": { \n" +
                    "                       \"classification\": \"PRIVATE\",\n" +
                    "                       \"value\": { \n" +
                    "                         \"Field2\": \"RESTRICTED\",\n" +
                    "                         \"Field3\": \"PRIVATE\"\n" +
                    "                       }\n" +
                    "                    }," +
                    "                    \"NestedNestedField2\": { \n" +
                    "                       \"classification\": \"RESTRICTED\",\n" +
                    "                       \"value\": { \n" +
                    "                         \"Field2\": \"RESTRICTED\",\n" +
                    "                         \"Field3\": \"RESTRICTED\"\n" +
                    "                       }\n" +
                    "                   }\n" +
                    "               }\n" +
                    "            }\n" +
                    "         }\n" +
                    "      }\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("Field").get("NestedField").get("Field1"),
                    is(equalTo(getTextNode("field1")))),
                () -> assertThat(resultNode.get("Field").get("NestedField").get("NestedNestedField").has("Field2"),
                    is(false)),
                () -> assertThat(resultNode.get("Field").get("NestedField").get("NestedNestedField").get("Field3"),
                    is(equalTo(getTextNode("field3")))),
                () -> assertThat(resultNode.get("Field").get("NestedField").has("NestedNestedField2"), is(false))
            );
        }

        @Test
        @DisplayName("should remove complex nodes when the fields and all its nested fields have higher"
            + " classification")
        void shouldFilterOutAllFieldsForCaseWithNestedComplexTypesOfHigherClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "         \"NestedField\": {  \n" +
                    "           \"Field1\": \"field1\",\n" +
                    "           \"NestedNestedField\": {\n" +
                    "             \"Field2\": \"field2\",\n" +
                    "             \"Field3\": \"field3\"\n" +
                    "           }\n" +
                    "         }\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "         \"classification\": \"RESTRICTED\",\n" +
                    "         \"value\": {\n" +
                    "            \"NestedField\": {  \n" +
                    "              \"classification\": \"RESTRICTED\",\n" +
                    "              \"value\": {\n" +
                    "                \"Field1\":\"RESTRICTED\",\n" +
                    "                \"NestedNestedField\": {\n" +
                    "                  \"classification\": \"RESTRICTED\",\n" +
                    "                  \"value\": {\n" +
                    "                    \"Field2\": \"RESTRICTED\",\n" +
                    "                    \"Field3\": \"RESTRICTED\"\n" +
                    "                  }\n" +
                    "                }\n" +
                    "              }\n" +
                    "            }\n" +
                    "         }\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should filter out nested complex objects but leave empty top level one if classification "
            + "matches")
        void shouldFilterOutNestedComplexObjectsButLeaveEmptyTopLevelOneIfClassificationMatches() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "         \"NestedField\": {  \n" +
                    "           \"Field1\": \"field1\",\n" +
                    "           \"NestedNestedField\": {\n" +
                    "             \"Field2\": \"field2\",\n" +
                    "             \"Field3\": \"field3\"\n" +
                    "           }\n" +
                    "         }\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Field\": {  \n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"value\": {\n" +
                    "            \"NestedField\": {  \n" +
                    "              \"classification\": \"RESTRICTED\",\n" +
                    "              \"value\": {\n" +
                    "                \"Field1\":\"RESTRICTED\",\n" +
                    "                \"NestedNestedField\": {\n" +
                    "                  \"classification\": \"RESTRICTED\",\n" +
                    "                  \"value\": {\n" +
                    "                    \"Field2\": \"RESTRICTED\",\n" +
                    "                    \"Field3\": \"RESTRICTED\"\n" +
                    "                  }\n" +
                    "                }\n" +
                    "              }\n" +
                    "            }\n" +
                    "         }\n" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Field"), is(true)),
                () -> assertThat(resultNode.get("Field").isObject(), is(true)),
                () -> assertThat(resultNode.get("Field").has("NestedField"), is(false))
            );
        }

        @Test
        @DisplayName("current implementation: should apply classification at collection level only")
        void shouldApplyClassificationsAtCollectionLevelOnly() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote11\",\n" +
                    "                   \"Note2\": \"someNote21\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address2\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote12\",\n" +
                    "                   \"Note2\": \"someNote22\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\": \"PRIVATE\" }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("Addresses").get(0).get(ID), is(equalTo(getTextNode(FIRST_CHILD_ID)))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Address"),
                    equalTo(getTextNode("address1"))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").get("Note1"),
                    is(equalTo(getTextNode("someNote11")))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").get("Note2"),
                    is(equalTo(getTextNode("someNote21")))),

                () -> assertThat(resultNode.get("Addresses").get(1).get(ID),
                    is(equalTo(getTextNode(SECOND_CHILD_ID)))),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Address"),
                    is(equalTo(getTextNode("address2")))),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Notes").get("Note1"),
                    is(equalTo(getTextNode("someNote12")))),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Notes").get("Note2"),
                    is(equalTo(getTextNode("someNote22"))))
            );
        }

        @Test
        @DisplayName("should filter out collection items but leave empty collection if classification matches")
        void shouldFilterOutCollectionItemsButLeaveEmptyCollectionIfClassificationMatches() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote11\",\n" +
                    "                   \"Note2\": \"someNote21\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address2\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote12\",\n" +
                    "                   \"Note2\": \"someNote22\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address3\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote13\",\n" +
                    "                   \"Note2\": \"someNote23\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"THIRD_CHILD\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\": {\n" +
                    "     \"classification\": \"PRIVATE\",\n" +
                    "     \"value\": [\n" +
                    "       {\n" +
                    "         \"value\": {\n" +
                    "           \"Address\": \"RESTRICTED\",\n" +
                    "           \"Notes\": {\n" +
                    "             \"classification\": \"RESTRICTED\",\n" +
                    "             \"value\": {\n" +
                    "               \"Note1\": \"RESTRICTED\",\n" +
                    "               \"Note2\": \"RESTRICTED\"\n" +
                    "             }\n" +
                    "           }\n" +
                    "         },\n" +
                    "         \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "       },\n" +
                    "       {\n" +
                    "         \"value\": {\n" +
                    "           \"Address\": \"RESTRICTED\",\n" +
                    "           \"Notes\": {\n" +
                    "             \"classification\": \"RESTRICTED\",\n" +
                    "             \"value\": {\n" +
                    "               \"Note1\": \"RESTRICTED\",\n" +
                    "               \"Note2\": \"RESTRICTED\"\n" +
                    "             }\n" +
                    "           }\n" +
                    "         },\n" +
                    "         \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "       },\n" +
                    "       {\n" +
                    "         \"value\": {\n" +
                    "           \"Address\": \"PRIVATE\",\n" +
                    "           \"Notes\": {\n" +
                    "             \"classification\": \"PRIVATE\",\n" +
                    "             \"value\": {\n" +
                    "               \"Note1\": \"PRIVATE\",\n" +
                    "               \"Note2\": \"PRIVATE\"\n" +
                    "             }\n" +
                    "           }\n" +
                    "         }\n" +
                    "       }\n" +
                    "     ]\n" +
                    "   }\n" +
                    " }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Addresses"), is(true)),
                () -> assertThat(resultNode.get("Addresses").isArray(), is(true)),
                () -> assertThat(resultNode.get("Addresses").size(), is(0))
            );
        }

        @Test
        @DisplayName("should filter out simple collection items with higher classification")
        void shouldFilterOutSimpleCollectionItemsWithHigherClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Aliases\":[  \n" +
                    "         {  \n" +
                    "            \"value\": \"Alias #1\",\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\": \"Alias #2\",\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\": \"Alias #3\",\n" +
                    "            \"id\":\"THIRD_CHILD\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Aliases\": {\n" +
                    "     \"classification\": \"PRIVATE\",\n" +
                    "     \"value\": [\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "       },\n" +
                    "       {\n" +
                    "         \"classification\": \"RESTRICTED\",\n" +
                    "         \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "       },\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"THIRD_CHILD\"\n" +
                    "       }\n" +
                    "     ]\n" +
                    "   }\n" +
                    " }\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Aliases"), is(true)),
                () -> assertThat(resultNode.get("Aliases").isArray(), is(true)),
                () -> assertThat(resultNode.get("Aliases").size(), is(2))
            );
            final JsonNode aliases = resultNode.get("Aliases");
            final String alias1 = aliases.get(0).get("value").textValue();
            final String alias3 = aliases.get(1).get("value").textValue();

            assertAll("Restricted Alias #2 should have been removed",
                () -> assertThat(alias1, is("Alias #1")),
                () -> assertThat(alias3, is("Alias #3"))
            );
        }

        @Test
        @DisplayName("should apply classifications on collections nested in collection")
        void shouldApplyClassificationsOnNestedCollections() throws IOException {
            caseDetails.setData(nestedCollectionData());
            caseDetails.setDataClassification(nestedCollectionClassification());

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());

            assertThat(resultNode.has("collection"), is(true));

            final JsonNode collection = resultNode.get("collection");

            assertAll(
                () -> assertThat(collection.isArray(), is(true)),
                () -> assertThat(collection.size(), is(2))
            );
            final JsonNode item1Collection1 = collection.get(0).get("value").get("collection1");
            final JsonNode item2Collection1 = collection.get(1).get("value").get("collection1");

            assertAll(
                () -> assertThat(item1Collection1.isArray(), is(true)),
                () -> assertThat(item1Collection1.size(), is(1)),
                () -> assertThat(item2Collection1.isArray(), is(true)),
                () -> assertThat(item2Collection1.size(), is(1))
            );

            final String item1_1 = item1Collection1.get(0).get("value").textValue();
            final String item2_2 = item2Collection1.get(0).get("value").textValue();

            assertAll(
                () -> assertThat(item1_1, equalTo("ITEM_1_1")),
                () -> assertThat(item2_2, equalTo("ITEM_2_2"))
            );
        }

        private Map<String, JsonNode> nestedCollectionClassification() throws IOException {
            return JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"collection\": {\n" +
                    "     \"classification\": \"PRIVATE\",\n" +
                    "     \"value\": [\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"" + FIRST_CHILD_ID + "\",\n" +
                    "         \"value\": {" +
                    "            \"collection1\": {" +
                    "              \"classification\": \"PRIVATE\",\n" +
                    "              \"value\": [" +
                    "                {" +
                    "                  \"id\": \"ITEM_1_1\"," +
                    "                  \"classification\": \"PRIVATE\"" +
                    "                }," +
                    "                {" +
                    "                  \"id\": \"ITEM_1_2\"," +
                    "                  \"classification\": \"RESTRICTED\"" +
                    "                }" +
                    "              ]" +
                    "            }" +
                    "         }" +
                    "       },\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"" + SECOND_CHILD_ID + "\",\n" +
                    "         \"value\": {" +
                    "            \"collection1\": {" +
                    "              \"classification\": \"PRIVATE\",\n" +
                    "              \"value\": [" +
                    "                {" +
                    "                  \"id\": \"ITEM_2_1\"," +
                    "                  \"classification\": \"RESTRICTED\"" +
                    "                }," +
                    "                {" +
                    "                  \"id\": \"ITEM_2_2\"," +
                    "                  \"classification\": \"PRIVATE\"" +
                    "                }" +
                    "              ]" +
                    "            }" +
                    "         }" +
                    "       }\n" +
                    "     ]\n" +
                    "   }\n" +
                    " }\n"
            ));
        }

        private Map<String, JsonNode> nestedCollectionData() throws IOException {
            return JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"collection\":[  \n" +
                    "         {  \n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\",\n" +
                    "            \"value\": {\n" +
                    "               \"collection1\": [" +
                    "                  {" +
                    "                     \"id\": \"ITEM_1_1\"," +
                    "                     \"value\": \"ITEM_1_1\"" +
                    "                  }," +
                    "                  {" +
                    "                     \"id\": \"ITEM_1_2\"," +
                    "                     \"value\": \"ITEM_1_2\"" +
                    "                  }" +
                    "               ]" +
                    "            }\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\",\n" +
                    "            \"value\": {\n" +
                    "               \"collection1\": [" +
                    "                  {" +
                    "                     \"id\": \"ITEM_2_1\"," +
                    "                     \"value\": \"ITEM_2_1\"" +
                    "                  }," +
                    "                  {" +
                    "                     \"id\": \"ITEM_2_2\"," +
                    "                     \"value\": \"ITEM_2_2\"" +
                    "                  }" +
                    "               ]" +
                    "            }\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
        }

        @Test
        @DisplayName("should apply collection-level classification before items-level classification")
        void shouldApplyCollectionLevelClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Aliases\":[  \n" +
                    "         {  \n" +
                    "            \"value\": \"Alias #1\",\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\": \"Alias #2\",\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Aliases\": {\n" +
                    "     \"classification\": \"RESTRICTED\",\n" +
                    "     \"value\": [\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "       },\n" +
                    "       {\n" +
                    "         \"classification\": \"PRIVATE\",\n" +
                    "         \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "       }\n" +
                    "     ]\n" +
                    "   }\n" +
                    " }\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat("should remove classified collection node",
                resultNode.has("Aliases"), is(false));
        }

        @Test
        // TODO Target implementation, see RDM-1204
        @DisplayName("should filter out fields within collection items")
        void shouldFilterFieldsWithinCollectionItems() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote11\",\n" +
                    "                   \"Note2\": \"someNote21\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address2\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote12\",\n" +
                    "                   \"Note2\": \"someNote22\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":{  \n" +
                    "       \"classification\": \"PRIVATE\",\n" +
                    "       \"value\": [\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"RESTRICTED\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"classification\": \"PRIVATE\",\n" +
                    "                   \"value\": {\n" +
                    "                     \"Note1\": \"PRIVATE\",\n" +
                    "                     \"Note2\": \"RESTRICTED\"\n" +
                    "                   }\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         }, \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"PRIVATE\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"classification\": \"PRIVATE\",\n" +
                    "                   \"value\": {\n" +
                    "                     \"Note1\": \"RESTRICTED\",\n" +
                    "                     \"Note2\": \"PRIVATE\"\n" +
                    "                   }" +
                    "               }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "       ]\n" +
                    "     }\n" +
                    "   }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("Addresses").get(0).get(ID), is(equalTo(getTextNode(FIRST_CHILD_ID)))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).has("Address"), is(false)),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").get("Note1"),
                    is(equalTo(getTextNode("someNote11")))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").has("Note2"), is(false)),

                () -> assertThat(resultNode.get("Addresses").get(1).get(ID),
                    is(equalTo(getTextNode(SECOND_CHILD_ID)))),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Address"),
                    is(equalTo(getTextNode("address2")))),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Notes").has("Note1"), is(false)),
                () -> assertThat(resultNode.get("Addresses").get(1).get(VALUE).get("Notes").get("Note2"),
                    is(equalTo(getTextNode("someNote22"))))
            );
        }

        @Test
        // TODO Target implementation, see RDM-1204
        @DisplayName("should filter out collection item if no item classification exists")
        void shouldFilterFieldOutCollectionItemIfNoItemClassificationExists() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote11\",\n" +
                    "                   \"Note2\": \"someNote21\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address2\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote12\",\n" +
                    "                   \"Note2\": \"someNote22\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":{  \n" +
                    "       \"classification\": \"PRIVATE\",\n" +
                    "       \"value\": [\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"RESTRICTED\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"classification\": \"PRIVATE\",\n" +
                    "                   \"value\": {\n" +
                    "                     \"Note1\": \"PRIVATE\",\n" +
                    "                     \"Note2\": \"RESTRICTED\"\n" +
                    "                   }\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         } \n" +
                    "       ]\n" +
                    "     }\n" +
                    "   }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("Addresses").size(), is(equalTo(1))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(ID), is(equalTo(getTextNode(FIRST_CHILD_ID)))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).has("Address"), is(false)),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").get("Note1"),
                    is(equalTo(getTextNode("someNote11")))),
                () -> assertThat(resultNode.get("Addresses").get(0).get(VALUE).get("Notes").has("Note2"), is(false))
            );
        }

        @Test
        @DisplayName("should filter out simple collection items with missing classification")
        void shouldFilterOutCollectionItemsWithMissingValue() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\": \"Address1\",\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\": \"Address2\",\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\": {  \n" +
                    "         \"classification\": \"PRIVATE\", \n" +
                    "         \"value\": [  \n" +
                    "           {  \n" +
                    "             \"classification\": \"PRIVATE\",\n" +
                    "             \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "           },\n" +
                    "           {  \n" +
                    "             \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "           }\n" +
                    "         ]\n" +
                    "      }\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());

            final JsonNode addresses = resultNode.get("Addresses");
            assertThat(addresses, is(notNullValue()));
            assertAll(
                () -> assertThat(addresses.size(), equalTo(1)),
                () -> assertThat(addresses.get(0).get(ID),
                    is(equalTo(jsonNodeFactory.textNode(FIRST_CHILD_ID)))),
                () -> assertThat(addresses.get(0).get(VALUE),
                    equalTo(jsonNodeFactory.textNode("Address1")))
            );
        }

        @Test
        // TODO Target implementation, see RDM-1204
        @DisplayName("should filter out collection itself when all collection items including collection have higher "
            + "classification")
        void shouldFilterOutAllFieldsForCollectionWhenAllItemsIncludingCollectionHaveHigherClassification()
                                                                                                throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote11\",\n" +
                    "                   \"Note2\": \"someNote21\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Address\":\"address1\",\n" +
                    "               \"Notes\": {\n" +
                    "                   \"Note1\": \"someNote21\",\n" +
                    "                   \"Note2\": \"someNote22\"\n" +
                    "                }" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ]\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Addresses\": {  \n" +
                    "         \"classification\": \"RESTRICTED\", \n" +
                    "         \"value\": [  \n" +
                    "             {  \n" +
                    "                \"value\":{  \n" +
                    "                   \"Address\":\"RESTRICTED\",\n" +
                    "                   \"Notes\": {\n" +
                    "                       \"classification\": \"RESTRICTED\", \n" +
                    "                       \"value\": {\n" +
                    "                           \"Note1\": \"RESTRICTED\",\n" +
                    "                           \"Note2\": \"RESTRICTED\"\n" +
                    "                       }\n" +
                    "                    }\n" +
                    "                },\n" +
                    "                \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "             },\n" +
                    "             {  \n" +
                    "                \"value\":{  \n" +
                    "                   \"Address\":\"RESTRICTED\",\n" +
                    "                   \"Notes\": {\n" +
                    "                       \"classification\": \"RESTRICTED\", \n" +
                    "                       \"value\": {\n" +
                    "                           \"Note1\": \"RESTRICTED\",\n" +
                    "                           \"Note2\": \"RESTRICTED\"\n" +
                    "                       }\n" +
                    "                    }\n" +
                    "                },\n" +
                    "                \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "             }\n" +
                    "         ]\n" +
                    "      }\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Addresses"), is(false))
            );
        }

        @Test
        @DisplayName("should filter fields for case with all types combined")
        void shouldFilterFieldsForCaseWithAllTypesCombined() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{ \"Det3\":\"Yes\",\n" +
                    "      \"SpecialIssueDescription\":\"test description\",\n" +
                    "      \"ChildrenDet\":[  \n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Name\":\"First Childs Name\",\n" +
                    "               \"BirthDate\":\"1988-12-13Z\",\n" +
                    "               \"Gender\":\"Male\",\n" +
                    "               \"ApplicantRelation\":\"Son\",\n" +
                    "               \"RespondentRelation\":\"relation 1\"\n" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "         },\n" +
                    "         {  \n" +
                    "            \"value\":{  \n" +
                    "               \"Name\":\"Second Childs Name\",\n" +
                    "               \"BirthDate\":\"1982-11-05Z\",\n" +
                    "               \"Gender\":\"Female\",\n" +
                    "               \"ApplicantRelation\":\"Daughter\",\n" +
                    "               \"RespondentRelation\":\"relation 2\"\n" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "         }\n" +
                    "      ],\n" +
                    "      \"PrimaryApplicantFullName\":{  \n" +
                    "         \"Title\":\"fdsfads\",\n" +
                    "         \"FirstName\":\"rewrew\",\n" +
                    "         \"MiddleName\":\"treter\",\n" +
                    "         \"LastName\":\"rewrwe\",\n" +
                    "         \"DateOfBirth\":\"1955-12-12Z\",\n" +
                    "         \"NationalInsuranceNumber\":\"12312fdfdsfds\"\n" +
                    "      },\n" +
                    "      \"ChildRiskReason1\":\"No\",\n" +
                    "      \"ChildRiskReason2\":\"Yes\",\n" +
                    "      \"OrderType\":[  \n" +
                    "         \"ChildOrder\",\n" +
                    "         \"SpecialIssueOrder\"\n" +
                    "      ]}\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{ \"Det3\":\"PUBLIC\",\n" +
                    "      \"SpecialIssueDescription\":\"PRIVATE\",\n" +
                    "      \"ChildrenDet\": {  \n" +
                    "        \"classification\": \"PRIVATE\",  \n" +
                    "        \"value\": [  \n" +
                    "          { \"value\":{  \n" +
                    "               \"Name\":\"PRIVATE\",\n" +
                    "               \"BirthDate\":\"RESTRICTED\",\n" +
                    "               \"Gender\":\"PUBLIC\",\n" +
                    "               \"ApplicantRelation\":\"RESTRICTED\",\n" +
                    "               \"RespondentRelation\":\"RESTRICTED\"\n" +
                    "            },\n" +
                    "            \"id\":\"" + FIRST_CHILD_ID + "\"\n" +
                    "          },\n" +
                    "          { \"value\":{  \n" +
                    "               \"Name\":\"PUBLIC\",\n" +
                    "               \"BirthDate\":\"PRIVATE\",\n" +
                    "               \"Gender\":\"PUBLIC\",\n" +
                    "               \"ApplicantRelation\":\"PUBLIC\",\n" +
                    "               \"RespondentRelation\":\"PUBLIC\"\n" +
                    "            },\n" +
                    "            \"id\":\"" + SECOND_CHILD_ID + "\"\n" +
                    "          }\n" +
                    "        ]\n" +
                    "      },\n" +
                    "      \"PrimaryApplicantFullName\":{  \n" +
                    "        \"classification\": \"PRIVATE\",  \n" +
                    "        \"value\": {  \n" +
                    "          \"Title\":\"PUBLIC\",\n" +
                    "          \"FirstName\":\"PUBLIC\",\n" +
                    "          \"MiddleName\":\"PUBLIC\",\n" +
                    "          \"LastName\":\"PUBLIC\",\n" +
                    "          \"DateOfBirth\":\"PUBLIC\",\n" +
                    "          \"NationalInsuranceNumber\":\"PUBLIC\"\n" +
                    "        }\n" +
                    "      },\n" +
                    "      \"ChildRiskReason1\":\"PUBLIC\",\n" +
                    "      \"ChildRiskReason2\":\"PUBLIC\",\n" +
                    "      \"OrderType\":\"RESTRICTED\"}\n"
            ));
            caseDetails.setDataClassification(dataClassification);

            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.get("Det3"), is(equalTo(getTextNode("Yes")))),
                () -> assertThat(resultNode.get("SpecialIssueDescription"),
                    is(equalTo(getTextNode("test description")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(0).get(ID),
                    is(equalTo(getTextNode(FIRST_CHILD_ID)))),
                () -> assertThat(resultNode.get("ChildrenDet").get(0).get(VALUE).get("Name"),
                    is(equalTo(getTextNode("First Childs Name")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(0).get(VALUE).has("BirthDate"), is(false)),
                () -> assertThat(resultNode.get("ChildrenDet").get(0).get(VALUE).get("Gender"),
                    is(equalTo(getTextNode("Male")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(0).get(VALUE).has("ApplicantRelation"), is(false)),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(ID),
                    is(equalTo(getTextNode(SECOND_CHILD_ID)))),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(VALUE).get("Name"),
                    is(equalTo(getTextNode("Second Childs Name")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(VALUE).get("BirthDate"),
                    is(equalTo(getTextNode("1982-11-05Z")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(VALUE).get("Gender"),
                    is(equalTo(getTextNode("Female")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(VALUE).get("ApplicantRelation"),
                    is(equalTo(getTextNode("Daughter")))),
                () -> assertThat(resultNode.get("ChildrenDet").get(1).get(VALUE).get("RespondentRelation"),
                    is(equalTo(getTextNode("relation 2")))),
                () -> assertThat(resultNode.get("Det3"), is(equalTo(getTextNode("Yes")))),
                () -> assertThat(resultNode.get("ChildRiskReason1"), is(equalTo(getTextNode("No")))),
                () -> assertThat(resultNode.get("ChildRiskReason2"), is(equalTo(getTextNode("Yes")))),
                () -> assertThat(resultNode.has("OrderType"), is(false))
            );
        }

        @Test
        @DisplayName("should return empty details if null details passed in")
        void shouldReturnEmptyDetailsIfNullDetailsPassedIn() throws IOException {
            final Map<String, JsonNode> data = null;
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.nullNode())));
        }

        @Test
        @DisplayName("should return empty details if empty details passed in")
        void shouldReturnEmptyDetailsIfEmptyDetailsPassedIn() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree("{}"));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should return empty details if null classification details passed in")
        void shouldReturnEmptyDetailsIfNullClassificationDetailsPassedIn() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": true\n," +
                    "       \"Note2\": false\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = null;
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should return empty details if empty classification details passed in")
        void shouldReturnEmptyDetailsIfEmptyPassedIn() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": true\n," +
                    "       \"Note2\": false\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree("{}"));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertThat(resultNode, is(equalTo(jsonNodeFactory.objectNode())));
        }

        @Test
        @DisplayName("should filter out number fields with higher classification")
        void shouldFilterOutNumberFieldsWithHigherClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": 42\n," +
                    "       \"Note2\": 56\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Note1"), is(false)),
                () -> assertThat(resultNode.get("Note2"), is(equalTo(jsonNodeFactory.numberNode(56))))
            );
        }

        @Test
        @DisplayName("should filter out boolean fields with higher classification")
        void shouldFilterOutBooleanFieldsWithHigherClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": true\n," +
                    "       \"Note2\": false\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Note1\": \"RESTRICTED\"\n," +
                    "       \"Note2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Note1"), is(false)),
                () -> assertThat(resultNode.get("Note2"), is(equalTo(jsonNodeFactory.booleanNode(false))))
            );
        }

        @Test
        @DisplayName("should filter out document fields with higher classification")
        void shouldFilterOutDocumentFieldsWithHigherClassification() throws IOException {
            final Map<String, JsonNode> data = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Document1\": { " +
                    "           \"document_url\": \"https://em/doc1\"," +
                    "           \"document_binary_url\": \"https://em/doc1/bin.pdf\"," +
                    "           \"document_filename\": \"Document 1\"" +
                    "       }\n," +
                    "       \"Document2\": {" +
                    "           \"document_url\": \"https://em/doc2\"," +
                    "           \"document_binary_url\": \"https://em/doc2/bin.pdf\"," +
                    "           \"document_filename\": \"Document 2\"" +
                    "       }\n" +
                    "    }\n"
            ));
            caseDetails.setData(data);
            final Map<String, JsonNode> dataClassification = JacksonUtils.convertValue(MAPPER.readTree(
                "{  \"Document1\": \"RESTRICTED\"\n," +
                    "       \"Document2\": \"PUBLIC\"\n" +
                    "    }\n"
            ));
            caseDetails.setDataClassification(dataClassification);


            CaseDetails caseDetails = applyClassification(PRIVATE);

            JsonNode resultNode = JacksonUtils.convertValueJsonNode(caseDetails.getData());
            assertAll(
                () -> assertThat(resultNode.has("Document1"), is(false)),
                () -> assertThat(resultNode.get("Document2").get("document_url"),
                    is(equalTo(jsonNodeFactory.textNode("https://em/doc2")))),
                () -> assertThat(resultNode.get("Document2").get("document_binary_url"),
                    is(equalTo(jsonNodeFactory.textNode("https://em/doc2/bin.pdf")))),
                () -> assertThat(resultNode.get("Document2").get("document_filename"),
                    is(equalTo(jsonNodeFactory.textNode("Document 2"))))
            );
        }
    }

    private JsonNode getTextNode(String value) {
        return JSON_NODE_FACTORY.textNode(value);
    }
}
