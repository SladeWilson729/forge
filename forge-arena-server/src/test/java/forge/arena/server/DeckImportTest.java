package forge.arena.server;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.testng.Assert.*;

public class DeckImportTest {
    @BeforeClass public void initialize() { ArenaServerMain.initializeForge(); }

    @Test public void realExports() throws Exception {
        Map<String, String> commanders = Map.of("moxfield-plain-edea.txt", "Edea, Possessed Sorceress",
                "moxfield-mtgo-fandaniel.txt", "Fandaniel, Telophoroi Ascian", "archidekt-text-hulk.txt", "Bruce Banner");
        try (var files = Files.list(Path.of("src/test/resources/decks"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                var result = DeckImport.parse(Files.readString(file), List.of(commanders.get(file.getFileName().toString())));
                System.out.println("[deck-import] " + file + " " + result.json());
                assertTrue(result.ok(), result.json().toString()); assertEquals(result.count(), 100);
            }
        }
    }

    private static String deck(String extra, int plains) {
        return "Commander\n1 Isamaru, Hound of Konda\nMain\n" + plains + " Plains\n" + extra;
    }

    @Test(timeOut = 30_000) public void submitDeckStoresOnlyAcceptedDeck() throws Exception {
        try (WsServer server = new WsServer("127.0.0.1", 0); WsTestClient client = new WsTestClient()) {
            server.start(); server.writer(0).bindPlayer(0);
            client.connect(java.net.http.HttpClient.newHttpClient(), server.getPort(), "/ws?seat=0"); client.hello(0);
            var message = new com.google.gson.JsonObject(); message.addProperty("type", "submitDeck");
            message.addProperty("text", "1 Isamaru, Hound of Konda\n99 Plains");
            message.add("commander", WsServer.GSON.toJsonTree(List.of("Isamaru, Hound of Konda")));
            PromptAssertions.validate(message, "submitDeck"); client.send(message);
            var accepted = client.receive(); PromptAssertions.validate(accepted, "deckResult");
            assertTrue(accepted.get("ok").getAsBoolean(), accepted.toString());
            var stored = server.writer(0).acceptedDeck(); assertNotNull(stored);
            message.addProperty("text", "1 Isamaru, Hound of Konda\n98 Plains"); client.send(message);
            var rejected = client.receive(); PromptAssertions.validate(rejected, "deckResult");
            assertFalse(rejected.get("ok").getAsBoolean()); assertSame(server.writer(0).acceptedDeck(), stored);
            System.out.println("[deck-result-example] " + rejected);
        }
    }

    @Test public void readableErrorsAndCommanderChoice() {
        assertEquals(DeckImport.parse(deck("1 Sol Rign", 98), List.of()).errors().get(0).message(),
                "Unknown card: Sol Rign; check the spelling.");
        assertEquals(DeckImport.parse(deck("", 98), List.of()).errors().get(0).message(),
                "Commander decks must contain exactly 100 cards including commanders; found 99.");
        assertEquals(DeckImport.parse(deck("2 Sol Ring", 97), List.of()).errors().get(0).message(),
                "Too many copies of Sol Ring; Commander allows 1.");
        assertEquals(DeckImport.parse(deck("1 Shock", 98), List.of()).errors().get(0).message(),
                "Cards outside the commander's color identity: Shock.");
        assertEquals(DeckImport.parse("Commander\n1 Grizzly Bears\nMain\n99 Forest", List.of()).errors().get(0).message(),
                "Illegal commander: Grizzly Bears.");
        var missing = DeckImport.parse("1 Isamaru, Hound of Konda\n99 Plains", List.of());
        assertTrue(missing.needsCommander()); assertFalse(missing.ok()); assertTrue(missing.candidates().contains("Isamaru, Hound of Konda"));
        var set = DeckImport.parse(deck("1 Sol Ring (ZZZZZ) 1", 98), List.of());
        assertTrue(set.ok(), set.json().toString());
        assertEquals(set.warnings(), List.of("Line 5: Set or printing not found; using another printing of Sol Ring."));
        assertEquals(DeckImport.parse(deck("%%%%", 99), List.of()).errors().get(0).message(), "Unrecognized deck text.");
        // DeckRecognizer emits UNSUPPORTED_CARD for a card whose section is disallowed,
        // not for a missing implementation. A real Scheme exercises that exact path.
        var recognizer = new forge.deck.DeckRecognizer();
        recognizer.setDeckFormatConstraint(forge.deck.DeckFormat.Commander);
        recognizer.setAllowedDeckSections(new java.util.ArrayList<>(forge.deck.DeckFormat.Commander.getPrimaryDeckSections()));
        assertTrue(recognizer.parseCardList(new String[] {"1 Surrender Your Thoughts"}).stream()
                .anyMatch(t -> t.getType() == forge.deck.DeckRecognizer.TokenType.UNSUPPORTED_CARD));
        assertEquals(DeckImport.parse(deck("1 Surrender Your Thoughts", 98), List.of()).errors().get(0).message(),
                "Forge cannot play Surrender Your Thoughts in a Commander deck.");
        assertTrue(DeckImport.parse("99 Plains\n1 Isamaru, Hound of Konda [Commander{top}]", List.of()).ok());
        assertTrue(DeckImport.parse("Commander\n1 Wilson, Refined Grizzly\n1 Raised by Giants\nMain\n98 Forest", List.of()).ok());
        var banned = DeckImport.parse(deck("1 Black Lotus", 98), List.of());
        assertTrue(banned.ok(), banned.json().toString());
        assertEquals(banned.warnings(), List.of("Black Lotus is banned in Commander; allowed by this server's house rule."));
    }
}
