package forge.arena.server.view;

import com.google.gson.*;
import forge.arena.server.ArenaPuzzle;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.testng.Assert.*;

public class PuzzleStartTest {
    @BeforeClass public void initialize() { ScenarioGame.initialize(); }
    @Test(timeOut = 60_000) public void puzzleSeatsFollowRegisteredIdentityRatherThanSortedPosition() throws Exception {
        ScenarioGame scenario = new ScenarioGame();
        var registered = scenario.game.getRegisteredPlayers().stream().map(p -> p.getRegisteredPlayer()).toList();
        FutureTask<Void> setup = new FutureTask<>(() -> {
            ArenaPuzzle.read(Path.of("tools/puzzles/late-game-4p.pzl")).apply(scenario.game,
                    java.util.List.of(registered.get(2), registered.get(0), registered.get(1), registered.get(3)));
            assertEquals(scenario.player(2).getLife(), 35); assertEquals(scenario.player(0).getLife(), 32);
            assertEquals(scenario.game.getPhaseHandler().getPlayerTurn(), scenario.player(2));
            assertTrue(scenario.player(2).getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals("Kenrith, the Returned King")));
            assertEquals(scenario.game.getCombat().getAttackers().size(), 2); return null;
        });
        scenario.game.getAction().invoke(setup); setup.get(45, TimeUnit.SECONDS);
    }
    @Test(timeOut = 60_000) public void exportedPuzzleMatchesLoadBoardForEverySeat() throws Exception {
        ScenarioGame manual = new ScenarioGame(), load = new ScenarioGame();
        FutureTask<Void> setup = new FutureTask<>(() -> {
            // Reproduce the turn-1 view already published by a started game.
            manual.game.updateTurnForView();
            assertEquals(manual.game.getView().getTurn(), 1);
            ArenaPuzzle.read(Path.of("tools/puzzles/late-game-4p.pzl")).apply(manual.game);
            LoadTest.loadPuzzle(load);
            for (int seat = 0; seat < 4; seat++) {
                var player = manual.player(seat);
                assertEquals(player.getCardsIn(ZoneType.Battlefield).size(), 33);
                assertEquals(player.getCardsIn(ZoneType.Hand).size(), 7);
                assertEquals(player.getCardsIn(ZoneType.Library).size(), 40);
                assertEquals(player.getCardsIn(ZoneType.Graveyard).size(), 8);
                assertEquals(player.getCardsIn(ZoneType.Exile).size(), 3);
                assertEquals(player.getCommanders().size(), 1);
                assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isFaceDown).count(), 1L);
                assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 4L);
            }
            assertEquals(manual.game.getPhaseHandler().getTurn(), 21);
            assertEquals(manual.game.getView().getTurn(), 21, "Puzzle turn reaches the tracked view");
            assertEquals(manual.game.getPhaseHandler().getPhase(), PhaseType.COMBAT_DECLARE_BLOCKERS);
            assertEquals(manual.game.getStack().size(), 3);
            assertEquals(manual.game.getCombat().getAttackers().size(), 2);
            assertEquals(manual.game.getCombat().getAllBlockers().size(), 2);
            JsonObject[] expected = load.snapshots(), actual = manual.snapshots();
            for (int seat = 0; seat < 4; seat++) {
                assertEquals(actual[seat].get("turn").getAsInt(), 21, "Seat " + seat + " receives the puzzle turn");
                assertEquals(normalize(actual[seat]), normalize(expected[seat]), "Seat " + seat + " shared board");
            }
            return null;
        });
        manual.game.getAction().invoke(setup); setup.get(45, TimeUnit.SECONDS);
    }
    private static JsonElement normalize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(e -> {
                // Games have independent reference namespaces; LoadTest substitutes only
                // private printing metadata for its existing whole-JSON leak scanner.
                if (e.getKey().equals("set") || e.getKey().equals("num")) return;
                if (e.getKey().equals("id") || e.getKey().endsWith("Id")) { result.add(e.getKey(), JsonNull.INSTANCE); return; }
                if (e.getKey().endsWith("Ids")) { result.addProperty(e.getKey(), e.getValue().getAsJsonArray().size()); return; }
                result.add(e.getKey(), normalize(e.getValue()));
            });
            return result;
        }
        if (value.isJsonArray()) { JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(normalize(v))); return result; }
        return value;
    }
}
