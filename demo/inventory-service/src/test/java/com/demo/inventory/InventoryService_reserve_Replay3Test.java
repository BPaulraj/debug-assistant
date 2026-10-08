package com.demo.inventory;

import io.debugassist.replay.ReplaySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;

/**
 * Replays a call recorded by debug-agent in inventory-service.
 * <pre>
 * trace    : ea70ca50206f384ae0b7dced16aba666
 * recorded : 2026-10-08 16:11:53.932 on qa-host [http-nio-8082-exec-4]
 * request  : POST /inventory/LAMP-3/reserve?qty=5 -> 409
 * call     : #3 InventoryService.reserve(String sku, int quantity)
 * fixtures : src/test/resources/debug-replay/InventoryService_reserve_Replay3Test/
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryService_reserve_Replay3Test {

    private final ReplaySupport replay = new ReplaySupport(getClass());

    @Mock
    private StockRepository repository;

    @InjectMocks
    private InventoryService target;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void replaysRecordedCall() throws Throwable {
        // StockRepository.find was called 1 time(s) in QA
        doAnswer(replay.answers("mock-1-StockRepository.find.json")).when(repository).find(ArgumentMatchers.<String>any());

        // Inputs captured in QA (edit the JSON files to try other data)
        String sku = replay.load("arg0-sku.json", String.class);
        int quantity = replay.load("arg1-quantity.json", int.class);

        // In QA this call failed with com.demo.inventory.OutOfStockException: Only 2 of LAMP-3 left, requested 5
        Throwable thrown = assertThrows(Throwable.class, () -> target.reserve(sku, quantity));
        assertEquals("com.demo.inventory.OutOfStockException", thrown.getClass().getName());
    }
}
