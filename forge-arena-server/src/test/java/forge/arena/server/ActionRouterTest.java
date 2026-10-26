package forge.arena.server;

import com.google.gson.*;
import forge.arena.server.view.IdMapper;
import forge.game.Game;
import forge.gamemodes.match.NextGameDecision;
import org.testng.annotations.Test;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
import static org.testng.Assert.*;

public class ActionRouterTest {
    @Test public void schemaAndNextGameMapping() {
        ClientMessageSchema schema = new ClientMessageSchema();
        for (String source : List.of("{\"type\":\"ping\"}", "{\"type\":\"ok\",\"clientSeq\":1,\"baseSeq\":0}",
                "{\"type\":\"selectCard\",\"cardId\":\"h-test\",\"clientSeq\":1,\"baseSeq\":0,\"button\":3}",
                "{\"type\":\"declareAttacker\",\"attackerId\":1,\"defenderId\":{\"kind\":\"player\",\"id\":2},\"clientSeq\":1,\"baseSeq\":0}")) {
            JsonObject message = JsonParser.parseString(source).getAsJsonObject();
            schema.validate(message); PromptAssertions.validate(message, "ClientMessage");
        }
        for (String source : List.of("[]", "null", "{\"type\":\"missing\"}", "{\"type\":\"ok\"}",
                "{\"type\":\"selectCard\",\"cardId\":1.1,\"clientSeq\":1,\"baseSeq\":0}",
                "{\"type\":\"selectCard\",\"cardId\":1,\"button\":2,\"clientSeq\":1,\"baseSeq\":0}",
                "{\"type\":\"useMana\",\"color\":128,\"clientSeq\":1,\"baseSeq\":0}"))
            expectThrows(IllegalArgumentException.class, () -> schema.validate(JsonParser.parseString(source)));
        assertEquals(ActionRouter.nextGame("rematch"), NextGameDecision.NEW);
        assertEquals(ActionRouter.nextGame("quit"), NextGameDecision.QUIT);
        expectThrows(IllegalArgumentException.class, () -> ActionRouter.nextGame("continue"));
        assertEquals(ArenaServerMain.humanSeats("0, 2"), Set.of(0, 2));
        expectThrows(IllegalArgumentException.class, () -> ArenaServerMain.humanSeats("4"));
        assertEquals(new TriggerClick(3).getButton(), 3); assertEquals(new TriggerClick(1).getX(), 0);
    }

    @Test public void rejectsUnknownRetiredAndUnofferedRefs() {
        try (SeatWriter writer = new SeatWriter(0)) {
            JsonSeatRemote remote = new JsonSeatRemote((Game) null, null, writer);
            ActionRouter router = new ActionRouter(remote, true, writer);
            IdMapper ids = remote.getIds();
            JsonObject state = JsonParser.parseString("{\"players\":[]}").getAsJsonObject();
            JsonObject input = JsonParser.parseString("{\"kind\":\"target\",\"selectable\":[]}").getAsJsonObject();
            assertTrue(expectThrows(IllegalArgumentException.class, () -> router.resolve(new JsonPrimitive(7), state, input)).getMessage().contains("stale"));
            ids.beginSnapshot(); Object hidden = ids.wireRef(7, "Hand", false, false); ids.endSnapshot();
            assertTrue(expectThrows(IllegalArgumentException.class, () -> router.resolve(new JsonPrimitive((String) hidden), state, input)).getMessage().contains("not offered"));
            input.getAsJsonArray("selectable").add((String) hidden);
            router.resolve(new JsonPrimitive((String) hidden), state, input);
            ids.beginSnapshot(); ids.endSnapshot();
            assertTrue(expectThrows(IllegalArgumentException.class, () -> router.resolve(new JsonPrimitive((String) hidden), state, input)).getMessage().contains("stale"));
            Object offer = ids.offer(8, "Library", false, true);
            assertEquals(ids.resolve(offer).orElseThrow(), 8);
            ids.retainOffers(Set.of()); assertTrue(ids.resolve(offer).isEmpty());
        }
    }

    @Test public void socketPingOwnershipAndSchemaRejection() throws Exception {
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            new JsonSeatRemote((Game) null, null, server.writer(1)); server.start();
            server.writer(1).bindPlayer(17); // Transport-only fixture: no live game.
            client.connect(HttpClient.newHttpClient(), server.getPort(), "/ws?seat=1"); client.hello(1);
            client.send(JsonParser.parseString("{\"type\":\"ping\"}").getAsJsonObject());
            JsonObject pong = client.receive(); assertEquals(pong.get("type").getAsString(), "pong"); PromptAssertions.validate(pong, "pong");
            client.send(JsonParser.parseString("{\"type\":\"selectCard\",\"cardId\":1,\"clientSeq\":1,\"baseSeq\":0}").getAsJsonObject());
            JsonObject rejected = client.receive(); assertEquals(rejected.get("text").getAsString(), "This seat is not yours to play"); PromptAssertions.validate(rejected, "message");
            client.send(JsonParser.parseString("{\"type\":\"ok\"}").getAsJsonObject());
            rejected = client.receive(); assertTrue(rejected.get("text").getAsString().contains("schema")); PromptAssertions.validate(rejected, "message");
            client.sendRaw("{bad-json");
            rejected = client.receive(); assertEquals(rejected.get("text").getAsString(), "Invalid JSON message"); PromptAssertions.validate(rejected, "message");
        }
    }
}
