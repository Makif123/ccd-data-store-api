package uk.gov.hmcts.ccd.data.casedetails.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.hmcts.ccd.data.casedetails.search.MetaData;
import uk.gov.hmcts.ccd.domain.model.definition.CaseState;
import uk.gov.hmcts.ccd.domain.service.common.AuthorisedCaseDefinitionDataService;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.ccd.domain.service.common.AccessControlService.CAN_READ;

class CaseStateAuthorisationSecurityTest {

    @Mock
    private AuthorisedCaseDefinitionDataService authorisedCaseDefinitionDataService;
    @Mock
    private CaseDetailsQueryBuilder<Long> builder;

    @InjectMocks
    private CaseStateAuthorisationSecurity caseStateAuthorisationSecurity;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Nested
    @DisplayName("should secure with authorised case states")
    class Secure {

        @Test
        @DisplayName("should secure the builder query with user authorised list of case states")
        void shouldSecureWithAuthorisedCaseStates() {
            MetaData metaData = new MetaData("CaseType", "Jurisdiction");
            CaseState caseState1 = new CaseState();
            caseState1.setId("state1");
            CaseState caseState2 = new CaseState();
            caseState2.setId("state2");
            List<CaseState> caseStates = asList(caseState1, caseState2);
            when(authorisedCaseDefinitionDataService.getUserAuthorisedCaseStates(metaData.getJurisdiction(), metaData.getCaseTypeId(), CAN_READ)).thenReturn(
                caseStates);

            caseStateAuthorisationSecurity.secure(builder, metaData);

            assertAll(
                () -> verify(authorisedCaseDefinitionDataService).getUserAuthorisedCaseStates(metaData.getJurisdiction(), metaData.getCaseTypeId(), CAN_READ),
                () -> verify(builder).whereStates(caseStates.stream().map(CaseState::getId).collect(Collectors.toList())));
        }
    }
}
