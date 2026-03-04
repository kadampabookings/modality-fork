package one.modality.ecommerce.policy.service.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import one.modality.ecommerce.policy.service.PolicyAggregate;

/**
 * @author Bruno Salmon
 */
public final class PolicyAggregateSerialCodec extends SerialCodecBase<PolicyAggregate> {

    private static final String CODEC_ID = "PolicyAggregate";
    private static final String EVENT_QUERY_RESULT_KEY = "eqr";
    private static final String SCHEDULED_ITEMS_QUERY_RESULT_KEY = "siqr";
    private static final String SCHEDULED_BOUNDARIES_QUERY_RESULT_KEY = "sbqr";
    private static final String EVENT_PARTS_QUERY_RESULT_KEY = "epaqr";
    private static final String EVENT_SELECTIONS_QUERY_RESULT_KEY = "esqr";
    private static final String EVENT_PHASES_QUERY_RESULT_KEY = "ephqr";
    private static final String EVENT_PHASE_COVERAGES_QUERY_RESULT_KEY = "epcqr";
    private static final String RATES_QUERY_RESULT_KEY = "rqr";
    private static final String ITEM_FAMILY_POLICIES_QUERY_RESULT_KEY = "ifpqr";
    private static final String ITEM_POLICIES_QUERY_RESULT_KEY = "ipqr";
    private static final String BOOKABLE_PERIODS_QUERY_RESULT_KEY = "bpqr";


    public PolicyAggregateSerialCodec() {
        super(PolicyAggregate.class, CODEC_ID);
    }

    @Override
    public void encode(PolicyAggregate pa, AstObject serial) {
        encodeObject(serial, EVENT_QUERY_RESULT_KEY,                 pa.getEventQueryResult());
        encodeObject(serial, SCHEDULED_ITEMS_QUERY_RESULT_KEY,       pa.getScheduledItemsQueryResult());
        encodeObject(serial, SCHEDULED_BOUNDARIES_QUERY_RESULT_KEY,  pa.getScheduledBoundariesQueryResult());
        encodeObject(serial, EVENT_PARTS_QUERY_RESULT_KEY,           pa.getEventPartsQueryResult());
        encodeObject(serial, EVENT_SELECTIONS_QUERY_RESULT_KEY,      pa.getEventSelectionsQueryResult());
        encodeObject(serial, EVENT_PHASES_QUERY_RESULT_KEY,          pa.getEventPhasesQueryResult());
        encodeObject(serial, EVENT_PHASE_COVERAGES_QUERY_RESULT_KEY, pa.getEventPhaseCoveragesQueryResult());
        encodeObject(serial, ITEM_FAMILY_POLICIES_QUERY_RESULT_KEY,  pa.getItemFamilyPoliciesQueryResult());
        encodeObject(serial, ITEM_POLICIES_QUERY_RESULT_KEY,         pa.getItemPoliciesQueryResult());
        encodeObject(serial, RATES_QUERY_RESULT_KEY,                 pa.getRatesQueryResult());
        encodeObject(serial, BOOKABLE_PERIODS_QUERY_RESULT_KEY,      pa.getBookablePeriodsQueryResult());
    }

    @Override
    public PolicyAggregate decode(ReadOnlyAstObject serial) {
        return new PolicyAggregate(
            decodeObject(serial, EVENT_QUERY_RESULT_KEY),
            decodeObject(serial, SCHEDULED_ITEMS_QUERY_RESULT_KEY),
            decodeObject(serial, SCHEDULED_BOUNDARIES_QUERY_RESULT_KEY),
            decodeObject(serial, EVENT_PARTS_QUERY_RESULT_KEY),
            decodeObject(serial, EVENT_SELECTIONS_QUERY_RESULT_KEY),
            decodeObject(serial, EVENT_PHASES_QUERY_RESULT_KEY),
            decodeObject(serial, EVENT_PHASE_COVERAGES_QUERY_RESULT_KEY),
            decodeObject(serial, ITEM_FAMILY_POLICIES_QUERY_RESULT_KEY),
            decodeObject(serial, ITEM_POLICIES_QUERY_RESULT_KEY),
            decodeObject(serial, RATES_QUERY_RESULT_KEY),
            decodeObject(serial, BOOKABLE_PERIODS_QUERY_RESULT_KEY)
        );
    }
}
