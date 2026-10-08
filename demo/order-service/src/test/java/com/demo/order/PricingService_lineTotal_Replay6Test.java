package com.demo.order;

import io.debugassist.replay.ReplaySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


/**
 * Replays a call recorded by debug-agent in order-service.
 * <pre>
 * trace    : ea0406648f35c53a84d147e3a9060392
 * recorded : 2026-10-08 16:11:53.811 on qa-host [http-nio-8081-exec-2]
 * request  : POST /orders -> 200
 * call     : #6 PricingService.lineTotal(BigDecimal unitPrice, int quantity)
 * fixtures : src/test/resources/debug-replay/PricingService_lineTotal_Replay6Test/
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingService_lineTotal_Replay6Test {

    private final ReplaySupport replay = new ReplaySupport(getClass());

    @InjectMocks
    private PricingService target;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replaysRecordedCall() throws Throwable {
        // Inputs captured in QA (edit the JSON files to try other data)
        java.math.BigDecimal unitPrice = replay.load("arg0-unitPrice.json", java.math.BigDecimal.class);
        int quantity = replay.load("arg1-quantity.json", int.class);

        Object actual = target.lineTotal(unitPrice, quantity);

        // Result returned in QA
        replay.assertMatches("expected-result.json", actual);
    }
}
