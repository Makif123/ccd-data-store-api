package uk.gov.hmcts.ccd.endpoint.exceptions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.collect.Lists;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;
import feign.FeignException;
import feign.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import uk.gov.hmcts.ccd.appinsights.AppInsights;
import uk.gov.hmcts.ccd.domain.model.common.HttpError;
import uk.gov.hmcts.ccd.domain.model.std.CaseFieldValidationError;
import uk.gov.hmcts.ccd.domain.service.aggregated.GetUserProfileOperation;
import uk.gov.hmcts.ccd.domain.service.common.AccessControlService;
import uk.gov.hmcts.ccd.endpoint.ui.UserProfileEndpoint;

import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext  // required for Jenkins agent
@AutoConfigureWireMock(port = 0)
public class RestExceptionHandlerTest {

    // url to trigger chosen test controller
    private static final String TEST_URL = "/caseworkers/123/profile";

    private static final String SQL_EXCEPTION_MESSAGE =
        "SQL Exception thrown during API operation";
    private static final String ERROR_RESPONSE_BODY =
        "{\"errorMessage\":\"SQL Exception thrown during API operation\"}";
    // service used by chosen test controller which we will use to throw exceptions
    @Mock
    private GetUserProfileOperation mockService;

    private MockMvc mockMvc;

    @Mock
    private AppInsights appInsights;

    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @Captor
    private ArgumentCaptor<Map<String, String>> customPropertiesCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        logger = (Logger) LoggerFactory.getLogger(RestExceptionHandler.class);

        // create and start a ListAppender
        listAppender = new ListAppender<>();
        listAppender.start();

        // add the appender to the logger
        logger.addAppender(listAppender);

        // only create classUnderTest after logger configuration
        RestExceptionHandler classUnderTest = new RestExceptionHandler(appInsights);

        // any controller will do as these tests will force it to error
        final UserProfileEndpoint controller = new UserProfileEndpoint(mockService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(classUnderTest)
            .build();
    }

    @After
    public void tearDown() {
        listAppender.stop();
        logger.detachAppender(listAppender);
    }

