package uk.gov.hmcts.ccd.domain.service.search.global;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import uk.gov.hmcts.ccd.config.JacksonUtils;
import uk.gov.hmcts.ccd.domain.model.search.global.GlobalSearchRequestPayload;
import uk.gov.hmcts.ccd.domain.model.search.global.GlobalSearchSortByCategory;
import uk.gov.hmcts.ccd.domain.model.search.global.GlobalSearchSortDirection;
import uk.gov.hmcts.ccd.domain.model.search.global.Party;
import uk.gov.hmcts.ccd.domain.model.search.global.SearchCriteria;
import uk.gov.hmcts.ccd.domain.model.search.global.SortCriteria;

import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CASE_TYPE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.BASE_LOCATION;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.CASE_MANAGEMENT_CATEGORY_ID;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.CASE_MANAGEMENT_CATEGORY_NAME;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.CASE_MANAGEMENT_LOCATION;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.CASE_NAME_HMCTS_INTERNAL;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.OTHER_REFERENCE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.OTHER_REFERENCE_VALUE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.REGION;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTIES;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_ADDRESS_LINE_1;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_DATE_OF_BIRTH;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_DATE_OF_DEATH;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_EMAIL_ADDRESS;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_NAME;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.CaseDataPaths.SEARCH_PARTY_POSTCODE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.JURISDICTION;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.REFERENCE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.STATE;
import static uk.gov.hmcts.ccd.domain.service.search.global.GlobalSearchFields.SupplementaryDataFields.SERVICE_ID;

@Named
public class GlobalSearchQueryBuilder {

    static final String STANDARD_ANALYZER = "standard";
    private int numberOfWildcardFields;

    public QueryBuilder globalSearchQuery(GlobalSearchRequestPayload request) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        numberOfWildcardFields = 0;

        if (request != null) {
            SearchCriteria searchCriteria = request.getSearchCriteria();
            if (searchCriteria != null) {

                checkForWildcardValues(boolQueryBuilder, REFERENCE, searchCriteria.getCaseReferences());
                // add terms queries for properties that must match 1 from many
                addTermsQuery(boolQueryBuilder, CASE_TYPE, searchCriteria.getCcdCaseTypeIds());
                addTermsQuery(boolQueryBuilder, JURISDICTION, searchCriteria.getCcdJurisdictionIds());
                addTermsQuery(boolQueryBuilder, STATE, searchCriteria.getStateIds());
                addTermsQuery(boolQueryBuilder, REGION, searchCriteria.getCaseManagementRegionIds());
                addTermsQuery(boolQueryBuilder, BASE_LOCATION, searchCriteria.getCaseManagementBaseLocationIds());
                checkForWildcardValues(boolQueryBuilder, OTHER_REFERENCE_VALUE, searchCriteria.getOtherReferences());
                // add parties query for all party values
                addPartiesQuery(boolQueryBuilder, searchCriteria.getParties());
                boolQueryBuilder.minimumShouldMatch(numberOfWildcardFields);
            }
        }

