package forge.arena.server;

import forge.arena.server.prompt.PromptCodec;
import forge.arena.server.view.IdMapper;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.interfaces.IGuiGame.OrderResult;
import org.testng.annotations.Test;
import java.util.List;
import static org.testng.Assert.*;

public class AbandonmentDefaultsTest {
    @Test public void orderDefaultsReturnTheSmallestLegalDestinationAndNeverNull() {
        check(List.of("a", "b", "c"), null, 0, 0, List.of("a", "b", "c"));
        check(List.of("a", "b", "c"), List.of("d"), 1, 2, List.of("d", "a"));
        check(List.of(), List.of("a", "b"), 0, 0, List.of("a", "b"));
        check(List.of("a", "b"), List.of(), 1, 1, List.of("a"));
        check(List.of("a"), List.of("d"), 0, -1, List.of());
    }
    private static void check(List<String> source, List<String> destination, int min, int max, List<String> expected) {
        PromptCodec codec = new PromptCodec();
        var prompt = codec.encode(new GuiGameEvent(ProtocolMethod.order, "Order", "First", min, max,
                source, destination, null, false, false), new PromptCodec.Context(null, null, new IdMapper()));
        Object answer = codec.defaultAnswer(prompt); assertNotNull(answer); assertTrue(answer instanceof OrderResult<?>);
        OrderResult<?> order = (OrderResult<?>) answer; assertNotNull(order.ordered());
        assertEquals(order.ordered(), expected); assertFalse(order.rememberDecision());
        int remaining = source.size() + (destination == null ? 0 : destination.size()) - order.ordered().size();
        assertTrue(remaining >= min && (max < 0 || remaining <= max));
    }
}
