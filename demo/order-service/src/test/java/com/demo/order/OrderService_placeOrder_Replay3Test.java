package com.demo.order;

import io.debugassist.replay.ReplaySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.Mockito.doAnswer;

/**
 * Replays a call recorded by debug-agent in order-service.
 * <pre>
 * trace    : ea0406648f35c53a84d147e3a9060392
 * recorded : 2026-10-08 16:11:53.778 on qa-host [http-nio-8081-exec-2]
 * request  : POST /orders -> 200
 * call     : #3 OrderService.placeOrder(CreateOrderRequest request)
 * fixtures : src/test/resources/debug-replay/OrderService_placeOrder_Replay3Test/
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderService_placeOrder_Replay3Test {

    private final ReplaySupport replay = new ReplaySupport(getClass());

    @Mock
    private InventoryClient inventoryClient;
    @Mock
    private PricingService pricingService;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService target;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replaysRecordedCall() throws Throwable {
        // InventoryClient.reserve was called 2 time(s) in QA
        doAnswer(replay.answers("mock-1-InventoryClient.reserve.json")).when(inventoryClient).reserve(ArgumentMatchers.<String>any(), ArgumentMatchers.anyInt());
        // PricingService.lineTotal was called 2 time(s) in QA
        doAnswer(replay.answers("mock-2-PricingService.lineTotal.json")).when(pricingService).lineTotal(ArgumentMatchers.<java.math.BigDecimal>any(), ArgumentMatchers.anyInt());
        // OrderRepository.save was called 1 time(s) in QA
        doAnswer(replay.answers("mock-3-OrderRepository.save.json")).when(orderRepository).save(ArgumentMatchers.<Order>any());

        // Inputs captured in QA (edit the JSON files to try other data)
        CreateOrderRequest request = replay.load("arg0-request.json", CreateOrderRequest.class);

        Object actual = target.placeOrder(request);

        // Result returned in QA
        replay.assertMatches("expected-result.json", actual);
    }
}
