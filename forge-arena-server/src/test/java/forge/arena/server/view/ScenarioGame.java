package forge.arena.server.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import forge.arena.server.HeadlessGui;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** AITest's public setup/createCard/addCardToZone pattern, copied without a test-module dependency. */
final class ScenarioGame {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Map<String, JsonSchema> SCHEMAS = new HashMap<>();
    final Game game;
    final IdMapper[] ids = {new IdMapper(), new IdMapper(), new IdMapper(), new IdMapper()};
    private final SeatViewSerializer serializer = new SeatViewSerializer();
    private long seq;
    private int printing;

    static void initialize() {
        if (!SCHEMAS.isEmpty()) {
            return;
        }
        // ArenaServerMain.initializeForge is package-private. Copy its public headless boot calls.
        // Like AITest, initialize outside any timed test so interruption cannot corrupt the card DB.
        GuiBase.setInterface(new HeadlessGui());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.UI_RECORD_REPLAYS, false);
            return null;
        });
        for (final String definition : List.of("state", "event", "log")) {
            SCHEMAS.put(definition, JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    SchemaLocation.of(Path.of("../protocol/schema.json").toAbsolutePath().normalize().toUri()
                            + "#/$defs/" + definition)));
        }
    }

    static void validate(final JsonObject payload, final String definition) {
        final var errors = SCHEMAS.get(definition).validate(JSON.toJson(payload), InputFormat.JSON);
        assertTrue(errors.isEmpty(), definition + " schema errors: " + errors);
    }

    ScenarioGame() {
        final List<RegisteredPlayer> registered = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            registered.add(new RegisteredPlayer(new Deck("Scenario " + i))
                    .setPlayer(GamePlayerUtil.createAiPlayer("Seat " + i)));
        }
        final GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);
        final Match match = new Match(rules, registered, "Hidden information scenario");
        game = new Game(registered, rules, match);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player(0));
        game.getPhaseHandler().onStackResolved();
        for (int i = 0; i < 4; i++) {
            final Card commander = addCardToZone("Isamaru, Hound of Konda", i, ZoneType.Command, false);
            player(i).addCommander(commander);
        }
        assertTrue(game.getRules().hasCommander());
    }

    Player player(final int seat) {
        return game.getPlayers().get(seat);
    }

    Card addCardToZone(final String name, final int seat, final ZoneType zone) {
        return addCardToZone(name, seat, zone, true);
    }

    private Card addCardToZone(final String name, final int seat, final ZoneType zone, final boolean marked) {
        final IPaperCard base = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(base, "Scenario card exists: " + name);
        // Distinct test print metadata makes whole-JSON leak scans meaningful: a shared real
        // edition or collector number such as "1" could appear on an unrelated visible card.
        final int number = ++printing;
        final IPaperCard paper = marked ? new PaperCard(base.getRules(), "PROBESET-" + number,
                base.getRarity(), IPaperCard.DEFAULT_ART_INDEX, false, "probe-collector-" + number,
                IPaperCard.NO_ARTIST_NAME, IPaperCard.NO_FUNCTIONAL_VARIANT) : base;
        final Card card = Card.fromPaperCard(paper, player(seat));
        card.setGameTimestamp(game.getNextTimestamp());
        player(seat).getZone(zone).add(card);
        return card;
    }

    /** No game loop or worker is running: all setup and snapshots run serially on this test thread. */
    JsonObject[] snapshots() {
        final JsonObject[] result = new JsonObject[4];
        final long sequence = ++seq;
        for (int seat = 0; seat < 4; seat++) {
            result[seat] = serializer.buildState(game, player(seat).getView(), ids[seat], sequence);
            validate(result[seat], "state");
        }
        return result;
    }
}