    @Test
    public void handleException_shouldReturnHttpErrorResponse() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";
        // any runtime exception (that is not an ApiException)
        ArrayIndexOutOfBoundsException expectedException =
            new ArrayIndexOutOfBoundsException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleHttpServerErrorException_shouldReturnBadGatewayWhenReceivedInternalServerError() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage,
            createHttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, myUniqueExceptionMessage));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.BAD_GATEWAY);
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleHttpServerErrorException_shouldReturnGatewayTimeoutWhenReceivedGatewayTimeout() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException serviceException = new ServiceException(myUniqueExceptionMessage,
            createHttpServerErrorException(HttpStatus.GATEWAY_TIMEOUT, myUniqueExceptionMessage));

        setupMockServiceToThrowException(serviceException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, serviceException, HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleHttpClientErrorException_shouldReturnInternalServerErrorWhenReceivedBadRequest() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage, HttpClientErrorException
            .create(HttpStatus.BAD_REQUEST, myUniqueExceptionMessage, HttpHeaders.EMPTY,
                new byte[0], Charset.defaultCharset()));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void handleHttpClientErrorException_shouldReturnUnauthorizedWhenReceivedUnauthorized() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage, HttpClientErrorException
            .create(HttpStatus.UNAUTHORIZED, myUniqueExceptionMessage, HttpHeaders.EMPTY,
                new byte[0], Charset.defaultCharset()));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void handleFeignServerException_shouldReturnBadGatewayWhenReceivedInternalServerError() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage,
            createFeignServerException(HttpStatus.INTERNAL_SERVER_ERROR, myUniqueExceptionMessage));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.BAD_GATEWAY);
    }

    @Test
    public void handleFeignServerException_shouldReturnGatewayTimeoutWhenReceivedGatewayTimeout() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException serviceException = new ServiceException(myUniqueExceptionMessage,
            createFeignServerException(HttpStatus.GATEWAY_TIMEOUT, myUniqueExceptionMessage));

        setupMockServiceToThrowException(serviceException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, serviceException, HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    public void handleFeignClientException_shouldReturnInternalServerErrorWhenReceivedBadRequest() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage,
            new FeignException.FeignClientException(HttpStatus.BAD_REQUEST.value(), myUniqueExceptionMessage,
                Request.create(Request.HttpMethod.GET, myUniqueExceptionMessage, Map.of(), new byte[0],
                    Charset.defaultCharset(), null), new byte[0]));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void handleFeignClientException_shouldReturnUnauthorizedWhenReceivedUnauthorized() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        ServiceException expectedException = new ServiceException(myUniqueExceptionMessage,
            new FeignException.FeignClientException(HttpStatus.UNAUTHORIZED.value(), myUniqueExceptionMessage,
                Request.create(Request.HttpMethod.GET, myUniqueExceptionMessage, Map.of(), new byte[0],
                    Charset.defaultCharset(), null), new byte[0]));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void handleDirectFeignClientException_shouldReturnUnauthorizedWhenReceivedUnauthorized() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        FeignException.FeignClientException expectedException =
            new FeignException.FeignClientException(HttpStatus.UNAUTHORIZED.value(), myUniqueExceptionMessage,
                Request.create(Request.HttpMethod.GET, myUniqueExceptionMessage, Map.of(), new byte[0],
                    Charset.defaultCharset(), null), new byte[0]);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void handleDirectFeignClientException_shouldReturnInternalServerErrorWhenReceivedBadRequest()
        throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 1";

        FeignException.FeignClientException expectedException =
            new FeignException.FeignClientException(HttpStatus.BAD_REQUEST.value(), myUniqueExceptionMessage,
                Request.create(Request.HttpMethod.GET, myUniqueExceptionMessage, Map.of(), new byte[0],
                    Charset.defaultCharset(), null), new byte[0]);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void handleException_shouldLogExceptionAsError() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic runtime exception message 2";
        // any runtime exception (that is not an ApiException)
        ArrayIndexOutOfBoundsException expectedException =
            new ArrayIndexOutOfBoundsException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        List<ILoggingEvent> logsList = listAppender.list;
        ILoggingEvent lastLogEntry = logsList.get(logsList.size() - 1);
        assertThat(lastLogEntry.getLevel(), is(equalTo(Level.ERROR)));
        assertThat(lastLogEntry.getMessage(), containsString(myUniqueExceptionMessage));
    }

    @Test
    public void handleException_shouldTrackExceptionToAppInsights() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique generic exception message 3";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        verify(appInsights, times(1)).trackException(expectedException);
    }

    @Test
    public void handleApiException_shouldReturnHttpErrorResponse() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 1";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
    }

    @Test
    public void handleApiException_shouldReturnHttpErrorResponse_withDetails() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 1.1";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);
        expectedException.withDetails("test details");

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
        assertExtraApiExceptionResponseProperties(result, expectedException);
        result.andExpect(jsonPath("$.details").exists());
    }

    @Test
    public void handleApiException_shouldReturnHttpErrorResponse_withCallbackErrors() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 1.2";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);
        expectedException.withErrors(Lists.newArrayList("Error 1", "Error 2"));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
        assertExtraApiExceptionResponseProperties(result, expectedException);
        result.andExpect(jsonPath("$.callbackErrors[*]", hasSize(2)));
    }

    @Test
    public void handleApiException_shouldReturnHttpErrorResponse_withCallbackWarnings() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 1.3";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);
        expectedException.withWarnings(Lists.newArrayList("Warning 1", "Warning 2"));

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
        assertExtraApiExceptionResponseProperties(result, expectedException);
        result.andExpect(jsonPath("$.callbackWarnings[*]", hasSize(2)));
    }

    @Test
    public void handleApiException_shouldLogExceptionAsError() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 2";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        List<ILoggingEvent> logsList = listAppender.list;
        ILoggingEvent lastLogEntry = logsList.get(logsList.size() - 1);
        assertThat(lastLogEntry.getLevel(), is(equalTo(Level.ERROR)));
        assertThat(lastLogEntry.getMessage(), containsString(myUniqueExceptionMessage));
    }

    @Test
    public void handleApiException_shouldTrackExceptionToAppInsights() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique API exception message 3";
        ApiException expectedException = new ApiException(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        verify(appInsights, times(1)).trackException(expectedException);
    }

    @Test
    public void handleSearchRequestException_shouldReturnHttpErrorResponse() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique BadSearchRequest exception message 1";
        BadSearchRequest expectedException = new BadSearchRequest(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
    }

    @Test
    public void handleSearchRequestException_shouldLogExceptionAsWarning() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique BadSearchRequest exception message 2";
        BadSearchRequest expectedException = new BadSearchRequest(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        List<ILoggingEvent> logsList = listAppender.list;
        ILoggingEvent lastLogEntry = logsList.get(logsList.size() - 1);
        assertThat(lastLogEntry.getLevel(), is(equalTo(Level.WARN)));
        assertThat(lastLogEntry.getMessage(), containsString(myUniqueExceptionMessage));
    }

    @Test
    public void handleSearchRequestException_shouldTrackExceptionToAppInsights() throws Exception {

        // ARRANGE
        String myUniqueExceptionMessage = "My unique BadSearchRequest exception message 3";
        BadSearchRequest expectedException = new BadSearchRequest(myUniqueExceptionMessage);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        verify(appInsights, times(1)).trackException(expectedException);
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleCaseValidationException_shouldReturnHttpErrorResponse_withFieldErrors_returnsWithDetails_includeFieldNamesAndMessages() throws Exception {

        // ARRANGE
        List<CaseFieldValidationError> fieldErrors = createFieldErrors();
        CaseValidationException expectedException = new CaseValidationException(fieldErrors);

        setupMockServiceToThrowException(expectedException);

        // ACT
        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        assertHttpErrorResponse(result, expectedException);
        assertExtraApiExceptionResponseProperties(result, expectedException);
        result.andExpect(jsonPath("$.details.field_errors[*]", hasSize(2)));
        result.andExpect(jsonPath("$.details.field_errors[0].id").value(fieldErrors.get(0).getId()));
        result.andExpect(jsonPath("$.details.field_errors[0].message").value(fieldErrors.get(0).getMessage()));
        result.andExpect(jsonPath("$.details.field_errors[1].id").value(fieldErrors.get(1).getId()));
        result.andExpect(jsonPath("$.details.field_errors[1].message").value(fieldErrors.get(1).getMessage()));
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleCaseValidationException_shouldLogExceptionAsWarning_withFieldErrors_includeFieldNamesButNotMessages() throws Exception {

        // ARRANGE
        List<CaseFieldValidationError> fieldErrors = createFieldErrors();
        CaseValidationException expectedException = new CaseValidationException(fieldErrors);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        List<ILoggingEvent> logsList = listAppender.list;
        ILoggingEvent lastLogEntry = logsList.get(logsList.size() - 1);
        assertThat(lastLogEntry.getLevel(), is(equalTo(Level.WARN)));
        assertThat(lastLogEntry.getFormattedMessage(), containsString(expectedException.getMessage()));
        // check logging field names
        assertThat(lastLogEntry.getFormattedMessage(), containsString(fieldErrors.get(0).getId()));
        assertThat(lastLogEntry.getFormattedMessage(), containsString(fieldErrors.get(1).getId()));
        // check not logging validation messages
        assertThat(lastLogEntry.getFormattedMessage(), not(containsString(fieldErrors.get(0).getMessage())));
        assertThat(lastLogEntry.getFormattedMessage(), not(containsString(fieldErrors.get(1).getMessage())));
    }

    @Test
    @SuppressWarnings("checkstyle:LineLength") // don't want to break long method name
    public void handleCaseValidationException_shouldTrackExceptionToAppInsightsAsWarning_withFieldErrors_includeFieldNamesButNotMessages() throws Exception {

        // ARRANGE
        List<CaseFieldValidationError> fieldErrors = createFieldErrors();
        CaseValidationException expectedException = new CaseValidationException(fieldErrors);

        setupMockServiceToThrowException(expectedException);

        // ACT
        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        verify(appInsights, times(1)).trackException(eq(expectedException), customPropertiesCaptor.capture(),
            eq(SeverityLevel.Warning));
        // check logging field names
        Map<String, String> trackedCustomProperties = customPropertiesCaptor.getValue();
        String trackedFieldInfo = trackedCustomProperties.get("CaseValidationError field IDs");
        // check logging field names
        assertThat(trackedFieldInfo, containsString(fieldErrors.get(0).getId()));
        assertThat(trackedFieldInfo, containsString(fieldErrors.get(1).getId()));
        // check not logging validation messages
        assertThat(trackedFieldInfo, not(containsString(fieldErrors.get(0).getMessage())));
        assertThat(trackedFieldInfo, not(containsString(fieldErrors.get(1).getMessage())));
    }

    @Test
    public void handleSQLException_shouldReturnHttpErrorResponse() throws Exception {
        doAnswer(
            apiCall -> {
                throw new SQLException();
            })
            .when(mockService)
            .execute(AccessControlService.CAN_READ);

        ResultActions result = mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        result.andExpect(status().isInternalServerError());
        result.andExpect(content()
            .string(ERROR_RESPONSE_BODY));
    }

    @Test
    public void handleSQLException_shouldLogExceptionAsWarning() throws Exception {
        doAnswer(
            apiCall -> {
                throw new SQLException();
            })
            .when(mockService)
            .execute(AccessControlService.CAN_READ);

        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        // ASSERT
        List<ILoggingEvent> logsList = listAppender.list;
        ILoggingEvent lastLogEntry = logsList.get(logsList.size() - 1);
        assertThat(lastLogEntry.getLevel(), is(equalTo(Level.ERROR)));
        assertThat(lastLogEntry.getMessage(),
            containsString(SQL_EXCEPTION_MESSAGE));
    }

    @Test
    public void handleSQLException_shouldTrackExceptionToAppInsights() throws Exception {
        SQLException expectedException = new SQLException();

        doAnswer(
            apiCall -> {
                throw expectedException;
            })
            .when(mockService)
            .execute(AccessControlService.CAN_READ);

        mockMvc.perform(MockMvcRequestBuilders.get(TEST_URL));

        verify(appInsights).trackException(expectedException);
    }

    private void assertHttpErrorResponse(ResultActions result, Exception expectedException) throws Exception {

        // NB: as we cannot mock HttpError generate an equivalent and compare to response
        final HttpError<Serializable> expectedError =
            new HttpError<>(expectedException, mock(HttpServletRequest.class));

        // check the very basics
        result.andExpect(status().is(expectedError.getStatus()));
        result.andExpect(jsonPath("$.exception").value(expectedException.getClass().getName()));

        // check a bit more
        result.andExpect(jsonPath("$.message").value(expectedException.getMessage()));
    }

    private void assertHttpErrorResponse(final ResultActions result, final Exception expectedException,
                                         final HttpStatus httpStatus) throws Exception {

        // NB: as we cannot mock HttpError generate an equivalent and compare to response
        final HttpError<Serializable> expectedError =
            new HttpError<>(httpStatus, expectedException, mock(HttpServletRequest.class));

        // check the very basics
        result.andExpect(status().is(expectedError.getStatus()));
        result.andExpect(jsonPath("$.exception").value(expectedException.getClass().getName()));

        // check a bit more
        result.andExpect(jsonPath("$.message").value(expectedException.getMessage()));
    }

    private void assertExtraApiExceptionResponseProperties(ResultActions result, ApiException expectedException)
        throws Exception {

        // load extra properties
        Serializable exceptionDetails = expectedException.getDetails();
        List<String> exceptionCallbackErrors = expectedException.getCallbackErrors();
        List<String> exceptionCallbackWarnings = expectedException.getCallbackWarnings();

        // check details
        if (exceptionDetails == null) {
            result.andExpect(jsonPath("$.details").doesNotExist());
        } else if (exceptionDetails instanceof String) {
            // simple object so can check value
            result.andExpect(jsonPath("$.details").value(exceptionDetails));
        } else {
            // complex object so just check if exists and leave finer detail to calling test method
            result.andExpect(jsonPath("$.details").exists());
        }

        // check callback errors
        if (exceptionCallbackErrors == null) {
            result.andExpect(jsonPath("$.callbackErrors").doesNotExist());
        } else {
            for (int i = 0; i < exceptionCallbackErrors.size(); i++) {
                result.andExpect(jsonPath(String.format("$.callbackErrors[%s]", i)).value(exceptionCallbackErrors
                    .get(i)));
            }
        }

        // check callback warnings
        if (exceptionCallbackWarnings == null) {
            result.andExpect(jsonPath("$.callbackWarnings").doesNotExist());
        } else {
            for (int i = 0; i < exceptionCallbackWarnings.size(); i++) {
                result.andExpect(jsonPath(String.format("$.callbackWarnings[%s]", i)).value(exceptionCallbackWarnings
                    .get(i)));
            }
        }
    }

    private List<CaseFieldValidationError> createFieldErrors() {
        List<CaseFieldValidationError> fieldErrors = new ArrayList<>();
        fieldErrors.add(new CaseFieldValidationError("field1", "validation message 1"));
        fieldErrors.add(new CaseFieldValidationError("field2", "validation message 2"));

        return fieldErrors;
    }

    private void setupMockServiceToThrowException(Exception expectedException) {
        // configure chosen mock service to throw exception when controller is run
        when(mockService.execute(AccessControlService.CAN_READ)).thenThrow(expectedException);
    }

    private HttpServerErrorException createHttpServerErrorException(final HttpStatus httpStatus,
                                                                    final String message) {
        return HttpServerErrorException
            .create(httpStatus, message, HttpHeaders.EMPTY,
                new byte[0], Charset.defaultCharset());
    }

    private FeignException.FeignServerException createFeignServerException(final HttpStatus httpStatus,
                                                                           final String message) {
        return new FeignException.FeignServerException(httpStatus.value(), message,
            Request.create(Request.HttpMethod.GET, message, Map.of(), new byte[0],
                Charset.defaultCharset(), null), new byte[0]);
    }
}