        return boolQueryBuilder;
    }

    public void checkForWildcardValues(BoolQueryBuilder boolQueryBuilder, String field, List<String> values) {
        if (values != null) {
            List<String> nonWildcardValues = new ArrayList<>();
            for (String str : values) {
                if (str.contains("*") || str.contains("?")) {
                    boolQueryBuilder.should(QueryBuilders.wildcardQuery(field, str));
                } else {
                    nonWildcardValues.add(str);
                }
            }
            if (values.size() > nonWildcardValues.size()) {
                numberOfWildcardFields++;
            }
            if(nonWildcardValues.size() != 0) {
                boolQueryBuilder.should(
                    QueryBuilders.termsQuery(field, nonWildcardValues.stream().map(String::toLowerCase).collect(Collectors.toList())));
            }

        }
    }

    public List<FieldSortBuilder> globalSearchSort(GlobalSearchRequestPayload request) {
        List<FieldSortBuilder> sortBuilders = Lists.newArrayList();

        if (request != null && request.getSortCriteria() != null) {
            request.getSortCriteria().forEach(sortCriteria -> createSort(sortCriteria).ifPresent(sortBuilders::add));
        }

        return sortBuilders;
    }

    /**
     * Get list of all case data fields to return from search.
     */
    public ArrayNode globalSearchSourceFields() {
        return JacksonUtils.MAPPER.createArrayNode()
            // root level case data fields
            .add(CASE_MANAGEMENT_CATEGORY_ID)
            .add(CASE_MANAGEMENT_CATEGORY_NAME)
            .add(CASE_MANAGEMENT_LOCATION)
            .add(CASE_NAME_HMCTS_INTERNAL)
            // remaining case data fields
            .add(OTHER_REFERENCE);
    }

    /**
     * Get list of all SupplementaryData fields to return from search.
     */
    public ArrayNode globalSearchSupplementaryDataFields() {
        return JacksonUtils.MAPPER.createArrayNode()
            .add(SERVICE_ID);
    }

    private void addTermsQuery(BoolQueryBuilder boolQueryBuilder, String term, List<String> values) {
        if (CollectionUtils.isNotEmpty(values)) {
            boolQueryBuilder.must(
                // NB: switch to lower case for terms query
                QueryBuilders.termsQuery(term, values.stream().map(String::toLowerCase).collect(Collectors.toList()))
            );
        }
    }

    private void addPartiesQuery(BoolQueryBuilder boolQueryBuilder, List<Party> parties) {
        if (CollectionUtils.isNotEmpty(parties)) {
            // NB: the generated ShouldQuery, which will contain all the party specific queries, will be wrapped in a
            //     single BoolQuery and added to the list of 'must' queries (i.e. alongside the other term based
            //     queries) rather than added directly to the main query.  This will allow any future additional
            //     SearchCriteria object based queries to be added in a similar fashion without impacting this query.
            BoolQueryBuilder partiesQueryBuilder = QueryBuilders.boolQuery();

            parties.forEach(party -> createPartyQuery(party).ifPresent(partiesQueryBuilder::should));

            // if found any party queries:: complete the parties query and attach to main query.
            if (!partiesQueryBuilder.should().isEmpty()) {
                partiesQueryBuilder.minimumShouldMatch(1);
                boolQueryBuilder.must(partiesQueryBuilder);
            }
        }
    }

    private Optional<QueryBuilder> createPartyQuery(Party party) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        if (party != null) {
            if (StringUtils.isNotBlank(party.getPartyName())) {
                String name = party.getPartyName();
                if (name.contains("*") || name.contains("?")) {
                    boolQueryBuilder.must(QueryBuilders.wildcardQuery(SEARCH_PARTY_NAME + ".raw", name));
                } else {
                    boolQueryBuilder.must(
                        QueryBuilders.matchPhraseQuery(SEARCH_PARTY_NAME, name)
                            .analyzer(STANDARD_ANALYZER)
                    );
                }
            }
            if (StringUtils.isNotBlank(party.getEmailAddress())) {
                boolQueryBuilder.must(
                    QueryBuilders.matchPhraseQuery(SEARCH_PARTY_EMAIL_ADDRESS, party.getEmailAddress())
                        .analyzer(STANDARD_ANALYZER)
                );
            }
            if (StringUtils.isNotBlank(party.getAddressLine1())) {
                String address = party.getAddressLine1();
                if (address.contains("*") || address.contains("?")) {
                    boolQueryBuilder.must(QueryBuilders.wildcardQuery(SEARCH_PARTY_ADDRESS_LINE_1 + ".raw", address));
                } else {
                    boolQueryBuilder.must(
                        QueryBuilders.matchPhraseQuery(SEARCH_PARTY_ADDRESS_LINE_1, address)
                            .analyzer(STANDARD_ANALYZER)
                    );
                }
            }
            if (StringUtils.isNotBlank(party.getPostCode())) {
                boolQueryBuilder.must(
                    QueryBuilders.matchPhraseQuery(SEARCH_PARTY_POSTCODE, party.getPostCode())
                        .analyzer(STANDARD_ANALYZER)
                );
            }
            if (StringUtils.isNotBlank(party.getDateOfBirth())) {
                boolQueryBuilder.must(QueryBuilders.rangeQuery(SEARCH_PARTY_DATE_OF_BIRTH)
                    .gte(party.getDateOfBirth())
                    .lte(party.getDateOfBirth())
                );
            }
            if (StringUtils.isNotBlank(party.getDateOfDeath())) {
                boolQueryBuilder.must(QueryBuilders.rangeQuery(SEARCH_PARTY_DATE_OF_DEATH)
                    .gte(party.getDateOfDeath())
                    .lte(party.getDateOfDeath())
                );
            }
        }

        if (boolQueryBuilder.must().isEmpty()) {
            return Optional.empty();
        }

        // NB: uses nested query so multiple property search is against a single party object
        return Optional.of(QueryBuilders.nestedQuery(SEARCH_PARTIES, boolQueryBuilder, ScoreMode.Total));
    }

    private Optional<FieldSortBuilder> createSort(SortCriteria sortCriteria) {

        if (sortCriteria != null && StringUtils.isNotBlank(sortCriteria.getSortBy())) {
            GlobalSearchSortByCategory sortByCategory = GlobalSearchSortByCategory.getEnum(sortCriteria.getSortBy());

            if (sortByCategory != null) {
                SortOrder sortOrder =
                    GlobalSearchSortDirection.DESCENDING.name().equalsIgnoreCase(sortCriteria.getSortDirection())
                        ? SortOrder.DESC : SortOrder.ASC;

                return Optional.of(SortBuilders
                    .fieldSort(sortByCategory.getField())
                    .order(sortOrder));
            }
        }

        return Optional.empty();
    }

}
